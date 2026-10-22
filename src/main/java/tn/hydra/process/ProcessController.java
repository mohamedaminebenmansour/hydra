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
    public ResponseEntity<Void> completeStep(@PathVariable UUID processId,
                                             @PathVariable UUID stepInstanceId,
                                             @RequestBody ProcessService.CompleteStepRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        processService.completeStep(auth, processId, stepInstanceId, req);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{processId}/steps/{stepInstanceId}/reject")
    public ResponseEntity<Void> rejectStep(@PathVariable UUID processId,
                                           @PathVariable UUID stepInstanceId,
                                           @RequestBody ProcessService.RejectStepRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        processService.rejectStep(auth, processId, stepInstanceId, req);
        return ResponseEntity.ok().build();
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
}