package tn.hydra.document.dto;

import java.util.UUID;

/** Link (or, with a null ticketId, unlink) a document to a ticket. */
public record LinkTicketRequest(UUID ticketId) {}