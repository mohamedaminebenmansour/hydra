package tn.hydra.process;

import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

public class ProcessService {

    public static class StartProcessRequest {
        private String processKey;

        public String getProcessKey() { return processKey; }
        public void setProcessKey(String processKey) { this.processKey = processKey; }
    }

    public static class CompleteStepRequest {
        private String outcome;

        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }
    }

    public static class RejectStepRequest {
        private String reason;

        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class ProcessTimeline {
        private UUID processId;
        private List<String> events;

        public ProcessTimeline(UUID processId, List<String> events) {
            this.processId = processId;
            this.events = events;
        }

        public UUID getProcessId() { return processId; }
        public void setProcessId(UUID processId) { this.processId = processId; }
        public List<String> getEvents() { return events; }
        public void setEvents(List<String> events) { this.events = events; }
    }

    public static class MyTaskSummary {
        private UUID processId;
        private String stepName;

        public MyTaskSummary(UUID processId, String stepName) {
            this.processId = processId;
            this.stepName = stepName;
        }

        public UUID getProcessId() { return processId; }
        public void setProcessId(UUID processId) { this.processId = processId; }
        public String getStepName() { return stepName; }
        public void setStepName(String stepName) { this.stepName = stepName; }
    }

    public UUID start(Authentication auth, StartProcessRequest req) {
        String sql = """
        select p.id, p.process_key, p.status
        from process_instance p
        where p.created_by = :user
        """;
        return UUID.randomUUID();
    }

    public void completeStep(Authentication auth, UUID processId, UUID stepInstanceId, CompleteStepRequest req) {
        String sql = """
        update process_step_instance
        set status = 'COMPLETED', completed_at = now()
        where id = :stepId
        """;
    }

    public void rejectStep(Authentication auth, UUID processId, UUID stepInstanceId, RejectStepRequest req) {
        String sql = """
        update process_step_instance
        set status = 'REJECTED', rejection_reason = :reason
        where id = :stepId
        """;
    }

    public ProcessTimeline getTimeline(Authentication auth, UUID processId) {
        String sql = """
        select e.event_type, e.created_at, e.actor
        from process_event e
        where e.process_id = :processId
        order by e.created_at asc
        """;
        return new ProcessTimeline(processId, List.of());
    }

    public List<MyTaskSummary> listMyTasks(Authentication auth) {
        String sql = """
        select s.process_id, s.step_name
        from process_step_instance s
        where s.assignee = :user and s.status = 'OPEN'
        """;
        return List.of();
    }
}
