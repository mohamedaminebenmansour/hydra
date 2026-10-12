package tn.hydra.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@Service
public class TicketService {

    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public TicketService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.audit = audit;
    }

    // ══════════════════ RESPONSE SHAPE ══════════════════

    public record TicketResponse(UUID id, String title, String description, String status,
                                 String priority, UUID projectId, String projectName,
                                 UUID assignedToId, String assignedToName,
                                 LocalDate startAt, LocalDate dueAt,
                                 Instant createdAt, UUID createdById, String createdByName,
                                 Instant resolvedAt, UUID resolvedById) {}

    private static final String SELECT_SQL = """
            select t.id, t.title, t.description, t.status, t.priority,
                   t.project_id, p.name as project_name,
                   t.assigned_to, au.full_name as assigned_to_name,
                   t.start_at, t.due_at,
                   t.created_at, t.created_by, cu.full_name as created_by_name,
                   t.resolved_at, t.resolved_by
            from ticket t
            left join project p on p.id = t.project_id
            left join app_user au on au.id = t.assigned_to
            left join app_user cu on cu.id = t.created_by
            """;

    // ══════════════════ 1. LIST WITH FILTERS ══════════════════

    public List<TicketResponse> list(Authentication auth, UUID projectId, UUID assignedTo,
                                     String status, String priority, Boolean mine) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();

        StringBuilder sql = new StringBuilder(SELECT_SQL).append(" where t.org_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());

        if (projectId != null) { sql.append(" and t.project_id = ?"); args.add(projectId); }
        if (assignedTo != null) { sql.append(" and t.assigned_to = ?"); args.add(assignedTo); }
        if (status != null && !status.isBlank()) { sql.append(" and t.status = ?"); args.add(status.trim()); }
        if (priority != null && !priority.isBlank()) { sql.append(" and t.priority = ?"); args.add(priority.trim()); }
        if (Boolean.TRUE.equals(mine)) { sql.append(" and t.assigned_to = ?"); args.add(me.getId()); }
        sql.append(" order by t.created_at desc");

        return jdbc.queryForList(sql.toString(), args.toArray()).stream()
                .map(TicketService::toResponse).toList();
    }

    // ══════════════════ 2. GET ONE (null → 404) ══════════════════

    public TicketResponse get(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_SQL + " where t.id = ? and t.org_id = ?", id, me.getOrgId());
        return rows.isEmpty() ? null : toResponse(rows.get(0));
    }

    // ══════════════════ 3. CREATE TICKET ══════════════════

    @Transactional
    public TicketResponse create(Authentication auth, String title, String description,
                                 UUID projectId, UUID assignedToId, String priority,
                                 LocalDate startAt, LocalDate dueAt) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (isBlank(title))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le titre est obligatoire");
        if (projectId == null)
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le projet est obligatoire");
        requireProject(me, projectId, false);                 // must exist in org and be ACTIF
        if (assignedToId != null) requireUserInOrg(me, assignedToId);
        String prio = normalizePriority(priority);

        UUID id = jdbc.queryForObject("""
                insert into ticket (org_id, title, description, status, priority,
                                    project_id, assigned_to, start_at, due_at, created_by)
                values (?, ?, ?, 'OUVERT', ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, me.getOrgId(), title.trim(), description,
                prio, projectId, assignedToId, startAt, dueAt, me.getId());

        audit.log("TICKET_CREATED", "ticket", id,
                "{\"title\":\"" + jsonEscape(title.trim()) + "\"}");
        return get(auth, id);
    }

    // ══════════════════ 4. PARTIAL UPDATE ══════════════════

    @Transactional
    public TicketResponse update(Authentication auth, UUID id, String title, String description,
                                 UUID projectId, UUID assignedToId, String priority,
                                 LocalDate startAt, LocalDate dueAt) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> current = requireTicket(me, id);

        if (title != null && isBlank(title))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le titre est obligatoire");
        if (projectId != null && !projectId.equals(current.get("project_id")))
            requireProject(me, projectId, false);
        if (assignedToId != null && !assignedToId.equals(current.get("assigned_to")))
            requireUserInOrg(me, assignedToId);
        if (priority != null && !PRIORITIES.contains(priority))
            throw new BusinessRuleException("INVALID_PRIORITY", "Priorité invalide");

        // null body field = leave unchanged (partial update)
        StringBuilder sql = new StringBuilder("update ticket set updated_at = now()");
        List<Object> args = new ArrayList<>();
        if (title != null)        { sql.append(", title = ?");        args.add(title.trim()); }
        if (description != null)  { sql.append(", description = ?");  args.add(description); }
        if (projectId != null)    { sql.append(", project_id = ?");   args.add(projectId); }
        if (assignedToId != null) { sql.append(", assigned_to = ?");  args.add(assignedToId); }
        if (priority != null)     { sql.append(", priority = ?");     args.add(priority); }
        if (startAt != null)      { sql.append(", start_at = ?");     args.add(startAt); }
        if (dueAt != null)        { sql.append(", due_at = ?");       args.add(dueAt); }
        sql.append(" where id = ? and org_id = ?");
        args.add(id);
        args.add(me.getOrgId());
        jdbc.update(sql.toString(), args.toArray());

        audit.log("TICKET_UPDATED", "ticket", id, null);
        return get(auth, id);
    }

    // ══════════════════ 5. STATUS TRANSITIONS ══════════════════

    public void start(Authentication auth, UUID id) {
        transition(auth, id, Set.of("OUVERT"), "EN_COURS", true, "TICKET_STATUS_CHANGED");
    }

    public void wait_(Authentication auth, UUID id) {
        transition(auth, id, Set.of("EN_COURS"), "EN_ATTENTE", true, "TICKET_STATUS_CHANGED");
    }

    public void resume(Authentication auth, UUID id) {
        transition(auth, id, Set.of("EN_ATTENTE"), "EN_COURS", true, "TICKET_STATUS_CHANGED");
    }

    public void close(Authentication auth, UUID id) {
        transition(auth, id, Set.of("OUVERT", "EN_COURS", "EN_ATTENTE"), "RESOLU",
                false, "TICKET_CLOSED");
    }

    /**
     * Shared transition engine. {@code assigneeOrWriter} = only the assignee or a
     * PERM_TICKET_WRITE holder may move the ticket; for {@code close} the endpoint
     * itself already required PERM_TICKET_WRITE, so nobody is excluded.
     */
    @Transactional
    public void transition(Authentication auth, UUID id, Set<String> fromStatuses, String toStatus,
                           boolean assigneeOrWriter, String auditAction) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);

        if (!fromStatuses.contains(t.get("status")))
            throw new BusinessRuleException("INVALID_STATUS",
                    "Transition non autorisée : " + t.get("status") + " → " + toStatus);
        if (assigneeOrWriter && !isAssigneeOrWriter(auth, me, t))
            throw new BusinessRuleException("NOT_ASSIGNED",
                    "Seul l'assigné ou un détenteur de PERM_TICKET_WRITE peut déplacer ce ticket");

        if ("RESOLU".equals(toStatus)) {
            jdbc.update("""
                    update ticket set status = ?, resolved_at = now(), resolved_by = ?,
                                      updated_at = now()
                    where id = ? and org_id = ?
                    """, toStatus, me.getId(), id, me.getOrgId());
        } else {
            jdbc.update("""
                    update ticket set status = ?, updated_at = now()
                    where id = ? and org_id = ?
                    """, toStatus, id, me.getOrgId());
        }
        audit.log(auditAction, "ticket", id,
                "{\"from\":\"" + t.get("status") + "\",\"to\":\"" + toStatus + "\"}");
    }

    // ══════════════════ VALIDATION HELPERS ══════════════════

    /** Same-org ticket row or BusinessRuleException(TICKET_NOT_FOUND). */
    private Map<String, Object> requireTicket(AppUser me, UUID id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, title, status, project_id, assigned_to from ticket where id = ? and org_id = ?",
                id, me.getOrgId());
        if (rows.isEmpty())
            throw new BusinessRuleException("TICKET_NOT_FOUND", "Ticket introuvable");
        return rows.get(0);
    }

    private void requireProject(AppUser me, UUID projectId, boolean allowClosed) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select status from project where id = ? and org_id = ?", projectId, me.getOrgId());
        if (rows.isEmpty())
            throw new BusinessRuleException("PROJECT_NOT_FOUND", "Projet introuvable");
        if (!allowClosed && !"ACTIF".equals(rows.get(0).get("status")))
            throw new BusinessRuleException("PROJECT_CLOSED", "Ce projet est clôturé");
    }

    private void requireUserInOrg(AppUser me, UUID userId) {
        List<UUID> found = jdbc.queryForList(
                "select id from app_user where id = ? and org_id = ?", UUID.class, userId, me.getOrgId());
        if (found.isEmpty())
            throw new BusinessRuleException("USER_NOT_FOUND", "Utilisateur introuvable");
    }

    private String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) return "MEDIUM";
        if (!PRIORITIES.contains(priority))
            throw new BusinessRuleException("INVALID_PRIORITY", "Priorité invalide");
        return priority;
    }

    private boolean isAssigneeOrWriter(Authentication auth, AppUser me, Map<String, Object> ticket) {
        if (me.getId().equals(ticket.get("assigned_to"))) return true;
        return auth.getAuthorities().stream()
                .anyMatch(a -> "PERM_TICKET_WRITE".equals(a.getAuthority()));
    }

    // ══════════════════ HELPERS ══════════════════

    private static TicketResponse toResponse(Map<String, Object> r) {
        return new TicketResponse(
                (UUID) r.get("id"),
                (String) r.get("title"),
                (String) r.get("description"),
                (String) r.get("status"),
                (String) r.get("priority"),
                (UUID) r.get("project_id"),
                (String) r.get("project_name"),
                (UUID) r.get("assigned_to"),
                (String) r.get("assigned_to_name"),
                (LocalDate) r.get("start_at"),
                (LocalDate) r.get("due_at"),
                toInstant(r.get("created_at")),
                (UUID) r.get("created_by"),
                (String) r.get("created_by_name"),
                toInstant(r.get("resolved_at")),
                (UUID) r.get("resolved_by"));
    }

    private static Instant toInstant(Object v) {
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

}
