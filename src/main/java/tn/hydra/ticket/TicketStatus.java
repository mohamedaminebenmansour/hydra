package tn.hydra.ticket;

import java.util.Set;

/**
 * Document transmittal / RFI workflow carried by a Ticket:
 * Draft → For Review → Approved → Issued for Construction → As-built.
 * The codes are stable API values; labels are French for the UI.
 */
public enum TicketStatus {
    DRAFT("Brouillon"),
    IN_REVIEW("En revue"),
    APPROVED("Approuvé"),
    IFC("Bon pour exécution"),
    AS_BUILT("As-built");

    private final String label;

    TicketStatus(String label) { this.label = label; }

    public String label() { return label; }

    /** Allowed transitions from a given status (forward-only workflow). */
    public Set<TicketStatus> allowedTransitions() {
        return switch (this) {
            case DRAFT    -> Set.of(IN_REVIEW);
            case IN_REVIEW-> Set.of(APPROVED, DRAFT);
            case APPROVED -> Set.of(IFC, IN_REVIEW);
            case IFC      -> Set.of(AS_BUILT);
            case AS_BUILT -> Set.of();
        };
    }

    public static TicketStatus from(String code) {
        if (code == null || code.isBlank()) return DRAFT;
        return TicketStatus.valueOf(code.trim().toUpperCase());
    }
}
