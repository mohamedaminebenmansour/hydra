package tn.hydra.process;

import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ProcessService {

    private static final String ACTION_START = "PROCESS_INSTANCE_STARTED";
    private static final String ACTION_COMPLETE = "PROCESS_STEP_DONE";
    private static final String ACTION_REJECT = "PROCESS_STEP_REJECTED";
    private static final String ENTITY_INSTANCE = "PROCESS_INSTANCE";
    private static final String ENTITY_STEP = "PROCESS_STEP";

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    @Autowired
    public ProcessService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    public record StartProcessRequest(UUID projectId, String templateKey,
                                      String subjectType, UUID subjectId) {}

    public record CompleteStepRequest(String comment, List<UUID> documentIds) {}

    public record RejectStepRequest(String comment) {}

    public record ProcessInstanceSummary(
            UUID id, UUID projectId, String templateKey, String subjectType, UUID subjectId,
            String status, Integer currentStepOrder, Instant createdAt, String createdByEmail) {}

    public record ProcessStepSummary(
            UUID id, Integer stepOrder, String stepKey, String name, String status,
            String assigneeRole, UUID assigneeUserId, String assigneeUserEmail,
            UUID completedBy, Instant completedAt, String comment, Instant createdAt,
            boolean isCurrent) {}

    public record ProcessTimeline(
            ProcessInstanceSummary instance, List<ProcessStepSummary> steps) {}

    public record MyTaskSummary(
            UUID stepInstanceId, UUID instanceId, UUID projectId, String templateKey,
            String subjectType, UUID subjectId, Integer stepOrder, String stepKey,
            String stepName, String instanceStatus, String instanceStatusLabel) {}



    private AppUser currentUser(Authentication auth) {
        String email = auth != null ? auth.getName() : null;
        if (email == null || email.isBlank()) {
            throw new BadRequestException("NO_AUTH", "Utilisateur non authentifie");
        }
        return users.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("USER_NOT_FOUND", "Utilisateur introuvable"));
    }

    @Transactional
    public UUID start(Authentication auth, StartProcessRequest req) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        UUID userId = me.getId();

        List<Map<String, Object>> templates = jdbc.queryForList(
                "select id, key, name from process_template where key = ? and (org_id is null or org_id = ?) and active = true",
                req.templateKey(), orgId);
        if (templates.isEmpty()) {
            throw new BadRequestException("TEMPLATE_NOT_FOUND", "Template introuvable : " + req.templateKey());
        }
        UUID templateId = (UUID) templates.get(0).get("id");

        List<Map<String, Object>> projects = jdbc.queryForList(
                "select id from project where id = ? and org_id = ?", req.projectId(), orgId);
        if (projects.isEmpty()) {
            throw new BusinessRuleException("PROJECT_NOT_FOUND", "Projet introuvable dans votre organisation");
        }

        List<Map<String, Object>> running = jdbc.queryForList(
                "select id from process_instance where org_id = ? and subject_type = ? and subject_id = ? and status = 'RUNNING'",
                orgId, req.subjectType(), req.subjectId());
        if (!running.isEmpty()) {
            throw new BusinessRuleException("INSTANCE_ALREADY_RUNNING",
                    "Une instance est deja en cours pour ce sujet : " + req.subjectType() + "/" + req.subjectId());
        }

        UUID instanceId = UUID.randomUUID();
        jdbc.update(
                "insert into process_instance (id, org_id, project_id, template_id, subject_type, subject_id, status, current_step_order, created_by, created_at, updated_at) values (?, ?, ?, ?, ?, ?, 'RUNNING', 1, ?, now(), now())",
                instanceId, orgId, req.projectId(), templateId, req.subjectType(), req.subjectId(), userId);

        List<Map<String, Object>> firstSteps = jdbc.queryForList(
                "select id, step_order, step_key, name, assignee_role, requires_attachment from process_template_step where template_id = ? order by step_order asc limit 1",
                templateId);
        if (firstSteps.isEmpty()) {
            throw new BusinessRuleException("TEMPLATE_NO_STEPS", "Le template ne contient aucune etape");
        }
        Map<String, Object> first = firstSteps.get(0);
        UUID stepId = UUID.randomUUID();
        Integer stepOrder = ((Number) first.get("step_order")).intValue();
        String stepKey = (String) first.get("step_key");
        String stepName = (String) first.get("name");
        String assigneeRole = (String) first.get("assignee_role");
        UUID assigneeUserId = resolveAssignee(orgId, req.projectId(), assigneeRole);

        jdbc.update(
                "insert into process_step_instance (id, instance_id, step_order, step_key, name, status, assignee_role, assignee_user_id, created_at) values (?, ?, ?, ?, ?, 'PENDING', ?, ?, now())",
                stepId, instanceId, stepOrder, stepKey, stepName, assigneeRole, assigneeUserId);

        audit.logAs(orgId, userId, ACTION_START, ENTITY_INSTANCE, instanceId,
                "{\"templateKey\":\"" + jsonEscape(req.templateKey()) +
                "\",\"subjectType\":\"" + jsonEscape(req.subjectType()) +
                "\",\"subjectId\":\"" + req.subjectId() + "\",\"projectId\":\"" + req.projectId() + "\"}");

        return instanceId;
    }



    @Transactional
    public void completeStep(Authentication auth, UUID instanceId, UUID stepInstanceId,
                             CompleteStepRequest req) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        UUID userId = me.getId();

        List<Map<String, Object>> instances = jdbc.queryForList(
                "select id, status, current_step_order, template_id from process_instance where id = ? and org_id = ?",
                instanceId, orgId);
        if (instances.isEmpty()) {
            throw new ResourceNotFoundException("INSTANCE_NOT_FOUND", "Instance introuvable");
        }
        Map<String, Object> inst = instances.get(0);
        String instanceStatus = (String) inst.get("status");
        UUID templateId = (UUID) inst.get("template_id");

        if (!"RUNNING".equals(instanceStatus)) {
            throw new BusinessRuleException("INSTANCE_NOT_RUNNING",
                    "L instance n est pas en cours (statut: " + instanceStatus + ")");
        }

        List<Map<String, Object>> steps = jdbc.queryForList(
                "select s.id, s.step_order, s.step_key, s.status, s.assignee_role, s.assignee_user_id, s.completed_by, s.comment from process_step_instance s where s.id = ? and s.instance_id = ?",
                stepInstanceId, instanceId);
        if (steps.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND", "Etape introuvable");
        }
        Map<String, Object> step = steps.get(0);
        String stepStatus = (String) step.get("status");

        if ("DONE".equals(stepStatus)) {
            return;
        }

        if (!"PENDING".equals(stepStatus)) {
            throw new BusinessRuleException("STEP_NOT_PENDING",
                    "L etape n est pas en attente (statut: " + stepStatus + ")");
        }

        Integer stepOrder = ((Number) step.get("step_order")).intValue();
        String stepKey = (String) step.get("step_key");
        String assigneeRole = (String) step.get("assignee_role");
        UUID assigneeUserId = step.get("assignee_user_id") != null ? (UUID) step.get("assignee_user_id") : null;

        boolean isAssignee = assigneeUserId != null && assigneeUserId.equals(userId);
        boolean isRoleHolder = hasSystemRole(orgId, userId, assigneeRole);
        UUID projectId = getProjectId(instanceId);
        boolean isProjectLead = isProjectAdminOrManager(orgId, projectId, userId);
        boolean isOwner = Boolean.TRUE.equals(me.getIsOwner());

        if (!isAssignee && !isRoleHolder && !isProjectLead && !isOwner) {
            throw new BadRequestException("NOT_ASSIGNEE",
                    "Vous n etes pas l assigne de cette etape (role requis: " + assigneeRole + ")");
        }

        List<Map<String, Object>> stepDefs = jdbc.queryForList(
                "select requires_attachment from process_template_step where template_id = ? and step_key = ?",
                templateId, stepKey);
        if (!stepDefs.isEmpty() && Boolean.TRUE.equals(stepDefs.get(0).get("requires_attachment"))) {
            if (req.documentIds() == null || req.documentIds().isEmpty()) {
                throw new BadRequestException("ATTACHMENT_REQUIRED",
                        "Cette etape nécessite au moins une piece jointe (document)");
            }
        }

        jdbc.update(
                "update process_step_instance set status = 'DONE', completed_by = ?, completed_at = now(), comment = ? where id = ?",
                userId, jsonEscape(req.comment()), stepInstanceId);

        if (req.documentIds() != null && !req.documentIds().isEmpty()) {
            for (UUID docId : req.documentIds()) {
                jdbc.update(
                        "insert into process_step_document (step_instance_id, document_id) values (?, ?) on conflict do nothing",
                        stepInstanceId, docId);
            }
        }

        List<Map<String, Object>> nextSteps = jdbc.queryForList(
                "select ts.id, ts.step_order, ts.step_key, ts.name, ts.assignee_role from process_template_step ts where ts.template_id = ? and ts.step_order = ? + 1 order by ts.step_order asc",
                templateId, stepOrder);

        if (nextSteps.isEmpty()) {
            jdbc.update(
                    "update process_instance set status = 'COMPLETED', current_step_order = null, updated_at = now() where id = ?",
                    instanceId);
        } else {
            Map<String, Object> next = nextSteps.get(0);
            UUID nextStepId = UUID.randomUUID();
            Integer nextOrder = ((Number) next.get("step_order")).intValue();
            String nextKey = (String) next.get("step_key");
            String nextName = (String) next.get("name");
            String nextRole = (String) next.get("assignee_role");
            UUID nextAssignee = resolveAssignee(orgId, projectId, nextRole);

            jdbc.update(
                    "insert into process_step_instance (id, instance_id, step_order, step_key, name, status, assignee_role, assignee_user_id, created_at) values (?, ?, ?, ?, ?, 'PENDING', ?, ?, now())",
                    nextStepId, instanceId, nextOrder, nextKey, nextName, nextRole, nextAssignee);

            jdbc.update(
                    "update process_instance set current_step_order = ?, updated_at = now() where id = ?",
                    nextOrder, instanceId);
        }

        audit.logAs(orgId, userId, ACTION_COMPLETE, ENTITY_STEP, stepInstanceId,
                "{\"instanceId\":\"" + instanceId + "\",\"stepKey\":\"" + jsonEscape(stepKey) +
                "\",\"stepOrder\":" + stepOrder + ",\"comment\":\"" + jsonEscape(req.comment()) + "\"}");
    }



    @Transactional
    public void rejectStep(Authentication auth, UUID instanceId, UUID stepInstanceId,
                           RejectStepRequest req) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        UUID userId = me.getId();

        if (req.comment() == null || req.comment().isBlank()) {
            throw new BadRequestException("COMMENT_REQUIRED", "Un commentaire est requis pour rejeter");
        }

        List<Map<String, Object>> instances = jdbc.queryForList(
                "select id, status from process_instance where id = ? and org_id = ?",
                instanceId, orgId);
        if (instances.isEmpty()) {
            throw new ResourceNotFoundException("INSTANCE_NOT_FOUND", "Instance introuvable");
        }
        String instanceStatus = (String) instances.get(0).get("status");
        if (!"RUNNING".equals(instanceStatus)) {
            throw new BusinessRuleException("INSTANCE_NOT_RUNNING",
                    "L instance n est pas en cours (statut: " + instanceStatus + ")");
        }

        List<Map<String, Object>> steps = jdbc.queryForList(
                "select s.id, s.step_order, s.step_key, s.status, s.assignee_role, s.assignee_user_id from process_step_instance s where s.id = ? and s.instance_id = ?",
                stepInstanceId, instanceId);
        if (steps.isEmpty()) {
            throw new ResourceNotFoundException("STEP_NOT_FOUND", "Etape introuvable");
        }
        Map<String, Object> step = steps.get(0);
        String stepStatus = (String) step.get("status");

        if ("REJECTED".equals(stepStatus) || "DONE".equals(stepStatus)) {
            throw new BusinessRuleException("STEP_ALREADY_RESOLVED",
                    "L etape a deja ete traitee (statut: " + stepStatus + ")");
        }

        UUID assigneeUserId = step.get("assignee_user_id") != null ? (UUID) step.get("assignee_user_id") : null;
        String assigneeRole = (String) step.get("assignee_role");

        boolean isAssignee = assigneeUserId != null && assigneeUserId.equals(userId);
        boolean isRoleHolder = hasSystemRole(orgId, userId, assigneeRole);
        UUID projectId = getProjectId(instanceId);
        boolean isProjectLead = isProjectAdminOrManager(orgId, projectId, userId);
        boolean isOwner = Boolean.TRUE.equals(me.getIsOwner());

        if (!isAssignee && !isRoleHolder && !isProjectLead && !isOwner) {
            throw new BadRequestException("NOT_ASSIGNEE",
                    "Vous n etes pas l assigne de cette etape (role requis: " + assigneeRole + ")");
        }

        jdbc.update(
                "update process_step_instance set status = 'REJECTED', completed_by = ?, completed_at = now(), comment = ? where id = ?",
                userId, jsonEscape(req.comment()), stepInstanceId);

        jdbc.update(
                "update process_instance set status = 'REJECTED', current_step_order = null, updated_at = now() where id = ?",
                instanceId);

        audit.logAs(orgId, userId, ACTION_REJECT, ENTITY_STEP, stepInstanceId,
                "{\"instanceId\":\"" + instanceId + "\",\"stepKey\":\"" +
                jsonEscape((String) step.get("step_key")) +
                "\",\"comment\":\"" + jsonEscape(req.comment()) + "\"}");
    }

    @Transactional(readOnly = true)
    public ProcessTimeline getTimeline(Authentication auth, UUID instanceId) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();

        List<Map<String, Object>> instances = jdbc.queryForList(
                """select i.id, i.project_id, i.template_id, i.subject_type, i.subject_id,
                          i.status, i.current_step_order, i.created_at, i.created_by,
                          t.key as template_key, u.email as created_by_email
                   from process_instance i
                   join process_template t on t.id = i.template_id
                   left join app_user u on u.id = i.created_by
                   where i.id = ? and i.org_id = ?""", instanceId, orgId);
        if (instances.isEmpty()) {
            throw new ResourceNotFoundException("INSTANCE_NOT_FOUND", "Instance introuvable");
        }
        Map<String, Object> inst = instances.get(0);
        UUID projectId = (UUID) inst.get("project_id");

        ProcessInstanceSummary instanceSummary = new ProcessInstanceSummary(
                (UUID) inst.get("id"),
                projectId,
                (String) inst.get("template_key"),
                (String) inst.get("subject_type"),
                (UUID) inst.get("subject_id"),
                (String) inst.get("status"),
                inst.get("current_step_order") != null ? ((Number) inst.get("current_step_order")).intValue() : null,
                toInstant(inst.get("created_at")),
                (String) inst.get("created_by_email"));

        List<Map<String, Object>> stepRows = jdbc.queryForList(
                """select s.id, s.step_order, s.step_key, s.name, s.status,
                          s.assignee_role, s.assignee_user_id, s.completed_by, s.completed_at,
                          s.comment, s.created_at,
                          au.email as assignee_email, cu.email as completed_by_email
                   from process_step_instance s
                   left join app_user au on au.id = s.assignee_user_id
                   left join app_user cu on cu.id = s.completed_by
                   where s.instance_id = ?
                   order by s.step_order asc, s.created_at asc""", instanceId);

        Integer currentStepOrder = instanceSummary.currentStepOrder();
        List<ProcessStepSummary> steps = stepRows.stream()
                .map(r -> new ProcessStepSummary(
                        (UUID) r.get("id"),
                        ((Number) r.get("step_order")).intValue(),
                        (String) r.get("step_key"),
                        (String) r.get("name"),
                        (String) r.get("status"),
                        (String) r.get("assignee_role"),
                        r.get("assignee_user_id") != null ? (UUID) r.get("assignee_user_id") : null,
                        (String) r.get("assignee_email"),
                        r.get("completed_by") != null ? (UUID) r.get("completed_by") : null,
                        toInstant(r.get("completed_at")),
                        (String) r.get("comment"),
                        toInstant(r.get("created_at")),
                        currentStepOrder != null &&
                                currentStepOrder.equals(((Number) r.get("step_order")).intValue()) &&
                                "PENDING".equals(r.get("status"))
                ))
                .collect(Collectors.toList());

        return new ProcessTimeline(instanceSummary, steps);
    }

    @Transactional(readOnly = true)
    public List<MyTaskSummary> listMyTasks(Authentication auth) {
        AppUser me = currentUser(auth);
        UUID orgId = me.getOrgId();
        UUID userId = me.getId();

        List<Map<String, Object>> rows = jdbc.queryForList(
                """select s.id as step_instance_id, s.instance_id, i.project_id, t.key as template_key,
                          i.subject_type, i.subject_id, s.step_order, s.step_key, s.name,
                          i.status as instance_status
                   from process_step_instance s
                   join process_instance i on i.id = s.instance_id
                   join process_template t on t.id = i.template_id
                   where i.org_id = ? and s.status = 'PENDING'
                     and (s.assignee_user_id = ?
                          or s.assignee_role in (
                            select ur.role_code from user_role ur where ur.user_id = ?
                          ))
                   order by s.created_at asc""", orgId, userId, userId);

        Set<String> userRoles = getUserSystemRoles(orgId, userId);

        return rows.stream()
                .map(r -> {
                    String role = (String) r.get("assignee_role");
                    boolean assignedDirectly = r.get("assignee_user_id") != null &&
                            ((UUID) r.get("assignee_user_id")).equals(userId);
                    boolean assignedByRole = !assignedDirectly && role != null && userRoles.contains(role);

                    return new MyTaskSummary(
                            (UUID) r.get("step_instance_id"),
                            (UUID) r.get("instance_id"),
                            (UUID) r.get("project_id"),
                            (String) r.get("template_key"),
                            (String) r.get("subject_type"),
                            (UUID) r.get("subject_id"),
                            ((Number) r.get("step_order")).intValue(),
                            (String) r.get("step_key"),
                            (String) r.get("name"),
                            (String) r.get("instance_status"),
                            assignedDirectly ? "ASSIGNED_TO_ME" :
                                    assignedByRole ? "ROLE_MATCH_" + role : "AVAILABLE"
                    );
                })
                .collect(Collectors.toList());
    }

    private UUID getProjectId(UUID instanceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select project_id from process_instance where id = ?", instanceId);
        if (rows.isEmpty()) return null;
        return (UUID) rows.get(0).get("project_id");
    }

    private UUID resolveAssignee(UUID orgId, UUID projectId, String assigneeRole) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    """select distinct u.id
                       from project_member pm
                       join app_user u on u.id = pm.user_id
                       where pm.project_id = ? and u.org_id = ? and u.is_active = true
                         and exists (
                           select 1 from user_role ur where ur.user_id = u.id and ur.role_code = ?
                         )
                       limit 1""",
                    projectId, orgId, assigneeRole);
            if (!rows.isEmpty()) {
                return (UUID) rows.get(0).get("id");
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private boolean hasSystemRole(UUID orgId, UUID userId, String roleCode) {
        try {
            List<UUID> found = jdbc.queryForList(
                    "select 1 from user_role where user_id = ? and role_code = ? limit 1",
                    UUID.class, userId, roleCode);
            return !found.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isProjectAdminOrManager(UUID orgId, UUID projectId, UUID userId) {
        if (projectId == null) return false;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "select pm.project_role from project_member pm where pm.project_id = ? and pm.user_id = ?",
                    projectId, userId);
            for (Map<String, Object> r : rows) {
                String role = (String) r.get("project_role");
                if ("ADMIN".equals(role) || "MANAGER".equals(role)) return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private Set<String> getUserSystemRoles(UUID orgId, UUID userId) {
        try {
            List<String> roles = jdbc.queryForList(
                    "select ur.role_code from user_role ur where ur.user_id = ?", String.class, userId);
            return new HashSet<>(roles);
        } catch (Exception e) {
            return Set.of();
        }
    }

    private static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

