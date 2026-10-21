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
                                 Instant resolvedAt, UUID resolvedById,
                                 UUID documentId) {}

    private static final String SELECT_SQL = """
            select t.id, t.title, t.description, t.status, t.priority,
                   t.project_id, p.name as project_name,
                   t.assigned_to, au.full_name as assigned_to_name,
                   t.start_at, t.due_at,
                   t.created_at, t.created_by, cu.full_name as created_by_name,
                   t.resolved_at, t.resolved_by,
                   t.document_id
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
                                 LocalDate startAt, LocalDate dueAt, UUID documentId) {
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
                                    project_id, assigned_to, start_at, due_at, created_by, document_id)
                values (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, me.getOrgId(), title.trim(), description,
                prio, projectId, assignedToId, startAt, dueAt, me.getId(), documentId);

        audit.log("TICKET_CREATED", "ticket", id,
                "{\"title\":\"" + jsonEscape(title.trim()) + "\"}");
        return get(auth, id);
    }

    // ══════════════════ 4. PARTIAL UPDATE ══════════════════

    @Transactional
    public TicketResponse update(Authentication auth, UUID id, String title, String description,
                                 UUID projectId, UUID assignedToId, String priority,
                                 LocalDate startAt, LocalDate dueAt, UUID documentId) {
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
        if (documentId != null)   { sql.append(", document_id = ?");  args.add(documentId); }
        sql.append(" where id = ? and org_id = ?");
        args.add(id);
        args.add(me.getOrgId());
        jdbc.update(sql.toString(), args.toArray());

        audit.log("TICKET_UPDATED", "ticket", id, null);
        return get(auth, id);
    }

    // ══════════════════ 5. STATUS TRANSITIONS (workflow) ══════════════════

    /**
     * Move a ticket along the transmittal workflow
     * (DRAFT → IN_REVIEW → APPROVED → IFC → AS_BUILT). The target must be an
     * allowed transition from the current status.
     */
    @Transactional
    public TicketResponse setStatus(Authentication auth, UUID id, String targetCode) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);

        TicketStatus current = TicketStatus.from((String) t.get("status"));
        TicketStatus target;
        try {
            target = TicketStatus.from(targetCode);
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("INVALID_STATUS", "Statut inconnu : " + targetCode);
        }

        if (!current.allowedTransitions().contains(target))
            throw new BusinessRuleException("INVALID_STATUS",
                    "Transition non autorisée : " + current + " → " + target.name());

        boolean terminal = (target == TicketStatus.AS_BUILT);
        if (terminal) {
            jdbc.update("""
                    update ticket set status = ?, resolved_at = now(), resolved_by = ?,
                                      updated_at = now()
                    where id = ? and org_id = ?
                    """, target.name(), me.getId(), id, me.getOrgId());
        } else {
            jdbc.update("""
                    update ticket set status = ?, updated_at = now()
                    where id = ? and org_id = ?
                    """, target.name(), id, me.getOrgId());
        }
        audit.log("TICKET_STATUS_CHANGED", "ticket", id,
                "{\"from\":\"" + current + "\",\"to\":\"" + target.name() + "\"}");
        return get(auth, id);
    }

    /** Possible next statuses for the UI (empty when terminal). */
    public List<String> availableTransitions(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        return TicketStatus.from((String) t.get("status"))
                .allowedTransitions().stream().map(Enum::name).sorted().toList();
    }

    // ══════════════════ 5b. SIMPLE STATUS TRANSITIONS ══════════════════

    @Transactional
    public void start(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        String current = (String) t.get("status");
        if (!"OUVERT".equals(current))
            throw new BusinessRuleException("INVALID_STATUS", "Transition non autorisée : " + current + " → OUVERT");
        jdbc.update("""
                update ticket set status = 'EN_COURS', updated_at = now()
                where id = ? and org_id = ?
                """, id, me.getOrgId());
        audit.log("TICKET_STATUS_CHANGED", "ticket", id, "{\"from\":\"" + current + "\",\"to\":\"EN_COURS\"}");
    }

    @Transactional
    public void wait_(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        String current = (String) t.get("status");
        if (!"EN_COURS".equals(current))
            throw new BusinessRuleException("INVALID_STATUS", "Transition non autorisée : " + current + " → EN_ATTENTE");
        jdbc.update("""
                update ticket set status = 'EN_ATTENTE', updated_at = now()
                where id = ? and org_id = ?
                """, id, me.getOrgId());
        audit.log("TICKET_STATUS_CHANGED", "ticket", id, "{\"from\":\"" + current + "\",\"to\":\"EN_ATTENTE\"}");
    }

    @Transactional
    public void resume(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        String current = (String) t.get("status");
        if (!"EN_ATTENTE".equals(current))
            throw new BusinessRuleException("INVALID_STATUS", "Transition non autorisée : " + current + " → EN_COURS");
        jdbc.update("""
                update ticket set status = 'EN_COURS', updated_at = now()
                where id = ? and org_id = ?
                """, id, me.getOrgId());
        audit.log("TICKET_STATUS_CHANGED", "ticket", id, "{\"from\":\"" + current + "\",\"to\":\"EN_COURS\"}");
    }

    @Transactional
    public void close(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        Map<String, Object> t = requireTicket(me, id);
        jdbc.update("""
                update ticket set status = 'RESOLU', resolved_at = now(), resolved_by = ?, updated_at = now()
                where id = ? and org_id = ?
                """, me.getId(), id, me.getOrgId());
        audit.log("TICKET_CLOSED", "ticket", id, null);
    }

    // ══════════════════ 6. LINKED DOCUMENTS ══════════════════

    /** Documents linked to this ticket (via document.ticket_id). */
    public List<Map<String, Object>> documentsOf(Authentication auth, UUID ticketId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        requireTicket(me, ticketId);
        return jdbc.queryForList("""
                select d.id, d.reference, d.title, d.doc_type, d.current_rev, d.status,
                       d.created_at, d.created_by, u.full_name as created_by_name
                from document d
                left join app_user u on u.id = d.created_by
                where d.ticket_id = ? and d.org_id = ? and d.deleted_at is null
                order by d.reference
                """, ticketId, me.getOrgId());
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
                toLocalDate(r.get("start_at")),
                toLocalDate(r.get("due_at")),
                toInstant(r.get("created_at")),
                (UUID) r.get("created_by"),
                (String) r.get("created_by_name"),
                toInstant(r.get("resolved_at")),
                (UUID) r.get("resolved_by"),
                (UUID) r.get("document_id"));
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDate ld) return ld;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        if (v instanceof java.time.LocalDateTime ldt) return ldt.toLocalDate();
        if (v instanceof String s && !s.isBlank()) return LocalDate.parse(s.substring(0, 10));
        return null;
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

