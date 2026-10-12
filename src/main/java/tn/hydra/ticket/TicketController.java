package tn.hydra.ticket;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.ticket.TicketService.TicketResponse;

import java.time.LocalDate;
import java.util.List;
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
                                      LocalDate startAt, LocalDate dueAt) {}

    public record UpdateTicketRequest(String title, String description, UUID projectId,
                                      UUID assignedToId, String priority,
                                      LocalDate startAt, LocalDate dueAt) {}

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
                body.assignedToId(), body.priority(), body.startAt(), body.dueAt());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_TICKET_WRITE')")
    public ResponseEntity<TicketResponse> update(@PathVariable UUID id,
                                                 @RequestBody UpdateTicketRequest body,
                                                 Authentication auth) {
        TicketResponse t = service.update(auth, id, body.title(), body.description(),
                body.projectId(), body.assignedToId(), body.priority(),
                body.startAt(), body.dueAt());
        return t == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(t);
    }

    // Status flow: OUVERT → EN_COURS → (EN_ATTENTE ↔ EN_COURS) → RESOLU.
    // The assignee or a PERM_TICKET_WRITE holder may drive start/wait/resume.
    @PostMapping("/{id}/start")
    @PreAuthorize("hasAuthority('PERM_TICKET_READ')")
    public ResponseEntity<?> start(@PathVariable UUID id, Authentication auth) {
        service.start(auth, id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/wait")
    @PreAuthorize("hasAuthority('PERM_TICKET_READ')")
    public ResponseEntity<?> wait(@PathVariable UUID id, Authentication auth) {
        service.wait_(auth, id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('PERM_TICKET_READ')")
    public ResponseEntity<?> resume(@PathVariable UUID id, Authentication auth) {
        service.resume(auth, id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('PERM_TICKET_WRITE')")
    public ResponseEntity<?> close(@PathVariable UUID id, Authentication auth) {
        service.close(auth, id);
        return ResponseEntity.ok().build();
    }
}
