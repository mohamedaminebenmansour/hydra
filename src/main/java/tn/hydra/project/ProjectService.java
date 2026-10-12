package tn.hydra.project;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ProjectService {

    private static final Set<String> STATUSES = Set.of("ACTIF", "CLOTURE");

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public ProjectService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.audit = audit;
    }

    // ══════════════════ RESPONSE SHAPE ══════════════════

    public record ProjectResponse(UUID id, String code, String name, String status,
                                  Instant createdAt) {}

    public record ProjectOption(UUID id, String name) {}

    public record CreateProjectRequest(String name, String code) {}
    public record UpdateProjectRequest(String name, String code, String status) {}

    // ══════════════════ 1. LIST PROJECTS ══════════════════

    public List<ProjectResponse> list(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        return jdbc.queryForList("""
                select id, code, name, status, created_at
                from project
                where org_id = ?
                order by name
                """, me.getOrgId())
                .stream().map(ProjectService::toResponse).toList();
    }

    public List<ProjectOption> projectOptions(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        return jdbc.queryForList("""
                select id, name
                from project
                where org_id = ? and status = 'ACTIF'
                order by name
                """, me.getOrgId())
                .stream().map(r -> new ProjectOption((UUID) r.get("id"), (String) r.get("name")))
                .toList();
    }

    // ══════════════════ 2. GET ONE PROJECT (null → 404) ══════════════════

    public ProjectResponse get(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, code, name, status, created_at
                from project
                where id = ? and org_id = ?
                """, id, me.getOrgId());
        return rows.isEmpty() ? null : toResponse(rows.get(0));
    }

    // ══════════════════ 3. CREATE PROJECT ══════════════════

    @Transactional
    public ProjectResponse create(Authentication auth, String name, String code) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (isBlank(name))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le nom est obligatoire");

        UUID id = jdbc.queryForObject("""
                insert into project (org_id, code, name, status, created_by)
                values (?, ?, ?, 'ACTIF', ?) returning id
                """, UUID.class, me.getOrgId(), blankToNull(code), name.trim(), me.getId());

        audit.log("PROJECT_CREATED", "project", id,
                "{\"name\":\"" + jsonEscape(name.trim()) + "\"}");
        return get(auth, id);
    }

    // ══════════════════ 4. UPDATE PROJECT ══════════════════

    @Transactional
    public ProjectResponse update(Authentication auth, UUID id,
                                  String name, String code, String status) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (isBlank(name))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le nom est obligatoire");
        if (status == null || !STATUSES.contains(status))
            throw new BusinessRuleException("INVALID_STATUS", "Statut invalide");
        List<UUID> found = jdbc.queryForList(
                "select id from project where id = ? and org_id = ?", UUID.class, id, me.getOrgId());
        if (found.isEmpty())
            throw new BusinessRuleException("PROJECT_NOT_FOUND", "Projet introuvable");

        jdbc.update("update project set name = ?, code = ?, status = ? where id = ?",
                name.trim(), blankToNull(code), status, id);

        audit.log("PROJECT_UPDATED", "project", id,
                "{\"name\":\"" + jsonEscape(name.trim()) + "\",\"status\":\"" + status + "\"}");
        return get(auth, id);
    }

    // ══════════════════ HELPERS ══════════════════

    private static ProjectResponse toResponse(Map<String, Object> row) {
        return new ProjectResponse(
                (UUID) row.get("id"),
                (String) row.get("code"),
                (String) row.get("name"),
                (String) row.get("status"),
                toInstant(row.get("created_at")));
    }

    private static Instant toInstant(Object v) {
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }

    private static String blankToNull(String s) { return isBlank(s) ? null : s.trim(); }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}