package tn.hydra.process;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/processes")
public class ProcessController {

    private final ProcessService processService;

    public ProcessController(ProcessService processService) {
        this.processService = processService;
    }

    @PostMapping("/start")
    public ResponseEntity<UUID> start(@RequestBody ProcessService.StartProcessRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID processId = processService.start(auth, req);
        return new ResponseEntity<>(processId, HttpStatus.CREATED);
    }

    @PostMapping("/{processId}/steps/{stepInstanceId}/complete")
    public ResponseEntity<ProcessService.StepResult> completeStep(@PathVariable UUID processId,
                                                                  @PathVariable UUID stepInstanceId,
                                                                  @RequestBody ProcessService.CompleteStepRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        processService.completeStep(auth, processId, stepInstanceId, req);
        return ResponseEntity.ok(new ProcessService.StepResult(stepInstanceId, "DONE"));
    }

    @PostMapping("/{processId}/steps/{stepInstanceId}/reject")
    public ResponseEntity<ProcessService.StepResult> rejectStep(@PathVariable UUID processId,
                                                                @PathVariable UUID stepInstanceId,
                                                                @RequestBody ProcessService.RejectStepRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        processService.rejectStep(auth, processId, stepInstanceId, req);
        return ResponseEntity.ok(new ProcessService.StepResult(stepInstanceId, "REJECTED"));
    }

    @GetMapping("/{processId}/timeline")
    public ResponseEntity<ProcessService.ProcessTimeline> getTimeline(@PathVariable UUID processId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ProcessService.ProcessTimeline timeline = processService.getTimeline(auth, processId);
        return ResponseEntity.ok(timeline);
    }

    @GetMapping("/my-tasks")
    public ResponseEntity<List<ProcessService.MyTaskSummary>> getMyTasks() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<ProcessService.MyTaskSummary> tasks = processService.listMyTasks(auth);
        return ResponseEntity.ok(tasks);
    }

    /* ═════════════════════════════════════════════════════════════════════
       PROJECT-SCOPED PROCESS ENDPOINTS  (chantier screen)
       ════════════════════════════════════════════════════════════════════ */

    @GetMapping("/projects/{projectId}/processes")
    public ResponseEntity<List<ProcessService.ProcessInstanceSummary>> listProcessesByProject(
            @PathVariable UUID projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<ProcessService.ProcessInstanceSummary> processes =
                processService.listProcessesByProject(auth, projectId);
        return ResponseEntity.ok(processes);
    }

    /* ═════════════════════════════════════════════════════════════════════
       TEMPLATES
       ════════════════════════════════════════════════════════════════════ */

    @GetMapping("/templates")
    public ResponseEntity<List<ProcessService.TemplateOption>> listTemplatesForOrg() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<ProcessService.TemplateOption> templates = processService.listTemplatesForOrg(auth);
        return ResponseEntity.ok(templates);
    }

    @GetMapping("/projects/{projectId}/process-templates")
    public ResponseEntity<List<ProcessService.TemplateOption>> listEnabledTemplatesForProject(
            @PathVariable UUID projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<ProcessService.TemplateOption> templates =
                processService.listEnabledTemplatesForProject(auth, projectId);
        return ResponseEntity.ok(templates);
    }

    @PostMapping("/projects/{projectId}/process-templates")
    public ResponseEntity<Void> assignTemplateToProject(@PathVariable UUID projectId,
                                                        @RequestBody ProcessService.AssignTemplateRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        processService.assignTemplateToProject(auth, projectId, req);
        return ResponseEntity.ok().build();
    }
}