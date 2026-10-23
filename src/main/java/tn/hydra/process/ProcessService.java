package tn.hydra.process;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ProcessService {

    public record StartProcessRequest(UUID projectId, String templateKey, String subjectType, UUID subjectId) {}
    public record CompleteStepRequest(String comment, List<UUID> documentIds) {}
    public record RejectStepRequest(String comment) {}

    /** Rich cross-project task row for the my-tasks screen. */
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
            String assigneeName,
            Instant createdAt) {}

    /** Timeline returned as a JSON object (instance + steps). */
    public record ProcessTimeline(
            UUID instanceId,
            UUID projectId,
            String status,
            String templateKey,
            String subjectType,
            UUID subjectId,
            Instant createdAt,
            List<StepView> steps) {}

    public record StepView(
            UUID stepInstanceId,
            int stepOrder,
            String stepKey,
            String name,
            String status,
            UUID assigneeUserId,
            String comment,
            Instant completedAt) {}

    public record StepResult(UUID stepInstanceId, String newStatus) {}

    /** One process instance for the chantier / project list. */
    public record ProcessInstanceSummary(
            UUID id,
            String templateKey,
            String status,
            String subjectType,
            UUID subjectId,
            Instant createdAt,
            String currentStepName) {}

    /** Template option for the start form / project template list. */
    public record TemplateOption(String key, String name) {}

    public record AssignTemplateRequest(String templateKey, boolean enabled) {}

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public ProcessService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    private UUID currentUserId(Authentication auth) {
        String email = auth.getName();
        return users.findByEmail(email)
                .map(AppUser::getId)
                .orElseThrow(() -> new IllegalStateException("Unknown user: " + email));
    }

    private AppUser currentUser(Authentication auth) {
        UUID userId = currentUserId(auth);
        return users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Unknown user: " + userId));
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Throw unless the caller is the org owner or a member of the given project. */
    private void requireProjectAccess(AppUser me, UUID projectId, UUID orgId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        Integer cnt = jdbc.queryForObject(
                "select count(*) from project_member where project_id = ? and user_id = ?",
                Integer.class, projectId, me.getId());
        if (cnt == null || cnt == 0) {
            throw new ResourceNotFoundException("PROJECT_NOT_FOUND", "Projet introuvable ou accès refusé");
        }
    }

    /** Resolve a project member carrying the given project role (null if none). */
    private UUID resolveAssigneeByRole(UUID projectId, String assigneeRole) {
        List<UUID> rows = jdbc.queryForList(
                "select pm.user_id from project_member pm where pm.project_id = ? and pm.project_role = ? limit 1",
                UUID.class, projectId, assigneeRole);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public UUID start(Authentication auth, StartProcessRequest req) {
        UUID userId = currentUserId(auth);
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        if (orgId == null) {
            throw new IllegalStateException("User has no org");
        }

        // Project membership (owner bypasses)
        requireProjectAccess(me, req.projectId(), orgId);

        // Resolve template: prefer org-scoped template, else system (org_id IS NULL)
        UUID templateId = jdbc.queryForObject(
                """
                select id from process_template
                where key = ? and active = true
                  and (org_id = ? or org_id is null)
                order by (org_id = ?) desc
                limit 1
                """,
                UUID.class, req.templateKey(), orgId, orgId);
        if (templateId == null) {
            throw new BadRequestException("TEMPLATE_NOT_FOUND",
                    "Template introuvable ou inactif: " + req.templateKey());
        }

        // v1 project enablement rule:
        //  - if any project_process_template rows exist for the project,
        //    the template must have an enabled row;
        //  - if no rows exist for the project, any active template is open.
        Integer ptCount = jdbc.queryForObject(
                "select count(*) from project_process_template where project_id = ?",
                Integer.class, req.projectId());
        if (ptCount != null && ptCount > 0) {
            Integer enabledCount = jdbc.queryForObject(
                    """
                    select count(*) from project_process_template
                    where project_id = ? and template_id = ? and enabled = true
                    """,
                    Integer.class, req.projectId(), templateId);
            if (enabledCount == null || enabledCount == 0) {
                throw new BusinessRuleException("TEMPLATE_NOT_ENABLED_ON_PROJECT",
                        "Ce template n'est pas activé sur ce projet");
            }
        }

        // Guard: only one RUNNING instance per (org, subject_type, subject_id)
        Integer running = jdbc.queryForObject(
                """
                select count(*) from process_instance
                where org_id = ? and subject_type = ? and subject_id = ? and status = 'RUNNING'
                """,
                Integer.class, orgId, req.subjectType(), req.subjectId());
        if (running != null && running > 0) {
            throw new BusinessRuleException("PROCESS_ALREADY_RUNNING",
                    "Une instance RUNNING existe déjà pour ce sujet");
        }

        UUID instanceId = UUID.randomUUID();
        jdbc.update(
                """
                insert into process_instance (id, org_id, project_id, template_id,
                                             subject_type, subject_id, status, current_step_order,
                                             created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'RUNNING', 1, ?, now(), now())
                """,
                instanceId, orgId, req.projectId(), templateId,
                req.subjectType(), req.subjectId(), userId);

        // Insert the FIRST step only, as PENDING
        List<Map<String, Object>> firstSteps = jdbc.queryForList(
                """
                select step_order, step_key, name, assignee_role
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

            UUID assigneeUserId = null;
            if (!Boolean.TRUE.equals(me.getIsOwner())) {
                assigneeUserId = resolveAssigneeByRole(req.projectId(), assigneeRole);
                // assigneeUserId may stay null: owner/finance can still act via role later
            }

            jdbc.update(
                    """
                    insert into process_step_instance (id, instance_id, step_order, step_key, name,
                                                      status, assignee_role, assignee_user_id, created_at)
                    values (?, ?, ?, ?, ?, 'PENDING', ?, ?, now())
                    """,
                    UUID.randomUUID(), instanceId, stepOrder, stepKey, stepName,
                    assigneeRole, assigneeUserId);
        }

        audit.log("PROCESS_STARTED", "process_instance", instanceId,
                "{\"templateKey\":\"" + jsonEscape(req.templateKey()) +
                "\",\"subjectType\":\"" + jsonEscape(req.subjectType()) +
                "\",\"projectId\":\"" + req.projectId() + "\"}");

        return instanceId;
    }

    @Transactional
    public void completeStep(Authentication auth, UUID instanceId, UUID stepInstanceId, CompleteStepRequest req) {
        UUID userId = currentUserId(auth);
        AppUser me = currentUser(auth);

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select psi.id, psi.instance_id, psi.status, psi.step_order, psi.assignee_user_id,
                       pi.template_id, pi.project_id, pi.status as instance_status
                from process_step_instance psi
                join process_instance pi on pi.id = psi.instance_id
                where psi.id = ? and psi.instance_id = ?
                """,
                stepInstanceId, instanceId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND", "Étape introuvable sur cette instance");
        }
        Map<String, Object> step = rows.get(0);
        if (!"PENDING".equals(step.get("status"))) {
            throw new BusinessRuleException("STEP_NOT_PENDING", "L'étape n'est pas en attente");
        }

        UUID assigneeUserId = (UUID) step.get("assignee_user_id");
        if (!Boolean.TRUE.equals(me.getIsOwner()) && !Objects.equals(userId, assigneeUserId)) {
            throw new BusinessRuleException("NOT_ALLOWED", "Vous n'êtes pas l'assigné de cette étape");
        }

        jdbc.update(
                """
                update process_step_instance
                set status = 'DONE', completed_at = now(), completed_by = ?, comment = ?
                where id = ?
                """,
                userId, req.comment(), stepInstanceId);

        UUID templateId = (UUID) step.get("template_id");
        UUID projectId = (UUID) step.get("project_id");
        int currentOrder = ((Number) step.get("step_order")).intValue();

        List<Map<String, Object>> nextSteps = jdbc.queryForList(
                """
                select step_order, step_key, name, assignee_role
                from process_template_step
                where template_id = ? and step_order = ?
                order by step_order asc
                limit 1
                """,
                templateId, currentOrder + 1);

        if (!nextSteps.isEmpty()) {
            Map<String, Object> next = nextSteps.get(0);
            int nextOrder = ((Number) next.get("step_order")).intValue();
            String nextKey = (String) next.get("step_key");
            String nextName = (String) next.get("name");
            String nextRole = (String) next.get("assignee_role");

            UUID nextAssignee = null;
            if (!Boolean.TRUE.equals(me.getIsOwner())) {
                nextAssignee = resolveAssigneeByRole(projectId, nextRole);
            }

            jdbc.update(
                    """
                    insert into process_step_instance (id, instance_id, step_order, step_key, name,
                                                      status, assignee_role, assignee_user_id, created_at)
                    values (?, ?, ?, ?, ?, 'PENDING', ?, ?, now())
                    """,
                    UUID.randomUUID(), instanceId, nextOrder, nextKey, nextName,
                    nextRole, nextAssignee);

            jdbc.update(
                    "update process_instance set current_step_order = ?, updated_at = now() where id = ?",
                    nextOrder, instanceId);
        } else {
            jdbc.update(
                    "update process_instance set status = 'COMPLETED', updated_at = now() where id = ?",
                    instanceId);
        }

        audit.log("STEP_COMPLETED", "process_step_instance", stepInstanceId,
                "{\"instanceId\":\"" + instanceId + "\",\"comment\":\"" + jsonEscape(req.comment()) + "\"}");
    }

    @Transactional
    public void rejectStep(Authentication auth, UUID instanceId, UUID stepInstanceId, RejectStepRequest req) {
        if (req.comment() == null || req.comment().isBlank()) {
            throw new BadRequestException("COMMENT_REQUIRED", "Un commentaire est requis pour rejeter une étape");
        }

        UUID userId = currentUserId(auth);
        AppUser me = currentUser(auth);

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select psi.id, psi.instance_id, psi.status, psi.assignee_user_id
                from process_step_instance psi
                where psi.id = ? and psi.instance_id = ?
                """,
                stepInstanceId, instanceId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND", "Étape introuvable sur cette instance");
        }
        Map<String, Object> step = rows.get(0);
        if (!"PENDING".equals(step.get("status"))) {
            throw new BusinessRuleException("STEP_NOT_PENDING", "L'étape n'est pas en attente");
        }

        UUID assigneeUserId = (UUID) step.get("assignee_user_id");
        if (!Boolean.TRUE.equals(me.getIsOwner()) && !Objects.equals(userId, assigneeUserId)) {
            throw new BusinessRuleException("NOT_ALLOWED", "Vous n'êtes pas l'assigné de cette étape");
        }

        jdbc.update(
                """
                update process_step_instance
                set status = 'REJECTED', completed_at = now(), completed_by = ?, comment = ?
                where id = ?
                """,
                userId, req.comment(), stepInstanceId);

        jdbc.update(
                "update process_instance set status = 'REJECTED', updated_at = now() where id = ?",
                instanceId);

        audit.log("STEP_REJECTED", "process_step_instance", stepInstanceId,
                "{\"instanceId\":\"" + instanceId + "\",\"comment\":\"" + jsonEscape(req.comment()) + "\"}");
    }

    public ProcessTimeline getTimeline(Authentication auth, UUID instanceId) {
        currentUser(auth); // ensures authenticated user exists

        Map<String, Object> inst = jdbc.queryForMap(
                """
                select pi.id, pi.project_id, pi.status, pt.key as template_key,
                       pi.subject_type, pi.subject_id, pi.created_at
                from process_instance pi
                join process_template pt on pt.id = pi.template_id
                where pi.id = ?
                """,
                instanceId);

        List<StepView> steps = jdbc.query(
                """
                select id as step_instance_id, step_order, step_key, name, status,
                       assignee_user_id, comment, completed_at
                from process_step_instance
                where instance_id = ?
                order by step_order asc, created_at asc
                """,
                (rs, rowNum) -> new StepView(
                        (UUID) rs.getObject("step_instance_id"),
                        rs.getInt("step_order"),
                        rs.getString("step_key"),
                        rs.getString("name"),
                        rs.getString("status"),
                        (UUID) rs.getObject("assignee_user_id"),
                        rs.getString("comment"),
                        toInstant(rs.getTimestamp("completed_at"))
                ),
                instanceId);

        return new ProcessTimeline(
                (UUID) inst.get("id"),
                (UUID) inst.get("project_id"),
                (String) inst.get("status"),
                (String) inst.get("template_key"),
                (String) inst.get("subject_type"),
                (UUID) inst.get("subject_id"),
                toInstant((Timestamp) inst.get("created_at")),
                steps);
    }

    public List<MyTaskSummary> listMyTasks(Authentication auth) {
        UUID userId = currentUserId(auth);
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        boolean isOwner = Boolean.TRUE.equals(me.getIsOwner());

        return jdbc.query(
                """
                select psi.id as step_instance_id, pi.id as instance_id, pi.project_id,
                       p.name as project_name,
                       pt.key as template_key, pt.name as template_name,
                       pi.subject_type, pi.subject_id,
                       psi.step_key, psi.name as step_name, psi.status,
                       u.full_name as assignee_name, psi.created_at
                from process_step_instance psi
                join process_instance pi on pi.id = psi.instance_id
                join project p on p.id = pi.project_id
                join process_template pt on pt.id = pi.template_id
                left join app_user u on u.id = psi.assignee_user_id
                where psi.status = 'PENDING'
                  and pi.org_id = ?
                  and (psi.assignee_user_id = ? or ? = true)
                order by psi.created_at asc
                """,
                (rs, rowNum) -> new MyTaskSummary(
                        (UUID) rs.getObject("step_instance_id"),
                        (UUID) rs.getObject("instance_id"),
                        (UUID) rs.getObject("project_id"),
                        (String) rs.getString("project_name"),
                        (String) rs.getString("template_key"),
                        (String) rs.getString("template_name"),
                        (String) rs.getString("subject_type"),
                        (UUID) rs.getObject("subject_id"),
                        (String) rs.getString("step_key"),
                        (String) rs.getString("step_name"),
                        (String) rs.getString("status"),
                        (String) rs.getString("assignee_name"),
                        toInstant(rs.getTimestamp("created_at"))
                ),
                orgId, userId, isOwner);
    }

    /* ════════════════════════════════════════════════════════════════════
       LIST PROCESSES BY PROJECT  (chantier screen)
       ═══════════════════════════════════════════════════════════════════ */

    public List<ProcessInstanceSummary> listProcessesByProject(Authentication auth, UUID projectId) {
        UUID userId = currentUserId(auth);
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();

        requireProjectAccess(me, projectId, orgId);

        return jdbc.query(
                """
                select pi.id, pt.key as template_key, pi.status, pi.subject_type, pi.subject_id,
                       pi.created_at,
                       (select psi.name
                        from process_step_instance psi
                        where psi.instance_id = pi.id
                        order by psi.step_order desc, psi.created_at desc
                        limit 1) as current_step_name
                from process_instance pi
                join process_template pt on pt.id = pi.template_id
                where pi.project_id = ? and pi.org_id = ?
                order by pi.created_at desc
                """,
                (rs, rowNum) -> new ProcessInstanceSummary(
                        (UUID) rs.getObject("id"),
                        (String) rs.getString("template_key"),
                        (String) rs.getString("status"),
                        (String) rs.getString("subject_type"),
                        (UUID) rs.getObject("subject_id"),
                        toInstant(rs.getTimestamp("created_at")),
                        (String) rs.getString("current_step_name")
                ),
                projectId, orgId);
    }

    /* ════════════════════════════════════════════════════════════════════
       TEMPLATES FOR ORG  (start form)
       ═══════════════════════════════════════════════════════════════════ */

    public List<TemplateOption> listTemplatesForOrg(Authentication auth) {
        UUID userId = currentUserId(auth);
        UUID orgId = users.findById(userId)
                .map(AppUser::getOrgId)
                .orElseThrow(() -> new IllegalStateException("User has no org"));

        return jdbc.query(
                """
                select distinct pt.key, pt.name
                from process_template pt
                where pt.active = true
                  and (pt.org_id = ? or pt.org_id is null)
                order by pt.key
                """,
                (rs, rowNum) -> new TemplateOption(rs.getString("key"), rs.getString("name")),
                orgId);
    }

    /* ═════════════════════════════════════════════════════════════════════
       ENABLE/DISABLE TEMPLATE ON PROJECT  (owner only)
       ════════════════════════════════════════════════════════════════════ */

    @Transactional
    public void assignTemplateToProject(Authentication auth, UUID projectId, AssignTemplateRequest req) {
        UUID userId = currentUserId(auth);
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();

        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            throw new BusinessRuleException("OWNER_ONLY",
                    "Seul le gérant peut activer/désactiver un template sur un projet");
        }

        UUID templateId = jdbc.queryForObject(
                "select id from process_template where key = ? and active = true",
                UUID.class, req.templateKey());
        if (templateId == null) {
            throw new BadRequestException("TEMPLATE_NOT_FOUND", "Template introuvable ou inactif: " + req.templateKey());
        }

        Integer projCnt = jdbc.queryForObject(
                "select count(*) from project where id = ? and org_id = ?",
                Integer.class, projectId, orgId);
        if (projCnt == null || projCnt == 0) {
            throw new ResourceNotFoundException("PROJECT_NOT_FOUND", "Projet introuvable");
        }

        jdbc.update(
                """
                insert into project_process_template (project_id, template_id, enabled)
                values (?, ?, ?)
                on conflict (project_id, template_id) do update set enabled = excluded.enabled
                """,
                projectId, templateId, req.enabled());

        audit.log("PROCESS_TEMPLATE_ASSIGNED", "project_process_template", projectId,
                "{\"templateKey\":\"" + jsonEscape(req.templateKey()) +
                "\",\"enabled\":\"" + req.enabled() + "\"}");
    }

    /** List templates enabled for a project. */
    public List<TemplateOption> listEnabledTemplatesForProject(Authentication auth, UUID projectId) {
        UUID userId = currentUserId(auth);
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();

        requireProjectAccess(me, projectId, orgId);

        return jdbc.query(
                """
                select pt.key, pt.name
                from project_process_template ppt
                join process_template pt on pt.id = ppt.template_id
                where ppt.project_id = ? and ppt.enabled = true and pt.active = true
                order by pt.key
                """,
                (rs, rowNum) -> new TemplateOption(rs.getString("key"), rs.getString("name")),
                projectId);
    }
}
