package tn.hydra.process;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import tn.hydra.common.BusinessRuleException;
import tn.hydra.common.GlobalExceptionHandler;

import java.util.UUID;

@RestController
@RequestMapping("/api/processes")
public class ProcessController {

    @Autowired
    private ProcessService processService;

    @PostMapping("/start")
    public ResponseEntity<UUID> start(@RequestBody ProcessService.StartProcessRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        UUID processId = processService.start(auth, req);
        return new ResponseEntity<>(processId, HttpStatus.CREATED);
    }

    @PostMapping("/{processId}/steps/{stepKey}/complete")
    public ResponseEntity<Void> completeStep(@PathVariable UUID processId, @PathVariable String stepKey,
                                             @RequestBody ProcessService.CompleteStepRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        processService.completeStep(auth, processId, stepKey, req);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{processId}/steps/{stepKey}/reject")
    public ResponseEntity<Void> rejectStep(@PathVariable UUID processId, @PathVariable String stepKey,
                                           @RequestBody ProcessService.RejectStepRequest req) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        processService.rejectStep(auth, processId, stepKey, req);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{processId}/timeline")
    public ResponseEntity<ProcessService.ProcessTimeline> getTimeline(@PathVariable UUID processId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        ProcessService.ProcessTimeline timeline = processService.getTimeline(auth, processId);
        return ResponseEntity.ok(timeline);
    }

    @GetMapping("/{processId}/my-tasks")
    public ResponseEntity<java.util.List<ProcessService.MyTaskSummary>> getMyTasks(@PathVariable UUID processId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        java.util.List<ProcessService.MyTaskSummary> tasks = processService.listMyTasks(auth, processId);
        return ResponseEntity.ok(tasks);
    }
}