package tn.hydra.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.security.SessionRevoker;
import tn.hydra.security.ToxicPairService;

import java.util.*;

@Service
public class UserService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final EventLogService audit;
    private final SessionRevoker sessions;
    private final ToxicPairService toxicPairs;

    public UserService(JdbcTemplate jdbc, AppUserRepository users, PasswordEncoder encoder,
                       EventLogService audit, SessionRevoker sessions, ToxicPairService toxicPairs) {
        this.jdbc = jdbc; this.users = users; this.encoder = encoder;
        this.audit = audit; this.sessions = sessions; this.toxicPairs = toxicPairs;
    }

    // ══════════════════ RESPONSE SHAPE ══════════════════

    public record UserResponse(UUID id, String email, String fullName, boolean isOwner,
                               boolean isActive, List<String> roleCodes, List<String> roleLabels) {}

    /** Lightweight picker for assignee dropdowns (ticket forms etc.). */
    public record UserOption(UUID id, String fullName, String email) {}

    // ══════════════════ 1. CHANGE MY PASSWORD ══════════════════

    @Transactional
    public void changeMyPassword(Authentication auth, String oldPassword, String newPassword) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (!encoder.matches(oldPassword, me.getPasswordHash()))
            throw new BusinessRuleException("WRONG_PASSWORD", "Ancien mot de passe incorrect");
        if (newPassword == null || newPassword.length() < 8)
            throw new BusinessRuleException("WEAK_PASSWORD", "Le nouveau mot de passe doit faire au moins 8 caractères");

        jdbc.update("update app_user set password_hash = ?, must_change_password = false where id = ?",
                encoder.encode(newPassword), me.getId());
        // all OTHER devices logged out; the current one survives so the user is not
        // logged out right after changing his own password
        String currentSession = currentSessionId();
        if (currentSession != null) sessions.revokeAllExcept(me.getEmail(), currentSession);
        else sessions.revokeAllFor(me.getEmail());     // non-web fallback: nobody survives
        audit.log("PASSWORD_CHANGED", "app_user", me.getId(), null);
    }

    // ══════════════════ 2. LIST USERS ══════════════════

    public List<UserResponse> listUsers(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select u.id, u.email, u.full_name, u.is_owner, u.is_active, r.code, r.label
                from app_user u
                left join user_role ur on ur.user_id = u.id
                left join role r on r.id = ur.role_id
                where u.org_id = ?
                order by u.full_name, r.code
                """, me.getOrgId());

        Map<UUID, UserResponse> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            UserResponse existing = byId.get(id);
            String roleCode = (String) row.get("code");
            if (existing == null) {
                existing = new UserResponse(id,
                        (String) row.get("email"),
                        (String) row.get("full_name"),
                        Boolean.TRUE.equals(row.get("is_owner")),
                        Boolean.TRUE.equals(row.get("is_active")),
                        new ArrayList<>(), new ArrayList<>());
                byId.put(id, existing);
            }
            if (roleCode != null) {
                existing.roleCodes().add(roleCode);
                existing.roleLabels().add((String) row.get("label"));
            }
        }
        return new ArrayList<>(byId.values());
    }

    // ══════════════════ 2b. ACTIVE USER OPTIONS ══════════════════

    /** Active users of the caller's org, for assignee pickers (needs no USER_* permission). */
    public List<UserOption> userOptions(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        return jdbc.queryForList("""
                select id, full_name, email
                from app_user
                where org_id = ? and is_active = true
                order by full_name
                """, me.getOrgId()).stream()
                .map(r -> new UserOption((UUID) r.get("id"),
                        (String) r.get("full_name"), (String) r.get("email")))
                .toList();
    }

    // ══════════════════ 3. CREATE USER ══════════════════

    @Transactional
    public UserResponse createUser(Authentication auth, String fullName, String email,
                                   String password, List<String> roleCodes, String overrideReason) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();

        if (isBlank(fullName) || isBlank(email) || isBlank(password))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Nom, email et mot de passe sont obligatoires");
        if (!email.contains("@"))
            throw new BusinessRuleException("EMAIL_INVALID", "Format d'email invalide");
        if (users.findByEmail(email).isPresent())
            throw new BusinessRuleException("EMAIL_EXISTS", "Un utilisateur avec cet email existe déjà");
        if (password.length() < 8)
            throw new BusinessRuleException("WEAK_PASSWORD", "Le mot de passe doit faire au moins 8 caractères");
        if (roleCodes == null || roleCodes.isEmpty())
            throw new BusinessRuleException("NO_ROLE", "Sélectionnez au moins un rôle");

        Set<String> codes = new LinkedHashSet<>(roleCodes);
        Map<String, UUID> roleIds = roleIds(codes, me.getOrgId());
        if (roleIds.size() != codes.size())
            throw new BusinessRuleException("UNKNOWN_ROLE", "Un des rôles sélectionnés n'existe pas");

        List<ToxicPairService.RolePairConflict> conflicts = toxicPairs.conflictingPairs(codes);
        if (!conflicts.isEmpty() && isBlank(overrideReason))
            throw new BusinessRuleException("TOXIC_PAIR", conflicts.get(0).reason());
        // a non-empty overrideReason turns the refusal into a signed, audited exception

        UUID id = jdbc.queryForObject("""
                insert into app_user (org_id, email, password_hash, full_name,
                                      is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, false, true, true) returning id
                """, UUID.class, me.getOrgId(), email.toLowerCase(Locale.ROOT),
                encoder.encode(password), fullName.trim());

        for (String code : codes) {
            jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                    id, roleIds.get(code), me.getId());
        }

        if (!conflicts.isEmpty()) {
            for (ToxicPairService.RolePairConflict c : conflicts) {
                jdbc.update("""
                        insert into toxic_pair_override (org_id, user_id, role_a, role_b, reason, granted_by)
                        values (?, ?, ?, ?, ?, ?)
                        """, me.getOrgId(), id, c.roleA(), c.roleB(), overrideReason, me.getId());
            }
            audit.log("TOXIC_PAIR_OVERRIDDEN", "app_user", id,
                    "{\"reason\":\"" + jsonEscape(overrideReason) + "\"}");
        }

        audit.log("USER_CREATED", "app_user", id,
                "{\"email\":\"" + jsonEscape(email) + "\",\"roles\":\"" + jsonEscape(String.join(",", codes)) + "\"}");
        return new UserResponse(id, email, fullName.trim(), false, true,
                new ArrayList<>(codes), labelsOf(roleIds, codes));
    }

    // ══════════════════ 4. DEACTIVATE / REACTIVATE ══════════════════

    @Transactional
    public void deactivateUser(Authentication auth, UUID id) {
        AppUser target = editable(auth, id);
        if (!Boolean.TRUE.equals(target.getIsActive()))
            throw new BusinessRuleException("ALREADY_INACTIVE", "Cet utilisateur est déjà désactivé");

        jdbc.update("update app_user set is_active = false where id = ?", target.getId());
        sessions.revokeAllFor(target.getEmail());       // kill switch: logged out everywhere, now
        audit.log("USER_DEACTIVATED", "app_user", target.getId(),
                "{\"email\":\"" + jsonEscape(target.getEmail()) + "\"}");
    }

    @Transactional
    public void reactivateUser(Authentication auth, UUID id) {
        AppUser target = editable(auth, id);
        if (Boolean.TRUE.equals(target.getIsActive()))
            throw new BusinessRuleException("ALREADY_ACTIVE", "Cet utilisateur est déjà actif");

        jdbc.update("update app_user set is_active = true where id = ?", target.getId());
        audit.log("USER_REACTIVATED", "app_user", target.getId(),
                "{\"email\":\"" + jsonEscape(target.getEmail()) + "\"}");
    }

    // ══════════════════ 5. ADMIN RESET PASSWORD ══════════════════

    @Transactional
    public void resetPassword(Authentication auth, UUID id, String newPassword) {
        AppUser target = editable(auth, id);
        if (target.getId().equals(users.findByEmail(auth.getName()).orElseThrow().getId()))
            throw new BusinessRuleException("SELF_ACTION", "Utilisez le changement de mot de passe pour votre propre compte");
        if (newPassword == null || newPassword.length() < 8)
            throw new BusinessRuleException("WEAK_PASSWORD", "Le nouveau mot de passe doit faire au moins 8 caractères");

        jdbc.update("update app_user set password_hash = ?, must_change_password = true where id = ?",
                encoder.encode(newPassword), target.getId());
        sessions.revokeAllFor(target.getEmail());       // he must log in again with the new password
        audit.log("PASSWORD_RESET", "app_user", target.getId(),
                "{\"email\":\"" + jsonEscape(target.getEmail()) + "\"}");
    }

    // ══════════════════ 6. UPDATE USER ROLES ══════════════════

    @Transactional
    public UserResponse updateUserRoles(Authentication auth, UUID id, List<String> roleCodes,
                                        String overrideReason) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        AppUser target = editable(auth, id);

        if (roleCodes == null || roleCodes.isEmpty())
            throw new BusinessRuleException("NO_ROLE", "Sélectionnez au moins un rôle");
        Set<String> codes = new LinkedHashSet<>(roleCodes);
        Map<String, UUID> roleIds = roleIds(codes, me.getOrgId());
        if (roleIds.size() != codes.size())
            throw new BusinessRuleException("UNKNOWN_ROLE", "Un des rôles sélectionnés n'existe pas");

        // toxic pair without an override reason → 409 {code: TOXIC_PAIR} via GlobalExceptionHandler
        List<ToxicPairService.RolePairConflict> conflicts = toxicPairs.conflictingPairs(codes);
        if (!conflicts.isEmpty() && isBlank(overrideReason))
            throw new BusinessRuleException("TOXIC_PAIR", conflicts.get(0).reason());

        jdbc.update("delete from user_role where user_id = ?", target.getId());
        for (String code : codes) {
            jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                    target.getId(), roleIds.get(code), me.getId());
        }

        if (!conflicts.isEmpty()) {
            jdbc.update("delete from toxic_pair_override where user_id = ?", target.getId());
            for (ToxicPairService.RolePairConflict c : conflicts) {
                jdbc.update("""
                        insert into toxic_pair_override (org_id, user_id, role_a, role_b, reason, granted_by)
                        values (?, ?, ?, ?, ?, ?)
                        """, me.getOrgId(), target.getId(), c.roleA(), c.roleB(), overrideReason, me.getId());
            }
            audit.log("TOXIC_PAIR_OVERRIDDEN", "app_user", target.getId(),
                    "{\"reason\":\"" + jsonEscape(overrideReason) + "\"}");
        }

        sessions.revokeAllFor(target.getEmail());       // his new rights apply on the next login only
        audit.log("USER_ROLES_UPDATED", "app_user", target.getId(),
                "{\"roles\":\"" + jsonEscape(String.join(",", codes)) + "\"}");
        return new UserResponse(target.getId(), target.getEmail(), target.getFullName(),
                Boolean.TRUE.equals(target.getIsOwner()), Boolean.TRUE.equals(target.getIsActive()),
                new ArrayList<>(codes), labelsOf(roleIds, codes));
    }

    // ══════════════════ 7. UPDATE USER (NAME / PASSWORD) ══════════════════

    @Transactional
    public UserResponse updateUser(Authentication auth, UUID id, String fullName, String password) {
        AppUser target = editable(auth, id);
        if (isBlank(fullName))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le nom est obligatoire");
        if (!isBlank(password) && password.length() < 8)
            throw new BusinessRuleException("WEAK_PASSWORD", "Le mot de passe doit faire au moins 8 caractères");

        jdbc.update("update app_user set full_name = ? where id = ?", fullName.trim(), target.getId());
        if (!isBlank(password)) {
            jdbc.update("update app_user set password_hash = ?, must_change_password = true where id = ?",
                    encoder.encode(password), target.getId());
            sessions.revokeAllFor(target.getEmail());   // he must log in again with the new password
        }
        audit.log("USER_UPDATED", "app_user", target.getId(),
                "{\"email\":\"" + jsonEscape(target.getEmail()) + "\"}");

        return new UserResponse(target.getId(), target.getEmail(), fullName.trim(),
                Boolean.TRUE.equals(target.getIsOwner()), Boolean.TRUE.equals(target.getIsActive()),
                roleCodesOf(target.getId()), roleLabelsOf(target.getId()));
    }

    // ══════════════════ HELPERS ══════════════════


    /** Id of the session serving this request, or null outside a web request. */
    private String currentSessionId() {
        if (!(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs)) {
            return null;
        }
        jakarta.servlet.http.HttpSession session =
                servletAttrs.getRequest().getSession(false);
        return session == null ? null : session.getId();
    }

    /** Target must exist, be in the caller's org and be neither the caller nor the owner. */
    private AppUser editable(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        AppUser target = users.findById(id)
                .orElseThrow(() -> new BusinessRuleException("USER_NOT_FOUND", "Utilisateur introuvable"));
        if (!me.getOrgId().equals(target.getOrgId()))
            throw new BusinessRuleException("USER_NOT_FOUND", "Utilisateur introuvable");
        if (target.getId().equals(me.getId()))
            throw new BusinessRuleException("SELF_ACTION", "Cette action n'est pas possible sur votre propre compte");
        if (Boolean.TRUE.equals(target.getIsOwner()))
            throw new BusinessRuleException("OWNER_PROTECTED", "Le compte gérant ne peut pas être modifié");
        return target;
    }

    /** Role codes assigned to a user, system and custom alike, ordered by code. */
    private List<String> roleCodesOf(UUID userId) {
        return jdbc.queryForList("""
                select r.code from user_role ur
                join role r on r.id = ur.role_id
                where ur.user_id = ?
                order by r.code
                """, String.class, userId);
    }

    /** Labels matching {@link #roleCodesOf(UUID)} row for row. */
    private List<String> roleLabelsOf(UUID userId) {
        return jdbc.queryForList("""
                select r.label from user_role ur
                join role r on r.id = ur.role_id
                where ur.user_id = ?
                order by r.code
                """, String.class, userId);
    }

    private Map<String, UUID> roleIds(Set<String> codes, UUID orgId) {
        Map<String, UUID> result = new LinkedHashMap<>();
        for (String code : codes) {
            List<UUID> ids = jdbc.queryForList(
                    "select id from role where code = ? and (org_id is null or org_id = ?)",
                    UUID.class, code, orgId);
            if (!ids.isEmpty()) result.put(code, ids.get(0));
        }
        return result;
    }

    private List<String> labelsOf(Map<String, UUID> roleIds, Set<String> codes) {
        List<String> labels = new ArrayList<>();
        for (String code : codes) {
            List<String> found = jdbc.queryForList(
                    "select label from role where id = ?", String.class, roleIds.get(code));
            labels.add(found.isEmpty() ? code : found.get(0));
        }
        return labels;
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
