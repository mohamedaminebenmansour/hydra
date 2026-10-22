package tn.hydra.process;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import tn.hydra.audit.EventLogService;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProcessService {

    public record StartProcessRequest(UUID projectId, String templateKey, String subjectType, UUID subjectId) {}
    public record CompleteStepRequest(String comment, List<UUID> documentIds) {}
    public record RejectStepRequest(String comment) {}
    public record ProcessTimeline(UUID processId, List<String> events) {}
    public record MyTaskSummary(UUID processId, String stepName) {}

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

    public UUID start(Authentication auth, StartProcessRequest req) {
        UUID userId = currentUserId(auth);
        UUID orgId = users.findById(userId)
                .map(AppUser::getOrgId)
                .orElseThrow(() -> new IllegalStateException("User has no org"));

        UUID templateId = jdbc.queryForObject(
                """
                select id from process_template
                where key = ? and org_id is null and active = true
                """,
                UUID.class, req.templateKey());

        UUID instanceId = UUID.randomUUID();
        jdbc.update(
                """
                insert into process_instance (id, org_id, project_id, template_id,
                                             subject_type, subject_id, status,
                                             created_by, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'RUNNING', ?, now(), now())
                """,
                instanceId, orgId, req.projectId(), templateId,
                req.subjectType(), req.subjectId(), userId);

        audit.log("PROCESS_STARTED", "process_instance", instanceId,
                "{\"templateKey\":\"" + req.templateKey() + "\",\"subjectType\":\"" + req.subjectType() + "\"}");

        return instanceId;
    }

    public void completeStep(Authentication auth, UUID instanceId, UUID stepInstanceId, CompleteStepRequest req) {
        jdbc.update(
                """
                update process_step_instance
                set status = 'DONE', completed_at = now(), comment = ?
                where id = ?
                """,
                req.comment(), stepInstanceId);

        audit.log("STEP_COMPLETED", "process_step_instance", stepInstanceId,
                "{\"instanceId\":\"" + instanceId + "\",\"comment\":\"" + (req.comment() != null ? req.comment() : "") + "\"}");
    }

    public void rejectStep(Authentication auth, UUID instanceId, UUID stepInstanceId, RejectStepRequest req) {
        jdbc.update(
                """
                update process_step_instance
                set status = 'REJECTED', completed_at = now(), comment = ?
                where id = ?
                """,
                req.comment(), stepInstanceId);

        audit.log("STEP_REJECTED", "process_step_instance", stepInstanceId,
                "{\"instanceId\":\"" + instanceId + "\",\"comment\":\"" + (req.comment() != null ? req.comment() : "") + "\"}");
    }

    public ProcessTimeline getTimeline(Authentication auth, UUID instanceId) {
        List<String> events = jdbc.queryForList(
                """
                select psi.step_key || ': ' || psi.status || ' (' || psi.created_at || ')'
                from process_step_instance psi
                where psi.instance_id = ?
                order by psi.created_at asc
                """,
                String.class, instanceId);

        return new ProcessTimeline(instanceId, events);
    }

    public List<MyTaskSummary> listMyTasks(Authentication auth) {
        UUID userId = currentUserId(auth);

        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select pi.id as process_id, psi.step_key as step_name
                from process_step_instance psi
                join process_instance pi on pi.id = psi.instance_id
                where psi.assignee_user_id = ? and psi.status = 'PENDING'
                order by psi.created_at asc
                """,
                userId);

        return rows.stream()
                .map(r -> new MyTaskSummary(
                        (UUID) r.get("process_id"),
                        (String) r.get("step_name")))
                .toList();
    }
}
