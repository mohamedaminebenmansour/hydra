package tn.hydra.project;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.security.FolderAccessService;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectService {

    private static final Set<String> STATUSES = Set.of("ACTIF", "CLOTURE");
    private static final Set<String> PROJECT_ROLES = Set.of("ADMIN", "MANAGER", "ENGINEER", "VIEWER");

    /** Column list shared by list/get so member_count is computed the same way everywhere. */
    private static final String PROJECT_COLUMNS = """
            select p.id, p.code, p.name, p.status,
                   p.client_name, p.location, p.governorate,
                   p.start_date, p.end_date, p.project_type, p.description,
                   p.contract_value, p.currency, p.created_at,
                   (select count(*) from project_member pm where pm.project_id = p.id) as member_count
            from project p
            """;

    /** Hard fallback templates (code, name, sort_order) so create() never yields 0 folders. */
    private static final List<String[]> DEFAULT_TEMPLATES = List.of(
            new String[]{"01_ADMIN", "Administratif",       "1"},
            new String[]{"02_TECH",  "Technique",           "2"},
            new String[]{"03_EXEC",  "ExÃ©cution",           "3"},
            new String[]{"04_QUAL",  "QualitÃ© / RÃ©ception", "4"},
            new String[]{"05_ACHAT", "Achats",              "5"},
            new String[]{"06_FIN",   "Finance",             "6"},
            new String[]{"07_HSE",   "HSE & Environnement", "7"});
    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;
    private final FolderAccessService folderAccess;

    public ProjectService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit,
                          FolderAccessService folderAccess) {
        this.jdbc = jdbc; this.users = users; this.audit = audit; this.folderAccess = folderAccess;
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• RESPONSE SHAPE â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    public record ProjectResponse(UUID id, String code, String name, String status,
                                  String clientName, String location, String governorate,
                                  LocalDate startDate, LocalDate endDate,
                                  String projectType, String description,
                                  BigDecimal contractValue, String currency,
                                  Integer memberCount, Instant createdAt) {}

    public record ProjectOption(UUID id, String name) {}

    public record FolderResponse(UUID id, UUID projectId, UUID parentId,
                                 String code, String name, Integer sortOrder,
                                 Instant createdAt) {}

    public record MemberResponse(UUID userId, String fullName, String email,
                                 String projectRole, boolean canUpload, Instant createdAt) {}

    public record CreateProjectRequest(String name, String code, String clientName,
                                       String location, String governorate,
                                       LocalDate startDate, LocalDate endDate,
                                       String projectType, String description,
                                       BigDecimal contractValue, String currency,
                                       List<UUID> memberUserIds) {}

    public record UpdateProjectRequest(String name, String code, String status,
                                       String clientName, String location, String governorate,
                                       LocalDate startDate, LocalDate endDate,
                                       String projectType, String description,
                                       BigDecimal contractValue, String currency) {}

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• 1. LIST PROJECTS â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    public List<ProjectResponse> list(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (companyWide(auth, me)) {
            return jdbc.queryForList(PROJECT_COLUMNS +
                            " where p.org_id = ? order by p.name", me.getOrgId())
                    .stream().map(ProjectService::toResponse).toList();
        }
        // non-admin: only projects the user belongs to
        return jdbc.queryForList(PROJECT_COLUMNS +
                        " where p.org_id = ? and exists (select 1 from project_member pm " +
                        " where pm.project_id = p.id and pm.user_id = ?) order by p.name",
                me.getOrgId(), me.getId())
                .stream().map(ProjectService::toResponse).toList();
    }

    public List<ProjectOption> projectOptions(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (companyWide(auth, me)) {
            return jdbc.queryForList("""
                    select p.id, p.name from project p
                    where p.org_id = ? and p.status = 'ACTIF'
                    order by p.name
                    """, me.getOrgId()).stream()
                    .map(r -> new ProjectOption((UUID) r.get("id"), (String) r.get("name")))
                    .toList();
        }
        return jdbc.queryForList("""
                select p.id, p.name from project p
                where p.org_id = ? and p.status = 'ACTIF'
                  and exists (select 1 from project_member pm
                              where pm.project_id = p.id and pm.user_id = ?)
                order by p.name
                """, me.getOrgId(), me.getId()).stream()
                .map(r -> new ProjectOption((UUID) r.get("id"), (String) r.get("name")))
                .toList();
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• 2. GET ONE PROJECT (null â†’ 404) â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    public ProjectResponse get(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> rows;
        if (companyWide(auth, me)) {
            rows = jdbc.queryForList(
                    PROJECT_COLUMNS + " where p.id = ? and p.org_id = ?", id, me.getOrgId());
        } else {
            rows = jdbc.queryForList(PROJECT_COLUMNS +
                    " where p.id = ? and p.org_id = ? and exists (select 1 from project_member pm " +
                    " where pm.project_id = p.id and pm.user_id = ?)", id, me.getOrgId(), me.getId());
        }
        if (rows.isEmpty()) return null;
        // self-heal: repair missing folders so the UI just works without an extra button
        repairFoldersIfEmpty(id, me.getOrgId());
        return toResponse(rows.get(0));
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• 3. CREATE PROJECT â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Transactional
    public ProjectResponse create(Authentication auth, CreateProjectRequest body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (isBlank(body.name()))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le nom est obligatoire");

        UUID id = jdbc.queryForObject("""
                insert into project (org_id, code, name, status, client_name, location,
                                     governorate, start_date, end_date, project_type,
                                     description, contract_value, currency, created_by)
                values (?, ?, ?, 'ACTIF', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, UUID.class,
                me.getOrgId(), blankToNull(body.code()), body.name().trim(),
                blankToNull(body.clientName()), blankToNull(body.location()),
                blankToNull(body.governorate()),
                body.startDate(), body.endDate(),
                blankToNull(body.projectType()), blankToNull(body.description()),
                body.contractValue(),
                blankToNull(body.currency()) == null ? "TND" : body.currency(),
                me.getId());

        // b/c) folder templates: org-specific first, else global; hard fallback if none.
        // Cloned flat (parent_id null) so create() never produces 0 folders.
        int folderCount = cloneTemplates(id, me.getOrgId());
        // seed the default folder ACL matrix (role grants per folder code)
        folderAccess.seedDefaults(id);

        // d) insert project_member for creator as MANAGER, can_upload true
        jdbc.update("""
                insert into project_member (project_id, user_id, project_role, can_upload, created_by)
                values (?, ?, 'MANAGER', true, ?)
                on conflict (project_id, user_id) do nothing
                """, id, me.getId(), me.getId());

        // e) insert additional members (ENGINEER) if same org and active
        if (body.memberUserIds() != null) {
            for (UUID userId : body.memberUserIds()) {
                if (userId == null || userId.equals(me.getId())) continue;
                AppUser target = activeUserInOrg(userId, me.getOrgId());
                jdbc.update("""
                        insert into project_member (project_id, user_id, project_role, can_upload, created_by)
                        values (?, ?, 'ENGINEER', true, ?)
                        on conflict (project_id, user_id) do nothing
                        """, id, target.getId(), me.getId());
                audit.log("PROJECT_MEMBER_ADDED", "project", id,
                        "{\"userId\":\"" + target.getId() + "\",\"role\":\"ENGINEER\"}");
            }
        }

        // f) audit with name + folder count
        audit.log("PROJECT_CREATED", "project", id,
                "{\"name\":\"" + jsonEscape(body.name().trim()) +
                        "\",\"folders\":" + folderCount + "}");

        // g) return full response
        return get(auth, id);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• 4. UPDATE PROJECT â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    @Transactional
    public ProjectResponse update(Authentication auth, UUID id, UpdateProjectRequest body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (isBlank(body.name()))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le nom est obligatoire");
        if (body.status() == null || !STATUSES.contains(body.status()))
            throw new BusinessRuleException("INVALID_STATUS", "Statut invalide");
        List<UUID> found = jdbc.queryForList(
                "select id from project where id = ? and org_id = ?", UUID.class, id, me.getOrgId());
        if (found.isEmpty())
            throw new BusinessRuleException("PROJECT_NOT_FOUND", "Projet introuvable");

        jdbc.update("""
                update project set
                    name = ?, code = ?, status = ?,
                    client_name = ?, location = ?, governorate = ?,
                    start_date = ?, end_date = ?, project_type = ?,
                    description = ?, contract_value = ?, currency = ?
                where id = ?
                """, body.name().trim(), blankToNull(body.code()), body.status(),
                blankToNull(body.clientName()), blankToNull(body.location()),
                blankToNull(body.governorate()),
                body.startDate(), body.endDate(), blankToNull(body.projectType()),
                blankToNull(body.description()), body.contractValue(),
                blankToNull(body.currency()) == null ? "TND" : body.currency(),
                id);

        audit.log("PROJECT_UPDATED", "project", id,
                "{\"name\":\"" + jsonEscape(body.name().trim()) +
                        "\",\"status\":\"" + body.status() + "\"}");
        return get(auth, id);
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• 5. FOLDERS â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    public List<FolderResponse> listFolders(Authentication auth, UUID projectId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        requireReadable(auth, me, projectId);
        // folder ACL: null = no filter (owner / project ADMIN), empty set = no folders
        java.util.Set<UUID> readable = folderAccess.readableFolderIds(me, projectId);
        if (readable != null && readable.isEmpty()) return List.of();
        java.util.Set<UUID> filter = readable;
        return jdbc.queryForList("""
                select id, project_id, parent_id, code, name, sort_order, created_at
                from doc_folder
                where project_id = ?
                order by sort_order, name
                """, projectId).stream()
                .filter(r -> filter == null || filter.contains((UUID) r.get("id")))
                .map(ProjectService::toFolderResponse).toList();
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• 6. MEMBERS â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    public List<MemberResponse> listMembers(Authentication auth, UUID projectId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        requireReadable(auth, me, projectId);
        return jdbc.queryForList("""
                select pm.user_id, u.full_name, u.email,
                       pm.project_role, pm.can_upload, pm.created_at
                from project_member pm
                join app_user u on u.id = pm.user_id
                where pm.project_id = ?
                order by u.full_name
                """, projectId).stream()
                .map(ProjectService::toMemberResponse).toList();
    }

    /** Caller's effective rights on a project (drives UI gating, e.g. upload button). */
    public record MyPermissionsResponse(boolean isMember, String projectRole, boolean canUpload) {}

    public MyPermissionsResponse myPermissions(Authentication auth, UUID projectId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (!projectExists(projectId, me.getOrgId()))
            throw new BusinessRuleException("PROJECT_NOT_FOUND", "Projet introuvable");
        // Owner can always act.
        if (Boolean.TRUE.equals(me.getIsOwner()))
            return new MyPermissionsResponse(true, "ADMIN", true);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select project_role, can_upload
                from project_member
                where project_id = ? and user_id = ?
                """, projectId, me.getId());
        if (rows.isEmpty()) {
            // Not a member: company-wide readers can still see, but never upload.
            boolean readOnly = companyWide(auth, me);
            return new MyPermissionsResponse(readOnly, readOnly ? "VIEWER" : null, false);
        }
        Map<String, Object> row = rows.get(0);
        String role = (String) row.get("project_role");
        boolean canUpload = Boolean.TRUE.equals(row.get("can_upload"))
                || "ADMIN".equals(role) || "MANAGER".equals(role);
        return new MyPermissionsResponse(true, role, canUpload);
    }

    @Transactional
    public MemberResponse addMember(Authentication auth, UUID projectId,
                                    UUID userId, String projectRole, Boolean canUpload) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        requireManageable(auth, me, projectId);
        String role = projectRole == null ? "ENGINEER" : projectRole;
        if (!PROJECT_ROLES.contains(role))
            throw new BusinessRuleException("INVALID_ROLE", "RÃ´le de projet invalide");
        boolean upload = canUpload == null || canUpload;
        AppUser target = activeUserInOrg(userId, me.getOrgId());

        List<Map<String, Object>> existing = jdbc.queryForList("""
                select 1 from project_member
                where project_id = ? and user_id = ?
                """, projectId, target.getId());
        if (!existing.isEmpty())
            throw new BusinessRuleException("MEMBER_EXISTS",
                    "Ce membre existe déjà dans ce projet");

        jdbc.update("""
                insert into project_member (project_id, user_id, project_role, can_upload, created_by)
                values (?, ?, ?, ?, ?)
                """, projectId, target.getId(), role, upload, me.getId());

        audit.log("PROJECT_MEMBER_ADDED", "project", projectId,
                "{\"userId\":\"" + target.getId() + "\",\"role\":\"" + role + "\"}");
        return memberRow(projectId, target.getId());
    }

    @Transactional
    public MemberResponse updateMember(Authentication auth, UUID projectId,
                                       UUID userId, String projectRole, Boolean canUpload) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        requireManageable(auth, me, projectId);
        if (projectRole == null || !PROJECT_ROLES.contains(projectRole))
            throw new BusinessRuleException("INVALID_ROLE", "Rôle de projet invalide");
        boolean upload = canUpload == null || canUpload;
        AppUser target = activeUserInOrg(userId, me.getOrgId());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select project_role from project_member where project_id = ? and user_id = ?",
                projectId, target.getId());
        if (rows.isEmpty())
            throw new BusinessRuleException("MEMBER_NOT_FOUND", "Membre introuvable");
        // cannot demote the last ADMIN/MANAGER
        String currentRole = (String) rows.get(0).get("project_role");
        if (("ADMIN".equals(currentRole) || "MANAGER".equals(currentRole))
                && !"ADMIN".equals(projectRole) && !"MANAGER".equals(projectRole)
                && countLeaders(projectId) <= 1)
            throw new BusinessRuleException("LAST_MANAGER",
                    "Impossible de rétrograder le dernier chef de projet");

        jdbc.update("""
                update project_member set project_role = ?, can_upload = ?
                where project_id = ? and user_id = ?
                """, projectRole, upload, projectId, target.getId());

        audit.log("PROJECT_MEMBER_UPDATED", "project", projectId,
                "{\"userId\":\"" + target.getId() + "\",\"role\":\"" + projectRole + "\"}");
        return memberRow(projectId, target.getId());
    }

    @Transactional
    public void removeMember(Authentication auth, UUID projectId, UUID userId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        requireManageable(auth, me, projectId);
        AppUser target = activeUserInOrg(userId, me.getOrgId());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select project_role from project_member where project_id = ? and user_id = ?",
                projectId, target.getId());
        if (rows.isEmpty())
            throw new BusinessRuleException("MEMBER_NOT_FOUND", "Membre introuvable");
        String role = (String) rows.get(0).get("project_role");
        boolean isLeader = "ADMIN".equals(role) || "MANAGER".equals(role);
        // cannot remove the last ADMIN/MANAGER (also covers self-removal when sole leader)
        if (isLeader && countLeaders(projectId) <= 1)
            throw new BusinessRuleException("LAST_MANAGER",
                    "Impossible de supprimer le dernier chef de projet");

        jdbc.update("delete from project_member where project_id = ? and user_id = ?",
                projectId, target.getId());
        audit.log("PROJECT_MEMBER_REMOVED", "project", projectId,
                "{\"userId\":\"" + target.getId() + "\"}");
    }

    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• FOLDER TEMPLATES / REPAIR â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /**
     * Clones folder templates for a project (flat, parent_id null): org-specific if any
     * exist, else global (org_id is null); if the table is somehow empty, a hardcoded
     * 7-folder fallback is used so a project never ends up with zero folders.
     * Returns the number of folders inserted.
     */
    private int cloneTemplates(UUID projectId, UUID orgId) {
        List<Map<String, Object>> templates = jdbc.queryForList("""
                select code, name, sort_order
                from doc_folder_template
                where org_id = ?
                order by sort_order, code
                """, orgId);
        if (templates.isEmpty()) {
            templates = jdbc.queryForList("""
                    select code, name, sort_order
                    from doc_folder_template
                    where org_id is null
                    order by sort_order, code
                    """);
        }

        if (templates.isEmpty()) {
            // hard fallback: never let a project exist with zero folders
            for (String[] d : DEFAULT_TEMPLATES) {
                insertFolder(projectId, d[0], d[1], Integer.parseInt(d[2]));
            }
            return DEFAULT_TEMPLATES.size();
        }

        for (Map<String, Object> t : templates) {
            int sort = t.get("sort_order") == null ? 0 : ((Number) t.get("sort_order")).intValue();
            insertFolder(projectId, (String) t.get("code"), (String) t.get("name"), sort);
        }
        return templates.size();
    }

    private void insertFolder(UUID projectId, String code, String name, int sortOrder) {
        jdbc.update("""
                insert into doc_folder (project_id, code, name, sort_order)
                values (?, ?, ?, ?)
                """, projectId, code, name, sortOrder);
    }

    private int folderCount(UUID projectId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from doc_folder where project_id = ?", Integer.class, projectId);
        return n == null ? 0 : n;
    }

    /**
     * Idempotent repair endpoint: if the project currently has zero folders (e.g. created
     * before templates existed), clone them now.
     */
    @Transactional
    public void ensureFolders(Authentication auth, UUID projectId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        requireReadable(auth, me, projectId);
        repairFoldersIfEmpty(projectId, me.getOrgId());
    }

    /**
     * If the project has zero folders, clone templates. Access is assumed already checked
     * (used both by the endpoint and by the auto-repair at the end of get()).
     */
    private void repairFoldersIfEmpty(UUID projectId, UUID orgId) {
        if (folderCount(projectId) == 0) {
            int inserted = cloneTemplates(projectId, orgId);
            audit.log("PROJECT_FOLDERS_ENSURED", "project", projectId,
                    "{\"folders\":" + inserted + "}");
        }
        // folders created before V15 have no ACL rows yet — seed them (idempotent)
        folderAccess.seedDefaults(projectId);
    }
    // â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• HELPERS â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•

    /** True if the user can see all org projects: owner, or holds PROJECT_MANAGE / PROJECT_READ. */
    private boolean companyWide(Authentication auth, AppUser me) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return true;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "PERM_PROJECT_MANAGE".equals(a.getAuthority())
                        || "PERM_PROJECT_READ".equals(a.getAuthority()));
    }

    /** Owner/admin OR member of the project, else PROJECT_NOT_FOUND (acts as 404/forbidden). */
    private void requireReadable(Authentication auth, AppUser me, UUID projectId) {
        if (!projectExists(projectId, me.getOrgId()))
            throw new BusinessRuleException("PROJECT_NOT_FOUND", "Projet introuvable");
        if (companyWide(auth, me)) return;
        boolean isMember = !jdbc.queryForList(
                "select 1 from project_member where project_id = ? and user_id = ?",
                UUID.class, projectId, me.getId()).isEmpty();
        if (!isMember)
            throw new BusinessRuleException("PROJECT_NOT_FOUND", "Projet introuvable");
    }

    /** Owner/admin OR project ADMIN/MANAGER OR holds PROJECT_ASSIGN/PROJECT_WRITE, else FORBIDDEN. */
    private void requireManageable(Authentication auth, AppUser me, UUID projectId) {
        if (!projectExists(projectId, me.getOrgId()))
            throw new BusinessRuleException("PROJECT_NOT_FOUND", "Projet introuvable");
        if (companyWide(auth, me)) return;
        boolean authorized = auth.getAuthorities().stream()
                .anyMatch(a -> "PERM_PROJECT_ASSIGN".equals(a.getAuthority())
                        || "PERM_PROJECT_WRITE".equals(a.getAuthority()));
        if (!authorized) {
            List<UUID> mgr = jdbc.queryForList("""
                    select 1 from project_member
                    where project_id = ? and user_id = ? and project_role in ('ADMIN','MANAGER')
                    """, UUID.class, projectId, me.getId());
            authorized = !mgr.isEmpty();
        }
        if (!authorized)
            throw new BusinessRuleException("FORBIDDEN", "Action non autorisÃ©e sur ce projet");
    }

    private boolean projectExists(UUID projectId, UUID orgId) {
        return !jdbc.queryForList(
                "select id from project where id = ? and org_id = ?", UUID.class, projectId, orgId)
                .isEmpty();
    }

    private int countLeaders(UUID projectId) {
        Integer n = jdbc.queryForObject("""
                select count(*) from project_member
                where project_id = ? and project_role in ('ADMIN','MANAGER')
                """, Integer.class, projectId);
        return n == null ? 0 : n;
    }

    private AppUser activeUserInOrg(UUID userId, UUID orgId) {
        if (userId == null)
            throw new BadRequestException("USER_NOT_FOUND", "Utilisateur introuvable");
        AppUser target = users.findById(userId)
                .orElseThrow(() -> new BadRequestException("USER_NOT_FOUND", "Utilisateur introuvable"));
        if (!orgId.equals(target.getOrgId()) || !Boolean.TRUE.equals(target.getIsActive()))
            throw new BadRequestException("USER_NOT_FOUND", "Utilisateur introuvable");
        return target;
    }

    private MemberResponse memberRow(UUID projectId, UUID userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select pm.user_id, u.full_name, u.email,
                       pm.project_role, pm.can_upload, pm.created_at
                from project_member pm
                join app_user u on u.id = pm.user_id
                where pm.project_id = ? and pm.user_id = ?
                """, projectId, userId);
        if (rows.isEmpty())
            throw new BusinessRuleException("MEMBER_NOT_FOUND", "Membre introuvable");
        return toMemberResponse(rows.get(0));
    }

    private static ProjectResponse toResponse(Map<String, Object> row) {
        return new ProjectResponse(
                (UUID) row.get("id"),
                (String) row.get("code"),
                (String) row.get("name"),
                (String) row.get("status"),
                (String) row.get("client_name"),
                (String) row.get("location"),
                (String) row.get("governorate"),
                toLocalDate(row.get("start_date")),
                toLocalDate(row.get("end_date")),
                (String) row.get("project_type"),
                (String) row.get("description"),
                toBigDecimal(row.get("contract_value")),
                (String) row.get("currency"),
                row.get("member_count") == null ? null : ((Number) row.get("member_count")).intValue(),
                toInstant(row.get("created_at")));
    }

    private static FolderResponse toFolderResponse(Map<String, Object> row) {
        return new FolderResponse(
                (UUID) row.get("id"),
                (UUID) row.get("project_id"),
                (UUID) row.get("parent_id"),
                (String) row.get("code"),
                (String) row.get("name"),
                row.get("sort_order") == null ? null : ((Number) row.get("sort_order")).intValue(),
                toInstant(row.get("created_at")));
    }

    private static MemberResponse toMemberResponse(Map<String, Object> row) {
        return new MemberResponse(
                (UUID) row.get("user_id"),
                (String) row.get("full_name"),
                (String) row.get("email"),
                (String) row.get("project_role"),
                Boolean.TRUE.equals(row.get("can_upload")),
                toInstant(row.get("created_at")));
    }

    private static Instant toInstant(Object v) {
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }

    private static LocalDate toLocalDate(Object v) {
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return null;
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private static String blankToNull(String s) { return isBlank(s) ? null : s.trim(); }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
