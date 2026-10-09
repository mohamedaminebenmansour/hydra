package tn.hydra.ticket;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.audit.EventLogService;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketRepository tickets;
    private final AppUserRepository users;
    private final EventLogService audit;

    public TicketController(TicketRepository tickets, AppUserRepository users, EventLogService audit) {
        this.tickets = tickets; this.users = users; this.audit = audit;
    }

    public record CreateRequest(String title, String description) {}
    public record TicketResponse(UUID id, String title, String description,
                                 String status, Instant createdAt) {}

    private AppUser currentUser(Authentication auth) {
        return users.findByEmail(auth.getName()).orElseThrow();
    }
    private TicketResponse toResponse(Ticket t) {
        return new TicketResponse(t.getId(), t.getTitle(), t.getDescription(),
                t.getStatus(), t.getCreatedAt());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_TICKET_READ')")
    public List<TicketResponse> list(Authentication auth) {
        AppUser u = currentUser(auth);
        return tickets.findByOrgIdOrderByCreatedAtDesc(u.getOrgId())
                .stream().map(this::toResponse).toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_TICKET_WRITE')")
    public TicketResponse create(@RequestBody CreateRequest body, Authentication auth) {
        AppUser u = currentUser(auth);
        Ticket t = new Ticket();
        t.setOrgId(u.getOrgId());
        t.setTitle(body.title());
        t.setDescription(body.description());
        t.setStatus("OUVERT");
        t.setCreatedBy(u.getId());
        t.setCreatedAt(Instant.now());
        t = tickets.save(t);
        audit.log("TICKET_CREATED", "ticket", t.getId(), "{\"title\":\"" + body.title() + "\"}");
        return toResponse(t);
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('PERM_TICKET_WRITE')")
    public ResponseEntity<?> close(@PathVariable UUID id, Authentication auth) {
        AppUser u = currentUser(auth);
        Ticket t = tickets.findById(id).orElse(null);
        if (t == null || !t.getOrgId().equals(u.getOrgId()))
            return ResponseEntity.notFound().build();
        t.setStatus("RESOLU");
        t.setResolvedAt(Instant.now());
        t.setResolvedBy(u.getId());
        tickets.save(t);
        audit.log("TICKET_CLOSED", "ticket", t.getId(), null);
        return ResponseEntity.ok().build();
    }
}