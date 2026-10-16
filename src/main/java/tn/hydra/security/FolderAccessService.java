package tn.hydra.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Per-folder ACL (table folder_access, V15).
 *
 * Effective access is the OR of:
 *   1. a direct user grant   (folder_access.user_id = me)
 *   2. a company-role grant  (folder_access.role_code in my role codes)
 *   3. bypass: org OWNER     (app_user.is_owner)
 *   4. bypass: project ADMIN (project_member.project_role = 'ADMIN')
 *
 * The default matrix is seeded when folders are created (ProjectService
 * calls seedDefaults after cloneTemplates) and backfilled by V15 for
 * folders that already existed.
 */
@Service
public class FolderAccessService {

    public enum FolderAction { READ, UPLOAD, DOWNLOAD, APPROVE, DELETE }

    /** One matrix row: which company role gets which flags on which folder code prefix. */
    public record MatrixRow(String prefix, String roleCode, boolean read, boolean upload,
                            boolean download, boolean approve, boolean delete) {}

    /**
     * Default grants per folder code prefix ('01'..'07', also matches legacy
     * '01 ADMIN' / '07_INT' codes). KEEP IN SYNC with the V15 backfill CTE.
     */
    public static final List<MatrixRow> MATRIX = List.of(
            // 01_ADMIN
            new MatrixRow("01", "OWNER",         true, true,  true, true,  true),
            new MatrixRow("01", "DIRECTION",     true, false, true, false, false),
            new MatrixRow("01", "CHEF_PROJET",   true, false, true, false, false),
            new MatrixRow("01", "DOC_CTRL",      true, true,  true, false, true),
            // 02_TECH
            new MatrixRow("02", "OWNER",         true, true,  true, true,  true),
            new MatrixRow("02", "CHEF_PROJET",   true, true,  true, true,  true),
            new MatrixRow("02", "INGENIEUR",     true, true,  true, false, false),
            new MatrixRow("02", "CONDUCTEUR",    true, true,  true, false, false),
            new MatrixRow("02", "QA",            true, false, true, true,  false),
            new MatrixRow("02", "DOC_CTRL",      true, true,  true, false, true),
            // 03_EXEC
            new MatrixRow("03", "OWNER",         true, true,  true, true,  true),
            new MatrixRow("03", "CHEF_PROJET",   true, true,  true, false, false),
            new MatrixRow("03", "CONDUCTEUR",    true, true,  true, false, false),
            new MatrixRow("03", "CHEF_CHANTIER", true, true,  true, false, false),
            new MatrixRow("03", "INGENIEUR",     true, true,  true, false, false),
            // 04_QUAL
            new MatrixRow("04", "OWNER",         true, true,  true, true,  true),
            new MatrixRow("04", "CHEF_PROJET",   true, true,  true, true,  true),
            new MatrixRow("04", "QA",            true, true,  true, true,  false),
            new MatrixRow("04", "DOC_CTRL",      true, true,  true, false, true),
            // 05_ACHAT
            new MatrixRow("05", "OWNER",         true, true,  true, true,  true),
            new MatrixRow("05", "ACHAT",         true, true,  true, false, false),
            new MatrixRow("05", "FINANCE",       true, false, true, false, false),
            new MatrixRow("05", "CHEF_PROJET",   true, true,  true, false, false),
            // 06_FIN — never CONDUCTEUR / CHEF_CHANTIER / SOUS_TRAITANT
            new MatrixRow("06", "OWNER",         true, true,  true, true,  true),
            new MatrixRow("06", "FINANCE",       true, false, true, false, false),
            new MatrixRow("06", "DIRECTION",     true, false, true, false, false),
            // 07_HSE (prefix also matches legacy '07_INT')
            new MatrixRow("07", "OWNER",         true, true,  true, true,  true),
            new MatrixRow("07", "HSE",           true, true,  true, false, false),
            new MatrixRow("07", "CHEF_PROJET",   true, true,  true, false, false),
            new MatrixRow("07", "CONDUCTEUR",    true, true,  true, false, false));

    /** Roles that must never see the Finance folder — hard server-side rule. */
    private static final Set<String> FINANCE_FOLDER_FORBIDDEN = Set.of("SOUS_TRAITANT");

    // ── API DTOs ──────────────────────────────────────────────────────────
    public record GrantRequest(UUID userId, String roleCode,
                               boolean canRead, boolean canUpload, boolean canDownload,
                               boolean canApprove, boolean canDelete) {}

