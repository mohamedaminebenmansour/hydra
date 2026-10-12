package tn.hydra.role;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.*;

@Service
public class RoleService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public RoleService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.audit = audit;
    }

    // ══════════════════ RESPONSE SHAPE ══════════════════

    public record RoleResponse(UUID id, String code, String label, boolean isSystem,
                               List<String> permissions) {}
    public record PermissionResponse(String id, String description) {}

    // ══════════════════ 1. LIST ROLES (system + my org) ══════════════════

    public List<RoleResponse> listRoles(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.id, r.code, r.label, r.org_id, p.id as permission_id
                from role r
                left join role_permission rp on rp.role_id = r.id
                left join permission p on p.id = rp.permission_id
                where r.org_id is null or r.org_id = ?
                order by r.org_id is not null, r.label, p.id
                """, me.getOrgId());

        Map<UUID, RoleResponse> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            RoleResponse existing = byId.get(id);
            if (existing == null) {
                existing = new RoleResponse(id,
                        (String) row.get("code"),
                        (String) row.get("label"),
                        row.get("org_id") == null,
                        new ArrayList<>());
                byId.put(id, existing);
            }
            String perm = (String) row.get("permission_id");
            if (perm != null) existing.permissions().add(perm);
        }
        return new ArrayList<>(byId.values());
    }

    // ══════════════════ 2. LIST ALL PERMISSIONS ══════════════════

    public List<PermissionResponse> listPermissions() {
        return jdbc.query("select id, description from permission order by id",
                (rs, i) -> new PermissionResponse(rs.getString("id"), rs.getString("description")));
    }

    // ══════════════════ 3. CREATE CUSTOM ROLE ══════════════════

    @Transactional
    public RoleResponse createRole(Authentication auth, String label, List<String> permissionCodes) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (isBlank(label))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le libellé est obligatoire");

        String code = slug(label);
        if (isBlank(code))
            throw new BusinessRuleException("INVALID_CODE", "Le libellé ne contient aucun caractère valide");
        // unique per org; system codes are refused too so role lookups stay unambiguous
        List<String> existing = jdbc.queryForList(
                "select id from role where code = ? and (org_id = ? or org_id is null)",
                String.class, code, me.getOrgId());
        if (!existing.isEmpty())
            throw new BusinessRuleException("CODE_EXISTS", "Un rôle avec ce code existe déjà");

        UUID id = jdbc.queryForObject("""
                insert into role (org_id, code, label, scope_type) values (?, ?, ?, 'ALL') returning id
                """, UUID.class, me.getOrgId(), code, label.trim());
        List<String> permissions = insertPermissions(id, permissionCodes);

        audit.log("ROLE_CREATED", "role", id, "{\"code\":\"" + jsonEscape(code) + "\"}");
        return new RoleResponse(id, code, label.trim(), false, permissions);
    }

    // ══════════════════ 4. UPDATE CUSTOM ROLE ══════════════════

    @Transactional
    public RoleResponse updateRole(Authentication auth, UUID id, String label, List<String> permissionCodes) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> found = jdbc.queryForList("select org_id, code from role where id = ?", id);
        if (found.isEmpty())
            throw new BusinessRuleException("ROLE_NOT_FOUND", "Rôle introuvable");
        if (found.get(0).get("org_id") == null)
            throw new BusinessRuleException("SYSTEM_ROLE", "Les rôles système ne peuvent pas être modifiés");
        if (!me.getOrgId().equals(found.get(0).get("org_id")))
            throw new BusinessRuleException("ROLE_NOT_FOUND", "Rôle introuvable");
        if (isBlank(label))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le libellé est obligatoire");

        String code = (String) found.get(0).get("code");
        jdbc.update("update role set label = ? where id = ?", label.trim(), id);
        jdbc.update("delete from role_permission where role_id = ?", id);
        List<String> permissions = insertPermissions(id, permissionCodes);

        audit.log("ROLE_UPDATED", "role", id, "{\"code\":\"" + jsonEscape(code) + "\"}");
        return new RoleResponse(id, code, label.trim(), false, permissions);
    }

    // ══════════════════ HELPERS ══════════════════

    /** Inserts one role_permission row per permission id that really exists; unknown codes are ignored. */
    private List<String> insertPermissions(UUID roleId, List<String> permissionCodes) {
        List<String> attached = new ArrayList<>();
        if (permissionCodes == null) return attached;
        for (String pc : new LinkedHashSet<>(permissionCodes)) {
            if (isBlank(pc)) continue;
            List<String> known = jdbc.queryForList("select id from permission where id = ?", String.class, pc);
            if (!known.isEmpty()) {
                jdbc.update("insert into role_permission (role_id, permission_id) values (?, ?)", roleId, pc);
                attached.add(pc);
            }
        }
        attached.sort(Comparator.naturalOrder());
        return attached;
    }

    /** "Chef de projet" → "CHEF_DE_PROJET": uppercased, every non-alphanumeric becomes _. */
    private String slug(String label) {
        return label.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
