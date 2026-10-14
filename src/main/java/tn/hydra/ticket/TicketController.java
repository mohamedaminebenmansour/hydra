package tn.hydra.ticket;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.ticket.TicketService.TicketResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService service;

    public TicketController(TicketService service) {
        this.service = service;
    }

    public record CreateTicketRequest(String title, String description, UUID projectId,
                                      UUID assignedToId, String priority,
                                      LocalDate startAt, LocalDate dueAt,
                                      UUID documentId) {}

    public record UpdateTicketRequest(String title, String description, UUID projectId,
                                      UUID assignedToId, String priority,
                                      LocalDate startAt, LocalDate dueAt,
                                      UUID documentId) {}

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_TICKET_READ')")
    public List<TicketResponse> list(Authentication auth,
                                     @RequestParam(required = false) UUID projectId,
                                     @RequestParam(required = false) UUID assignedTo,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String priority,
                                     @RequestParam(required = false) Boolean mine) {
        return service.list(auth, projectId, assignedTo, status, priority, mine);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TICKET_READ')")
    public ResponseEntity<TicketResponse> get(@PathVariable UUID id, Authentication auth) {
        TicketResponse t = service.get(auth, id);
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_TICKET_WRITE')")
    public TicketResponse create(@RequestBody CreateTicketRequest body, Authentication auth) {
        return service.create(auth, body.title(), body.description(), body.projectId(),
                body.assignedToId(), body.priority(), body.startAt(), body.dueAt(), body.documentId());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TICKET_WRITE')")
    public ResponseEntity<TicketResponse> update(@PathVariable UUID id,
                                                 @RequestBody UpdateTicketRequest body,
                                                 Authentication auth) {
        TicketResponse t = service.update(auth, id, body.title(), body.description(),
                body.projectId(), body.assignedToId(), body.priority(),
                body.startAt(), body.dueAt(), body.documentId());
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t);
    }

    // Status flow: DRAFT → IN_REVIEW → APPROVED → IFC → AS_BUILT (workflow).
    // Moves a ticket to the given status if the transition is allowed.
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PERM_TICKET_WRITE')")
    public ResponseEntity<?> setStatus(@PathVariable UUID id,
                                       @RequestParam String status,
                                       Authentication auth) {
        return ResponseEntity.ok(service.setStatus(auth, id, status));
    }

    /** Allowed next statuses for the current status (empty when terminal). */
    @GetMapping("/{id}/transitions")
    @PreAuthorize("hasAuthority('PERM_TICKET_READ')")
    public List<String> transitions(@PathVariable UUID id, Authentication auth) {
        return service.availableTransitions(auth, id);
    }

    // ── linked documents ──────────────────────────────────────────────
    @GetMapping("/{id}/documents")
    @PreAuthorize("hasAuthority('PERM_TICKET_READ')")
    public List<Map<String, Object>> documents(@PathVariable UUID id, Authentication auth) {
        return service.documentsOf(auth, id);
    }
}
