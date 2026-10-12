package tn.hydra.audit;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    @GetMapping("/events")
    @PreAuthorize("hasAuthority('PERM_AUDIT_VIEW')")
    public List<AuditService.EventResponse> events(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication auth) {
        return service.events(auth, userId, from, to, action, page, size);
    }

    @GetMapping("/users/{id}/summary")
    @PreAuthorize("hasAnyAuthority('PERM_AUDIT_VIEW','PERM_USER_UPDATE')")
    public AuditService.UserSummaryResponse userSummary(@PathVariable UUID id, Authentication auth) {
        return service.userSummary(auth, id);
    }
}
