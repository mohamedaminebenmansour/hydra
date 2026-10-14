package tn.hydra.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {
    List<Ticket> findByOrgIdOrderByCreatedAtDesc(UUID orgId);
    Optional<Ticket> findByIdAndOrgId(UUID id, UUID orgId);
}