    public record AccessResponse(UUID id, UUID folderId, String roleCode, UUID userId,
                                 String userLabel, boolean canRead, boolean canUpload,
                                 boolean canDownload, boolean canApprove, boolean canDelete,
                                 Instant createdAt) {}

    private record FolderTarget(UUID id, UUID projectId, String code) {}

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public FolderAccessService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.audit = audit;
    }


    // ══════════════════ ENFORCEMENT ══════════════════

    /** True if the user may perform {@code action} on the folder. */
    public boolean canAccessFolder(UUID userId, UUID folderId, FolderAction action) {
        FolderTarget folder = folderTarget(folderId);
        if (isOwner(userId)) return true;
        if (isProjectAdmin(folder.projectId(), userId)) return true;
        ensureSeeded(folder.projectId());
        Boolean ok = jdbc.query("""
                select coalesce(bool_or(can_read), false),
                       coalesce(bool_or(can_upload), false),
                       coalesce(bool_or(can_download), false),
                       coalesce(bool_or(can_approve), false),
                       coalesce(bool_or(can_delete), false)
                from folder_access
                where folder_id = ?
                  and (user_id = ? or role_code in (
                        select r.code from user_role ur
                        join role r on r.id = ur.role_id
                        where ur.user_id = ?))
                """, rs -> {
                    if (!rs.next()) return false;
                    return switch (action) {
                        case READ -> rs.getBoolean(1);
                        case UPLOAD -> rs.getBoolean(2);
                        case DOWNLOAD -> rs.getBoolean(3);
                        case APPROVE -> rs.getBoolean(4);
                        case DELETE -> rs.getBoolean(5);
                    };
                }, folderId, userId, userId);
        return Boolean.TRUE.equals(ok);
    }

    /**
     * Hard assertion used by document services. READ denial hides the folder
     * (404 FOLDER_NOT_FOUND, no existence leak); other actions -> 403.
     */
    public void assertAccess(UUID userId, UUID folderId, FolderAction action) {
        if (canAccessFolder(userId, folderId, action)) return;
        if (action == FolderAction.READ)
            throw new ResourceNotFoundException("FOLDER_NOT_FOUND", "Dossier introuvable");
        throw new org.springframework.security.access.AccessDeniedException(
                "Accès refusé au dossier (" + action + ")");
    }

    /**
     * Folder ids of the project the user can at least READ.
     * Returns null = "no filter" (org owner or project ADMIN sees everything);
     * empty set = sees nothing (list endpoints must return empty).
     */
    public java.util.Set<UUID> readableFolderIds(AppUser me, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return null;
        if (projectId != null && isProjectAdmin(projectId, me.getId())) return null;
        return jdbc.queryForList("""
                select distinct fa.folder_id
                from folder_access fa
                join doc_folder f on f.id = fa.folder_id
                where (?::uuid is null or f.project_id = ?)
                  and (fa.user_id = ? or fa.role_code in (
                        select r.code from user_role ur
                        join role r on r.id = ur.role_id
                        where ur.user_id = ?))
                """, UUID.class, projectId, projectId, me.getId(), me.getId())
                .stream().collect(java.util.stream.Collectors.toSet());
    }

    // ══════════════════ SEEDING (idempotent) ══════════════════

    /**
     * Inserts the default matrix rows for every folder of the project that has
     * no folder_access rows yet. REQUIRES_NEW so it also works when called
     * lazily from readOnly transactions (e.g. during a document read).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int seedDefaults(UUID projectId) {
        List<Map<String, Object>> folders = jdbc.queryForList("""
                select f.id, f.code from doc_folder f
                where f.project_id = ?
                  and not exists (select 1 from folder_access fa where fa.folder_id = f.id)
                """, projectId);
        int inserted = 0;
        for (Map<String, Object> f : folders) {
            String code = (String) f.get("code");
            String prefix = code == null || code.length() < 2 ? null : code.substring(0, 2);
            UUID folderId = (UUID) f.get("id");
            for (MatrixRow m : MATRIX) {
                if (!m.prefix().equals(prefix)) continue;
                inserted += jdbc.update("""
                        insert into folder_access (folder_id, role_code, can_read, can_upload,
                                                   can_download, can_approve, can_delete)
                        values (?, ?, ?, ?, ?, ?, ?)
                        on conflict (folder_id, role_code) where role_code is not null do nothing
                        """, folderId, m.roleCode(), m.read(), m.upload(),
                        m.download(), m.approve(), m.delete());
            }
        }
        if (inserted > 0)
            audit.log("FOLDER_ACCESS_SEEDED", "project", projectId, "{\"grants\":" + inserted + "}");
        return inserted;
    }

    // ══════════════════ ACL MANAGEMENT ══════════════════

    public List<AccessResponse> list(Authentication auth, UUID projectId, UUID folderId) {
        AppUser me = requireUser(auth);
        assertFolderInProject(projectId, folderId);
        assertCanManage(me, projectId);
        return jdbc.queryForList("""
                select fa.id, fa.folder_id, fa.role_code, fa.user_id, u.full_name, u.email,
                       fa.can_read, fa.can_upload, fa.can_download, fa.can_approve,
                       fa.can_delete, fa.created_at
                from folder_access fa
                left join app_user u on u.id = fa.user_id
                where fa.folder_id = ?
                order by fa.role_code nulls last, u.full_name
                """, folderId).stream().map(FolderAccessService::toAccessResponse).toList();
    }

    @Transactional
    public AccessResponse grant(Authentication auth, UUID projectId, UUID folderId, GrantRequest body) {
        AppUser me = requireUser(auth);
        FolderTarget folder = assertFolderInProject(projectId, folderId);
        assertCanManage(me, projectId);

        boolean hasRole = body.roleCode() != null && !body.roleCode().isBlank();
        boolean hasUser = body.userId() != null;
        if (hasRole == hasUser) // both or none
            throw new BadRequestException("INVALID_TARGET", "Fournissez roleCode ou userId (exclusivement)");

        if (hasRole) {
            String code = body.roleCode().trim();
            // hard rule first (before any DB lookup): never grant on a Finance folder
            if (FINANCE_FOLDER_FORBIDDEN.contains(code) && folder.code() != null
                    && folder.code().startsWith("06"))
                throw new BadRequestException("FOLDER_ACCESS_FORBIDDEN",
                        "Accès refusé : ce rôle ne peut pas accéder au dossier Finance");
            Integer found = jdbc.queryForObject(
                    "select count(*) from role where code = ?", Integer.class, code);
            if (found == null || found == 0)
                throw new BadRequestException("ROLE_NOT_FOUND", "Rôle introuvable : " + code);
            jdbc.update("""
                    insert into folder_access (folder_id, role_code, can_read, can_upload,
                                               can_download, can_approve, can_delete, granted_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (folder_id, role_code) where role_code is not null
                    do update set can_read = excluded.can_read,
                                  can_upload = excluded.can_upload,
                                  can_download = excluded.can_download,
                                  can_approve = excluded.can_approve,
                                  can_delete = excluded.can_delete,
                                  granted_by = excluded.granted_by
                    """, folderId, code, body.canRead(), body.canUpload(),
                    body.canDownload(), body.canApprove(), body.canDelete(), me.getId());
            audit.log("FOLDER_ACCESS_GRANTED", "doc_folder", folderId,
                    "{\"roleCode\":\"" + code + "\"}");
            return findGrantByRole(folderId, code);
        }

        AppUser target = activeUserInOrg(body.userId(), me.getOrgId());
        jdbc.update("""
                insert into folder_access (folder_id, user_id, can_read, can_upload,
                                           can_download, can_approve, can_delete, granted_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (folder_id, user_id) where user_id is not null
                do update set can_read = excluded.can_read,
                              can_upload = excluded.can_upload,
                              can_download = excluded.can_download,
                              can_approve = excluded.can_approve,
                              can_delete = excluded.can_delete,
                              granted_by = excluded.granted_by
                """, folderId, target.getId(), body.canRead(), body.canUpload(),
                body.canDownload(), body.canApprove(), body.canDelete(), me.getId());
        audit.log("FOLDER_ACCESS_GRANTED", "doc_folder", folderId,
                "{\"userId\":\"" + target.getId() + "\"}");
        return findGrantByUser(folderId, target.getId());
    }

    @Transactional
    public void revoke(Authentication auth, UUID projectId, UUID folderId, UUID accessId) {
        AppUser me = requireUser(auth);
        assertFolderInProject(projectId, folderId);
        assertCanManage(me, projectId);
        int deleted = jdbc.update(
                "delete from folder_access where id = ? and folder_id = ?", accessId, folderId);
        if (deleted == 0)
            throw new ResourceNotFoundException("FOLDER_ACCESS_NOT_FOUND", "Accès introuvable");
        audit.log("FOLDER_ACCESS_REVOKED", "doc_folder", folderId,
                "{\"accessId\":\"" + accessId + "\"}");
    }


    // ══════════════════ HELPERS ══════════════════

    private AppUser requireUser(Authentication auth) {
        return users.findByEmail(auth.getName()).orElseThrow();
    }

    private FolderTarget folderTarget(UUID folderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, project_id, code from doc_folder where id = ?", folderId);
        if (rows.isEmpty())
            throw new ResourceNotFoundException("FOLDER_NOT_FOUND", "Dossier introuvable");
        Map<String, Object> r = rows.get(0);
        return new FolderTarget((UUID) r.get("id"), (UUID) r.get("project_id"), (String) r.get("code"));
    }

    private FolderTarget assertFolderInProject(UUID projectId, UUID folderId) {
        FolderTarget t = folderTarget(folderId);
        if (!t.projectId().equals(projectId))
            throw new ResourceNotFoundException("FOLDER_NOT_FOUND", "Dossier introuvable");
        return t;
    }

    /** Owner, project ADMIN, or holder of PERM_FOLDER_MANAGE may change ACLs. */
    private void assertCanManage(AppUser me, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        if (isProjectAdmin(projectId, me.getId())) return;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean hasPerm = auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "PERM_FOLDER_MANAGE".equals(a.getAuthority()));
        if (!hasPerm)
            throw new AccessDeniedException("Seul un administrateur peut modifier les accès du dossier");
    }

    private boolean isOwner(UUID userId) {
        Boolean ok = jdbc.query("select is_owner from app_user where id = ?",
                rs -> rs.next() && rs.getBoolean(1), userId);
        return Boolean.TRUE.equals(ok);
    }

    private boolean isProjectAdmin(UUID projectId, UUID userId) {
        Boolean ok = jdbc.query("""
                select project_role = 'ADMIN' from project_member
                where project_id = ? and user_id = ?
                """, rs -> rs.next() && "ADMIN".equals(rs.getString(1)), projectId, userId);
        return Boolean.TRUE.equals(ok);
    }

    private void ensureSeeded(UUID projectId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from folder_access fa join doc_folder f on f.id = fa.folder_id " +
                        "where f.project_id = ?", Integer.class, projectId);
        if (n == null || n == 0) seedDefaults(projectId); // own transaction
    }

    private AppUser activeUserInOrg(UUID userId, UUID orgId) {
        AppUser target = users.findById(userId)
                .orElseThrow(() -> new BusinessRuleException("USER_NOT_FOUND", "Utilisateur introuvable"));
        if (!orgId.equals(target.getOrgId()) || !Boolean.TRUE.equals(target.getIsActive()))
            throw new BusinessRuleException("USER_NOT_FOUND", "Utilisateur introuvable");
        return target;
    }

    private AccessResponse findGrantByRole(UUID folderId, String roleCode) {
        return jdbc.queryForList("""
                select fa.id, fa.folder_id, fa.role_code, fa.user_id, null as full_name,
                       null as email, fa.can_read, fa.can_upload, fa.can_download,
                       fa.can_approve, fa.can_delete, fa.created_at
                from folder_access fa where fa.folder_id = ? and fa.role_code = ?
                """, folderId, roleCode).stream()
                .map(FolderAccessService::toAccessResponse).findFirst().orElseThrow();
    }

    private AccessResponse findGrantByUser(UUID folderId, UUID userId) {
        return jdbc.queryForList("""
                select fa.id, fa.folder_id, fa.role_code, fa.user_id, u.full_name, u.email,
                       fa.can_read, fa.can_upload, fa.can_download,
                       fa.can_approve, fa.can_delete, fa.created_at
                from folder_access fa
                left join app_user u on u.id = fa.user_id
                where fa.folder_id = ? and fa.user_id = ?
                """, folderId, userId).stream()
                .map(FolderAccessService::toAccessResponse).findFirst().orElseThrow();
    }

    private static AccessResponse toAccessResponse(Map<String, Object> row) {
        String full = (String) row.get("full_name");
        String email = (String) row.get("email");
        String label = full != null ? full : email;
        Object created = row.get("created_at");
        Instant createdAt = created instanceof Timestamp ts ? ts.toInstant()
                : created instanceof Instant i ? i : null;
        return new AccessResponse(
                (UUID) row.get("id"),
                (UUID) row.get("folder_id"),
                (String) row.get("role_code"),
                (UUID) row.get("user_id"),
                label,
                Boolean.TRUE.equals(row.get("can_read")),
                Boolean.TRUE.equals(row.get("can_upload")),
                Boolean.TRUE.equals(row.get("can_download")),
                Boolean.TRUE.equals(row.get("can_approve")),
                Boolean.TRUE.equals(row.get("can_delete")),
                createdAt);
    }
}

