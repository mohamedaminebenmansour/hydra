package tn.hydra.process;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Process engine service — single-file, pure JdbcTemplate, no JPA flush risk.
 *
 * Permission model (clear contract used everywhere):
 *  - Org OWNER:          may do everything in their org.
 *  - Project member:     may start a process on that project.
 *  - Step assignee:      may complete / reject their own PENDING step
 *                        (assignee_user_id = me, OR my project_role = assignee_role).
 *  - Complete / reject fallback: if assignee_user_id is null, any project member whose
 *    project_role matches the step's assignee_role may act.
 */
@Service
public class ProcessService {

    // ═══════════════════════════════════════════════════════════════
    // REQUEST / RESPONSE RECORDS
    // ═══════════════════════════════════════════════════════════════

    public record StartProcessCommand(UUID projectId, String templateKey, String subjectType, UUID subjectId) {}

    public record CompleteStepRequest(String comment, List<UUID> documentIds) {}

    public record RejectStepRequest(String comment) {}

    public record CancelRequest(String reason) {}

    /** Returned after start — 201 */
    public record StartProcessResponse(UUID id, String status, UUID projectId,
                                       String templateKey, String subjectType, UUID subjectId) {}

    /** One line on the timeline steps list. */
    public record StepView(
            UUID stepInstanceId,
            int stepOrder,
            String stepKey,
            String name,
            String status,
            String assigneeRole,
            UUID assigneeUserId,
            String assigneeName,
            boolean requiresAttachment,
            Instant startedAt,
            Instant completedAt,
            String comment,
            List<UUID> documentIds) {}

    /** Full traceability view. */
    public record ProcessTimeline(
            UUID instanceId,
            UUID projectId,
            String projectName,
            String status,
            String templateKey,
            String templateName,
            String subjectType,
            UUID subjectId,
            Instant createdAt,
            UUID createdBy,
            String createdByName,
            int currentStepOrder,
            List<StepView> steps) {}

    /** Minimal result returned after complete / reject. */
    public record StepResult(UUID stepInstanceId, String newStatus) {}

    /** Cross-project task for the "My Tasks" screen. */
    public record MyTaskSummary(
            UUID stepInstanceId,
            UUID instanceId,
            UUID projectId,
            String projectName,
            String templateKey,
            String templateName,
            String subjectType,
            UUID subjectId,
            String stepKey,
            String stepName,
            String status,
            String assigneeRole,
            UUID assigneeUserId,
            String assigneeName,
            boolean requiresAttachment,
            Instant startedAt) {}

    /** Summary row for the project chantier screen. */
    public record ProcessInstanceSummary(
            UUID id,
            String templateKey,
            String templateName,
            String status,
            String subjectType,
            UUID subjectId,
            Instant createdAt,
            int currentStepOrder,
            String currentStepName) {}

    /** Template option for dropdowns / project config. */
    public record TemplateOption(UUID id, String key, String name, boolean active) {}

    /** Full template detail (for admin screens). */
    public record TemplateDetail(
            UUID id, String key, String name, UUID orgId, boolean active,
            Instant createdAt, List<TemplateStepView> steps) {}

    public record TemplateStepView(
            UUID id, int stepOrder, String stepKey, String name,
            String assigneeRole, boolean requiresAttachment) {}

    // Template management request records
    public record CreateTemplateRequest(String key, String name) {}

    public record UpdateTemplateRequest(String name, Boolean active) {}

    public record CreateStepRequest(int stepOrder, String stepKey, String name,
                                    String assigneeRole, boolean requiresAttachment) {}

    public record UpdateStepRequest(String name, String assigneeRole,
                                    boolean requiresAttachment, Integer stepOrder) {}

    // Project template assignment
    public record AssignTemplateRequest(String templateKey, boolean enabled,
                                        String defaultForSubjectType) {}

    public record ProjectTemplateConfig(
            UUID templateId, String templateKey, String templateName,
            boolean enabled, String defaultForSubjectType) {}

    // ═══════════════════════════════════════════════════════════════
    // DEPENDENCIES
    // ═══════════════════════════════════════════════════════════════

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public ProcessService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    // ═══════════════════════════════════════════════════════════════
    // AUTH HELPERS  — all use plain JDBC to avoid Hibernate autoflush
    // ═══════════════════════════════════════════════════════════════

    /** Returns the authenticated user from app_user via plain JDBC. */
    private AppUser currentUser(Authentication auth) {
        String email = auth.getName();
        return users.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Unknown user: " + email));
    }

    private UUID currentUserId(Authentication auth) {
        return currentUser(auth).getId();
    }

    /**
     * Enforces: caller is org owner OR a member of the given project.
     * Throws ResourceNotFoundException (→ 404) so we never reveal project existence
     * to non-members.
     */
    private void requireProjectAccess(AppUser me, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        Integer cnt = jdbc.queryForObject(
                "select count(*) from project_member where project_id = ? and user_id = ?",
                Integer.class, projectId, me.getId());
        if (cnt == null || cnt == 0) {
            throw new ResourceNotFoundException("PROJECT_NOT_FOUND",
                    "Projet introuvable ou accès refusé");
        }
    }

    /**
     * Enforces: caller is org owner OR is_owner flag.
     * Used for template admin operations.
     */
    private void requireOwner(AppUser me) {
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            throw new BusinessRuleException("OWNER_ONLY",
                    "Seul le gérant peut effectuer cette action");
        }
    }

    /**
     * Can the caller complete or reject the given step?
     *  1. Org owner → always yes.
     *  2. assignee_user_id = me → yes.
     *  3. assignee_user_id is null AND my project_role = step's assignee_role → yes.
     */
    private void requireStepAccess(AppUser me, UUID stepInstanceId,
                                   UUID assigneeUserId, String assigneeRole, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        if (Objects.equals(me.getId(), assigneeUserId)) return;
        // role-based fallback
        List<UUID> roleMatch = jdbc.queryForList(
                """
                select pm.user_id from project_member pm
                where pm.project_id = ? and pm.user_id = ? and pm.project_role = ?
                limit 1
                """,
                UUID.class, projectId, me.getId(), assigneeRole);
        if (!roleMatch.isEmpty()) return;
        throw new BusinessRuleException("NOT_ALLOWED",
                "Vous n'êtes pas autorisé à agir sur cette étape");
    }

    /**
     * Tries to resolve a specific user for the given role on the project.
     * Returns null if nobody found (any user with that role may still act via role-check).
     * Picks the first alphabetically by email for determinism.
     */
    private UUID resolveAssigneeByRole(UUID projectId, String assigneeRole) {
        List<UUID> rows = jdbc.queryForList(
                """
                select pm.user_id from project_member pm
                join app_user u on u.id = pm.user_id
                where pm.project_id = ? and pm.project_role = ?
                order by u.email asc
                limit 1
                """,
                UUID.class, projectId, assigneeRole);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.4  START PROCESS
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public StartProcessResponse start(Authentication auth, StartProcessCommand cmd) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        if (orgId == null) throw new IllegalStateException("User has no org");

        // (1) Project membership
        requireProjectAccess(me, cmd.projectId());

        // (2) Resolve active template — prefer org-scoped, fall back to system (org_id IS NULL)
        List<Map<String, Object>> tmplRows = jdbc.queryForList(
                """
                select id, key, name from process_template
                where key = ? and active = true
                  and (org_id = ? or org_id is null)
                order by (org_id = ?) desc nulls last
                limit 1
                """,
                cmd.templateKey(), orgId, orgId);
        if (tmplRows.isEmpty()) {
            throw new BadRequestException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou inactif: " + cmd.templateKey());
        }
        UUID templateId = (UUID) tmplRows.get(0).get("id");
        String templateName = (String) tmplRows.get(0).get("name");

        // (3) Project enablement check
        Integer ptCount = jdbc.queryForObject(
                "select count(*) from project_process_template where project_id = ?",
                Integer.class, cmd.projectId());
        if (ptCount != null && ptCount > 0) {
            Integer enabledCount = jdbc.queryForObject(
                    """
                    select count(*) from project_process_template
                    where project_id = ? and template_id = ? and enabled = true
                    """,
                    Integer.class, cmd.projectId(), templateId);
            if (enabledCount == null || enabledCount == 0) {
                throw new BusinessRuleException("TEMPLATE_NOT_ENABLED_ON_PROJECT",
                        "Ce template n'est pas activé sur ce projet");
            }
        }

        // (4) Guard: only one RUNNING instance per (org, subjectType, subjectId)
        Integer running = jdbc.queryForObject(
                """
                select count(*) from process_instance
                where org_id = ? and subject_type = ? and subject_id = ? and status = 'RUNNING'
                """,
                Integer.class, orgId, cmd.subjectType(), cmd.subjectId());
        if (running != null && running > 0) {
            throw new BusinessRuleException("PROCESS_ALREADY_RUNNING",
                    "Une instance RUNNING existe déjà pour ce sujet");
        }

        // (5) Insert process_instance
        UUID instanceId = UUID.randomUUID();
        jdbc.update(
                """
                insert into process_instance (id, org_id, project_id, template_id,
                                             subject_type, subject_id, status,
                                             current_step_order, created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'RUNNING', 1, ?, now(), now())
                """,
                instanceId, orgId, cmd.projectId(), templateId,
                cmd.subjectType(), cmd.subjectId(), me.getId());

        // (6) Load first step only
        List<Map<String, Object>> firstSteps = jdbc.queryForList(
                """
                select step_order, step_key, name, assignee_role, requires_attachment
                from process_template_step
                where template_id = ?
                order by step_order asc
                limit 1
                """,
                templateId);

        if (!firstSteps.isEmpty()) {
            Map<String, Object> first = firstSteps.get(0);
            int stepOrder = ((Number) first.get("step_order")).intValue();
            String stepKey = (String) first.get("step_key");
            String stepName = (String) first.get("name");
            String assigneeRole = (String) first.get("assignee_role");
            boolean requiresAttachment = Boolean.TRUE.equals(first.get("requires_attachment"));

            // Resolve assignee — null is OK; any user with role may still act
            UUID assigneeUserId = resolveAssigneeByRole(cmd.projectId(), assigneeRole);

            // (7) Insert first PENDING step_instance
            jdbc.update(
                    """
                    insert into process_step_instance
                      (id, instance_id, step_order, step_key, name,
                       status, assignee_role, assignee_user_id, requires_attachment,
                       started_at, created_at)
                    values (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, now(), now())
                    """,
                    UUID.randomUUID(), instanceId, stepOrder, stepKey, stepName,
                    assigneeRole, assigneeUserId, requiresAttachment);

            audit.log("STEP_STARTED", "process_step_instance", instanceId,
                    "{\"instanceId\":\"" + instanceId + "\",\"stepKey\":\"" + jsonEscape(stepKey) +
                    "\",\"stepOrder\":" + stepOrder + "}");
        }

        // (9) Audit
        audit.log("PROCESS_STARTED", "process_instance", instanceId,
                "{\"templateKey\":\"" + jsonEscape(cmd.templateKey()) +
                "\",\"subjectType\":\"" + jsonEscape(cmd.subjectType()) +
                "\",\"subjectId\":\"" + cmd.subjectId() +
                "\",\"projectId\":\"" + cmd.projectId() + "\"}");

        return new StartProcessResponse(instanceId, "RUNNING", cmd.projectId(),
                cmd.templateKey(), cmd.subjectType(), cmd.subjectId());
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.5  COMPLETE STEP
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public StepResult completeStep(Authentication auth, UUID instanceId,
                                   UUID stepInstanceId, CompleteStepRequest req) {
        AppUser me = currentUser(auth);

        // Load step + instance in one join
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select psi.id, psi.instance_id, psi.status, psi.step_order,
                       psi.step_key, psi.assignee_user_id, psi.assignee_role,
                       psi.requires_attachment,
                       pi.template_id, pi.project_id, pi.status as instance_status
                from process_step_instance psi
                join process_instance pi on pi.id = psi.instance_id
                where psi.id = ? and psi.instance_id = ?
                """,
                stepInstanceId, instanceId);

        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND",
                    "Étape introuvable sur cette instance");
        }
        Map<String, Object> step = rows.get(0);

        if (!"RUNNING".equals(step.get("instance_status"))) {
            throw new BusinessRuleException("INSTANCE_NOT_RUNNING",
                    "L'instance de processus n'est pas en cours");
        }
        if (!"PENDING".equals(step.get("status"))) {
            throw new BusinessRuleException("STEP_NOT_PENDING",
                    "L'étape n'est pas en attente");
        }

        UUID projectId = (UUID) step.get("project_id");
        UUID assigneeUserId = (UUID) step.get("assignee_user_id");
        String assigneeRole = (String) step.get("assignee_role");
        boolean requiresAttachment = Boolean.TRUE.equals(step.get("requires_attachment"));

        // Permission
        requireStepAccess(me, stepInstanceId, assigneeUserId, assigneeRole, projectId);

        // Attachment guard
        if (requiresAttachment &&
                (req.documentIds() == null || req.documentIds().isEmpty())) {
            throw new BadRequestException("ATTACHMENT_REQUIRED",
                    "Cette étape requiert au moins un document joint");
        }

        // Mark step DONE
        jdbc.update(
                """
                update process_step_instance
                set status = 'DONE', completed_at = now(), completed_by = ?, comment = ?
                where id = ?
                """,
                me.getId(), req.comment(), stepInstanceId);

        // Link documents
        if (req.documentIds() != null) {
            for (UUID docId : req.documentIds()) {
                jdbc.update(
                        """
                        insert into process_step_document (step_instance_id, document_id)
                        values (?, ?)
                        on conflict do nothing
                        """,
                        stepInstanceId, docId);
            }
        }

        UUID templateId = (UUID) step.get("template_id");
        int currentOrder = ((Number) step.get("step_order")).intValue();
        String currentKey = (String) step.get("step_key");

        // Load next template step
        List<Map<String, Object>> nextSteps = jdbc.queryForList(
                """
                select step_order, step_key, name, assignee_role, requires_attachment
                from process_template_step
                where template_id = ? and step_order > ?
                order by step_order asc
                limit 1
                """,
                templateId, currentOrder);

        audit.log("STEP_COMPLETED", "process_step_instance", stepInstanceId,
                "{\"instanceId\":\"" + instanceId +
                "\",\"stepKey\":\"" + jsonEscape(currentKey) +
                "\",\"comment\":\"" + jsonEscape(req.comment()) + "\"}");

        if (!nextSteps.isEmpty()) {
            Map<String, Object> next = nextSteps.get(0);
            int nextOrder = ((Number) next.get("step_order")).intValue();
            String nextKey = (String) next.get("step_key");
            String nextName = (String) next.get("name");
            String nextRole = (String) next.get("assignee_role");
            boolean nextRequiresAttachment = Boolean.TRUE.equals(next.get("requires_attachment"));

            UUID nextAssignee = resolveAssigneeByRole(projectId, nextRole);

            UUID newStepId = UUID.randomUUID();
            jdbc.update(
                    """
                    insert into process_step_instance
                      (id, instance_id, step_order, step_key, name,
                       status, assignee_role, assignee_user_id, requires_attachment,
                       started_at, created_at)
                    values (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, now(), now())
                    """,
                    newStepId, instanceId, nextOrder, nextKey, nextName,
                    nextRole, nextAssignee, nextRequiresAttachment);

            jdbc.update(
                    "update process_instance set current_step_order = ?, updated_at = now() where id = ?",
                    nextOrder, instanceId);

            audit.log("STEP_STARTED", "process_step_instance", newStepId,
                    "{\"instanceId\":\"" + instanceId +
                    "\",\"stepKey\":\"" + jsonEscape(nextKey) +
                    "\",\"stepOrder\":" + nextOrder + "}");
        } else {
            // Last step completed → process COMPLETED
            jdbc.update(
                    "update process_instance set status = 'COMPLETED', updated_at = now() where id = ?",
                    instanceId);

            audit.log("PROCESS_COMPLETED", "process_instance", instanceId,
                    "{\"completedBy\":\"" + me.getId() + "\"}");

            // Hook placeholder for future caisse integration
            onPaymentProcessCompleted(instanceId);
        }

        return new StepResult(stepInstanceId, "DONE");
    }

    /**
     * Hook called when the last step of a process instance is completed.
     * Placeholder for future caisse / payment posting integration.
     * Do NOT call any external service here in v1; it is a no-op.
     */
    protected void onPaymentProcessCompleted(UUID instanceId) {
        // Future: trigger caisse entry, notify finance, etc.
        // No-op in v1.
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.6  REJECT STEP
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public StepResult rejectStep(Authentication auth, UUID instanceId,
                                 UUID stepInstanceId, RejectStepRequest req) {
        if (req.comment() == null || req.comment().isBlank()) {
            throw new BadRequestException("COMMENT_REQUIRED",
                    "Un commentaire est requis pour rejeter une étape");
        }

        AppUser me = currentUser(auth);

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select psi.id, psi.status, psi.step_key,
                       psi.assignee_user_id, psi.assignee_role,
                       pi.project_id, pi.status as instance_status
                from process_step_instance psi
                join process_instance pi on pi.id = psi.instance_id
                where psi.id = ? and psi.instance_id = ?
                """,
                stepInstanceId, instanceId);

        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND",
                    "Étape introuvable sur cette instance");
        }
        Map<String, Object> step = rows.get(0);

        if (!"RUNNING".equals(step.get("instance_status"))) {
            throw new BusinessRuleException("INSTANCE_NOT_RUNNING",
                    "L'instance de processus n'est pas en cours");
        }
        if (!"PENDING".equals(step.get("status"))) {
            throw new BusinessRuleException("STEP_NOT_PENDING",
                    "L'étape n'est pas en attente");
        }

        UUID projectId = (UUID) step.get("project_id");
        UUID assigneeUserId = (UUID) step.get("assignee_user_id");
        String assigneeRole = (String) step.get("assignee_role");
        String stepKey = (String) step.get("step_key");

        requireStepAccess(me, stepInstanceId, assigneeUserId, assigneeRole, projectId);

        jdbc.update(
                """
                update process_step_instance
                set status = 'REJECTED', completed_at = now(), completed_by = ?, comment = ?
                where id = ?
                """,
                me.getId(), req.comment(), stepInstanceId);

        jdbc.update(
                "update process_instance set status = 'REJECTED', updated_at = now() where id = ?",
                instanceId);

        audit.log("STEP_REJECTED", "process_step_instance", stepInstanceId,
                "{\"instanceId\":\"" + instanceId +
                "\",\"stepKey\":\"" + jsonEscape(stepKey) +
                "\",\"comment\":\"" + jsonEscape(req.comment()) + "\"}");

        audit.log("PROCESS_REJECTED", "process_instance", instanceId,
                "{\"rejectedBy\":\"" + me.getId() +
                "\",\"comment\":\"" + jsonEscape(req.comment()) + "\"}");

        return new StepResult(stepInstanceId, "REJECTED");
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.7  CANCEL INSTANCE
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public void cancelInstance(Authentication auth, UUID instanceId, CancelRequest req) {
        AppUser me = currentUser(auth);

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select pi.status, pi.project_id, pi.org_id
                from process_instance pi
                where pi.id = ?
                """,
                instanceId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("INSTANCE_NOT_FOUND",
                    "Instance de processus introuvable");
        }
        Map<String, Object> inst = rows.get(0);

        // Only owner or project member can cancel
        UUID projectId = (UUID) inst.get("project_id");
        requireProjectAccess(me, projectId);

        // Only RUNNING can be cancelled
        if (!"RUNNING".equals(inst.get("status"))) {
            throw new BusinessRuleException("INSTANCE_NOT_RUNNING",
                    "Seule une instance RUNNING peut être annulée");
        }

        // Set open PENDING steps → SKIPPED
        jdbc.update(
                """
                update process_step_instance
                set status = 'SKIPPED', completed_at = now(), completed_by = ?,
                    comment = 'Instance annulée'
                where instance_id = ? and status = 'PENDING'
                """,
                me.getId(), instanceId);

        // Set instance → CANCELLED
        jdbc.update(
                "update process_instance set status = 'CANCELLED', updated_at = now() where id = ?",
                instanceId);

        audit.log("PROCESS_CANCELLED", "process_instance", instanceId,
                "{\"cancelledBy\":\"" + me.getId() +
                "\",\"reason\":\"" + jsonEscape(req != null ? req.reason() : null) + "\"}");
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.9  TIMELINE
    // ═══════════════════════════════════════════════════════════════

    public ProcessTimeline getTimeline(Authentication auth, UUID instanceId) {
        currentUser(auth); // ensure authenticated

        List<Map<String, Object>> instRows = jdbc.queryForList(
                """
                select pi.id, pi.project_id, p.name as project_name,
                       pi.status, pt.key as template_key, pt.name as template_name,
                       pi.subject_type, pi.subject_id, pi.created_at,
                       pi.created_by, pi.current_step_order,
                       u.full_name as created_by_name
                from process_instance pi
                join process_template pt on pt.id = pi.template_id
                join project p on p.id = pi.project_id
                left join app_user u on u.id = pi.created_by
                where pi.id = ?
                """,
                instanceId);
        if (instRows.isEmpty()) {
            throw new ResourceNotFoundException("INSTANCE_NOT_FOUND",
                    "Instance de processus introuvable");
        }
        Map<String, Object> inst = instRows.get(0);

        List<StepView> steps = jdbc.query(
                """
                select psi.id as step_instance_id, psi.step_order, psi.step_key, psi.name,
                       psi.status, psi.assignee_role,
                       psi.assignee_user_id, u.full_name as assignee_name,
                       psi.requires_attachment, psi.started_at,
                       psi.completed_at, psi.comment
                from process_step_instance psi
                left join app_user u on u.id = psi.assignee_user_id
                where psi.instance_id = ?
                order by psi.step_order asc, psi.created_at asc
                """,
                (rs, rowNum) -> {
                    UUID stepId = (UUID) rs.getObject("step_instance_id");
                    List<UUID> docIds = jdbc.queryForList(
                            "select document_id from process_step_document where step_instance_id = ?",
                            UUID.class, stepId);
                    return new StepView(
                            stepId,
                            rs.getInt("step_order"),
                            rs.getString("step_key"),
                            rs.getString("name"),
                            rs.getString("status"),
                            rs.getString("assignee_role"),
                            (UUID) rs.getObject("assignee_user_id"),
                            rs.getString("assignee_name"),
                            rs.getBoolean("requires_attachment"),
                            toInstant(rs.getTimestamp("started_at")),
                            toInstant(rs.getTimestamp("completed_at")),
                            rs.getString("comment"),
                            docIds);
                },
                instanceId);

        return new ProcessTimeline(
                (UUID) inst.get("id"),
                (UUID) inst.get("project_id"),
                (String) inst.get("project_name"),
                (String) inst.get("status"),
                (String) inst.get("template_key"),
                (String) inst.get("template_name"),
                (String) inst.get("subject_type"),
                (UUID) inst.get("subject_id"),
                toInstant((Timestamp) inst.get("created_at")),
                (UUID) inst.get("created_by"),
                (String) inst.get("created_by_name"),
                inst.get("current_step_order") == null
                        ? 0 : ((Number) inst.get("current_step_order")).intValue(),
                steps);
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.8  MY TASKS (cross-project)
    // ═══════════════════════════════════════════════════════════════

    public List<MyTaskSummary> listMyTasks(Authentication auth) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        boolean isOwner = Boolean.TRUE.equals(me.getIsOwner());

        /*
         * Inclusion rules:
         *  A. assignee_user_id = me  (directly assigned)
         *  B. assignee_user_id IS NULL AND my project_role = assignee_role  (role fallback)
         *  C. org owner sees everything in org
         */
        return jdbc.query(
                """
                select psi.id as step_instance_id, pi.id as instance_id, pi.project_id,
                       p.name as project_name,
                       pt.key as template_key, pt.name as template_name,
                       pi.subject_type, pi.subject_id,
                       psi.step_key, psi.name as step_name, psi.status,
                       psi.assignee_role, psi.assignee_user_id,
                       u.full_name as assignee_name,
                       psi.requires_attachment, psi.started_at
                from process_step_instance psi
                join process_instance pi on pi.id = psi.instance_id
                join project p on p.id = pi.project_id
                join process_template pt on pt.id = pi.template_id
                left join app_user u on u.id = psi.assignee_user_id
                where psi.status = 'PENDING'
                  and pi.status = 'RUNNING'
                  and pi.org_id = ?
                  and (
                    ? = true                                                 -- owner sees all
                    or psi.assignee_user_id = ?                             -- directly assigned
                    or (psi.assignee_user_id is null                        -- role fallback
                        and exists (
                            select 1 from project_member pm2
                            where pm2.project_id = pi.project_id
                              and pm2.user_id = ?
                              and pm2.project_role = psi.assignee_role
                        ))
                  )
                order by psi.started_at asc nulls last
                """,
                (rs, rowNum) -> new MyTaskSummary(
                        (UUID) rs.getObject("step_instance_id"),
                        (UUID) rs.getObject("instance_id"),
                        (UUID) rs.getObject("project_id"),
                        rs.getString("project_name"),
                        rs.getString("template_key"),
                        rs.getString("template_name"),
                        rs.getString("subject_type"),
                        (UUID) rs.getObject("subject_id"),
                        rs.getString("step_key"),
                        rs.getString("step_name"),
                        rs.getString("status"),
                        rs.getString("assignee_role"),
                        (UUID) rs.getObject("assignee_user_id"),
                        rs.getString("assignee_name"),
                        rs.getBoolean("requires_attachment"),
                        toInstant(rs.getTimestamp("started_at"))
                ),
                orgId, isOwner, me.getId(), me.getId());
    }

    // ═══════════════════════════════════════════════════════════════
    // 3.10  LIST BY PROJECT
    // ═══════════════════════════════════════════════════════════════

    public List<ProcessInstanceSummary> listProcessesByProject(
            Authentication auth, UUID projectId, String statusFilter) {

        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        requireProjectAccess(me, projectId);

        String sql = """
                select pi.id, pt.key as template_key, pt.name as template_name,
                       pi.status, pi.subject_type, pi.subject_id,
                       pi.created_at, pi.current_step_order,
                       (select psi2.name
                        from process_step_instance psi2
                        where psi2.instance_id = pi.id
                        order by psi2.step_order desc, psi2.created_at desc
                        limit 1) as current_step_name
                from process_instance pi
                join process_template pt on pt.id = pi.template_id
                where pi.project_id = ? and pi.org_id = ?
                """ + (statusFilter != null ? " and pi.status = ?" : "") + """
                order by pi.created_at desc
                """;

        Object[] args = statusFilter != null
                ? new Object[]{projectId, orgId, statusFilter}
                : new Object[]{projectId, orgId};

        return jdbc.query(sql,
                (rs, rowNum) -> new ProcessInstanceSummary(
                        (UUID) rs.getObject("id"),
                        rs.getString("template_key"),
                        rs.getString("template_name"),
                        rs.getString("status"),
                        rs.getString("subject_type"),
                        (UUID) rs.getObject("subject_id"),
                        toInstant(rs.getTimestamp("created_at")),
                        rs.getInt("current_step_order"),
                        rs.getString("current_step_name")
                ),
                args);
    }

    // ═══════════════════════════════════════════════════════════════
    // TEMPLATE MANAGEMENT  (admin / owner)
    // ═══════════════════════════════════════════════════════════════

    public List<TemplateOption> listTemplatesForOrg(Authentication auth) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        return jdbc.query(
                """
                select id, key, name, active from process_template
                where active = true and (org_id = ? or org_id is null)
                order by key
                """,
                (rs, rowNum) -> new TemplateOption(
                        (UUID) rs.getObject("id"),
                        rs.getString("key"),
                        rs.getString("name"),
                        rs.getBoolean("active")),
                orgId);
    }

    public List<TemplateOption> listAllTemplates(Authentication auth) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();
        return jdbc.query(
                """
                select id, key, name, active from process_template
                where org_id = ? or org_id is null
                order by key
                """,
                (rs, rowNum) -> new TemplateOption(
                        (UUID) rs.getObject("id"),
                        rs.getString("key"),
                        rs.getString("name"),
                        rs.getBoolean("active")),
                orgId);
    }

    public TemplateDetail getTemplate(Authentication auth, UUID templateId) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select id, key, name, org_id, active, created_at
                from process_template
                where id = ? and (org_id = ? or org_id is null)
                """,
                templateId, orgId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("TEMPLATE_NOT_FOUND",
                    "Template introuvable");
        }
        Map<String, Object> t = rows.get(0);

        List<TemplateStepView> steps = jdbc.query(
                """
                select id, step_order, step_key, name, assignee_role, requires_attachment
                from process_template_step
                where template_id = ?
                order by step_order asc
                """,
                (rs, rowNum) -> new TemplateStepView(
                        (UUID) rs.getObject("id"),
                        rs.getInt("step_order"),
                        rs.getString("step_key"),
                        rs.getString("name"),
                        rs.getString("assignee_role"),
                        rs.getBoolean("requires_attachment")),
                templateId);

        return new TemplateDetail(
                (UUID) t.get("id"),
                (String) t.get("key"),
                (String) t.get("name"),
                (UUID) t.get("org_id"),
                Boolean.TRUE.equals(t.get("active")),
                toInstant((Timestamp) t.get("created_at")),
                steps);
    }

    @Transactional
    public TemplateDetail createTemplate(Authentication auth, CreateTemplateRequest req) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        if (req.key() == null || req.key().isBlank())
            throw new BadRequestException("REQUIRED_FIELDS", "La clé est obligatoire");
        if (req.name() == null || req.name().isBlank())
            throw new BadRequestException("REQUIRED_FIELDS", "Le nom est obligatoire");

        // Uniqueness check (org-scoped)
        Integer exists = jdbc.queryForObject(
                "select count(*) from process_template where key = ? and org_id = ?",
                Integer.class, req.key().toUpperCase().trim(), orgId);
        if (exists != null && exists > 0) {
            throw new BusinessRuleException("TEMPLATE_KEY_EXISTS",
                    "Un template avec cette clé existe déjà dans votre organisation");
        }

        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into process_template (id, key, name, org_id, active, created_at, updated_at)
                values (?, ?, ?, ?, true, now(), now())
                """,
                id, req.key().toUpperCase().trim(), req.name().trim(), orgId);

        audit.log("PROCESS_TEMPLATE_CREATED", "process_template", id,
                "{\"key\":\"" + jsonEscape(req.key()) + "\",\"orgId\":\"" + orgId + "\"}");

        return getTemplate(auth, id);
    }

    @Transactional
    public TemplateDetail updateTemplate(Authentication auth, UUID templateId,
                                         UpdateTemplateRequest req) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        // Ensure template belongs to this org (not a system template)
        List<UUID> owned = jdbc.queryForList(
                "select id from process_template where id = ? and org_id = ?",
                UUID.class, templateId, orgId);
        if (owned.isEmpty()) {
            throw new ResourceNotFoundException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou non modifiable (template système)");
        }

        // Cannot deactivate if RUNNING instances
        if (Boolean.FALSE.equals(req.active())) {
            Integer runningCnt = jdbc.queryForObject(
                    "select count(*) from process_instance where template_id = ? and status = 'RUNNING'",
                    Integer.class, templateId);
            if (runningCnt != null && runningCnt > 0) {
                throw new BusinessRuleException("RUNNING_INSTANCES_EXIST",
                        "Impossible de désactiver : des instances sont en cours d'exécution");
            }
        }

        if (req.name() != null && !req.name().isBlank()) {
            jdbc.update(
                    "update process_template set name = ?, updated_at = now() where id = ?",
                    req.name().trim(), templateId);
        }
        if (req.active() != null) {
            jdbc.update(
                    "update process_template set active = ?, updated_at = now() where id = ?",
                    req.active(), templateId);
        }

        audit.log("PROCESS_TEMPLATE_UPDATED", "process_template", templateId,
                "{\"active\":\"" + req.active() + "\"}");

        return getTemplate(auth, templateId);
    }

    // ── Template Steps ──────────────────────────────────────────────

    @Transactional
    public TemplateDetail addStep(Authentication auth, UUID templateId, CreateStepRequest req) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        requireOwnedTemplate(templateId, orgId);

        if (req.stepKey() == null || req.stepKey().isBlank())
            throw new BadRequestException("REQUIRED_FIELDS", "La clé de l'étape est obligatoire");

        UUID stepId = UUID.randomUUID();
        try {
            jdbc.update(
                    """
                    insert into process_template_step
                      (id, template_id, step_order, step_key, name, assignee_role, requires_attachment)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    stepId, templateId, req.stepOrder(),
                    req.stepKey().toUpperCase().trim(),
                    req.name(), req.assigneeRole(), req.requiresAttachment());
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new BusinessRuleException("STEP_EXISTS",
                    "Un step_order ou step_key en conflit existe déjà sur ce template");
        }

        audit.log("PROCESS_STEP_ADDED", "process_template_step", templateId,
                "{\"stepKey\":\"" + jsonEscape(req.stepKey()) +
                "\",\"stepOrder\":" + req.stepOrder() + "}");

        return getTemplate(auth, templateId);
    }

    @Transactional
    public TemplateDetail updateStep(Authentication auth, UUID templateId,
                                     UUID stepId, UpdateStepRequest req) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        requireOwnedTemplate(templateId, orgId);

        List<UUID> found = jdbc.queryForList(
                "select id from process_template_step where id = ? and template_id = ?",
                UUID.class, stepId, templateId);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND", "Étape introuvable");
        }

        if (req.name() != null)
            jdbc.update("update process_template_step set name = ? where id = ?",
                    req.name(), stepId);
        if (req.assigneeRole() != null)
            jdbc.update("update process_template_step set assignee_role = ? where id = ?",
                    req.assigneeRole(), stepId);
        jdbc.update("update process_template_step set requires_attachment = ? where id = ?",
                req.requiresAttachment(), stepId);
        if (req.stepOrder() != null) {
            try {
                jdbc.update("update process_template_step set step_order = ? where id = ?",
                        req.stepOrder(), stepId);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw new BusinessRuleException("STEP_ORDER_CONFLICT",
                        "Un autre step utilise déjà cet ordre");
            }
        }

        audit.log("PROCESS_STEP_UPDATED", "process_template_step", stepId,
                "{\"templateId\":\"" + templateId + "\"}");

        return getTemplate(auth, templateId);
    }

    @Transactional
    public void deleteStep(Authentication auth, UUID templateId, UUID stepId) {
        AppUser me = currentUser(auth);
        requireOwner(me);
        UUID orgId = me.getOrgId();

        requireOwnedTemplate(templateId, orgId);

        // Cannot delete if RUNNING instances use this template
        Integer runningCnt = jdbc.queryForObject(
                "select count(*) from process_instance where template_id = ? and status = 'RUNNING'",
                Integer.class, templateId);
        if (runningCnt != null && runningCnt > 0) {
            throw new BusinessRuleException("RUNNING_INSTANCES_EXIST",
                    "Impossible de supprimer l'étape : des instances utilisent ce template en cours");
        }

        jdbc.update("delete from process_template_step where id = ? and template_id = ?",
                stepId, templateId);

        audit.log("PROCESS_STEP_DELETED", "process_template_step", stepId,
                "{\"templateId\":\"" + templateId + "\"}");
    }

    private void requireOwnedTemplate(UUID templateId, UUID orgId) {
        List<UUID> owned = jdbc.queryForList(
                "select id from process_template where id = ? and org_id = ?",
                UUID.class, templateId, orgId);
        if (owned.isEmpty()) {
            throw new ResourceNotFoundException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou non modifiable (template système)");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // PROJECT ↔ TEMPLATE CONFIGURATION
    // ═══════════════════════════════════════════════════════════════

    public List<ProjectTemplateConfig> listProjectTemplates(Authentication auth, UUID projectId) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        requireProjectAccess(me, projectId);

        return jdbc.query(
                """
                select pt.id as template_id, pt.key as template_key, pt.name as template_name,
                       ppt.enabled, ppt.default_for_subject_type
                from process_template pt
                left join project_process_template ppt
                       on ppt.template_id = pt.id and ppt.project_id = ?
                where pt.active = true and (pt.org_id = ? or pt.org_id is null)
                order by pt.key
                """,
                (rs, rowNum) -> new ProjectTemplateConfig(
                        (UUID) rs.getObject("template_id"),
                        rs.getString("template_key"),
                        rs.getString("template_name"),
                        Boolean.TRUE.equals(rs.getObject("enabled")),
                        rs.getString("default_for_subject_type")),
                projectId, orgId);
    }

    @Transactional
    public void assignTemplateToProject(Authentication auth, UUID projectId,
                                        AssignTemplateRequest req) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        requireOwner(me);

        UUID templateId = jdbc.queryForObject(
                "select id from process_template where key = ? and active = true",
                UUID.class, req.templateKey());
        if (templateId == null) {
            throw new BadRequestException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou inactif: " + req.templateKey());
        }

        Integer projCnt = jdbc.queryForObject(
                "select count(*) from project where id = ? and org_id = ?",
                Integer.class, projectId, orgId);
        if (projCnt == null || projCnt == 0) {
            throw new ResourceNotFoundException("PROJECT_NOT_FOUND", "Projet introuvable");
        }

        jdbc.update(
                """
                insert into project_process_template
                  (project_id, template_id, enabled, default_for_subject_type)
                values (?, ?, ?, ?)
                on conflict (project_id, template_id)
                do update set enabled = excluded.enabled,
                              default_for_subject_type = excluded.default_for_subject_type
                """,
                projectId, templateId, req.enabled(), req.defaultForSubjectType());

        audit.log("PROCESS_TEMPLATE_ASSIGNED", "project_process_template", projectId,
                "{\"templateKey\":\"" + jsonEscape(req.templateKey()) +
                "\",\"enabled\":\"" + req.enabled() +
                "\",\"defaultFor\":\"" + jsonEscape(req.defaultForSubjectType()) + "\"}");
    }

    // Kept for backwards-compatibility with the old single-template assign API
    @Transactional
    public void assignTemplateToProject(Authentication auth, UUID projectId,
                                        String templateKey, boolean enabled) {
        assignTemplateToProject(auth, projectId,
                new AssignTemplateRequest(templateKey, enabled, null));
    }
}
