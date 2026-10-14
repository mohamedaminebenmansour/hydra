package tn.hydra.document.dto;

import tn.hydra.document.domain.DocStatus;
import tn.hydra.document.domain.DocType;

import java.util.List;
import java.util.UUID;

/**
 * Partial update: title / docType / status / ticketId are applied only when non-null.
 * A non-null tagNames replaces the whole tag set. Use the link endpoint to detach a ticket.
 */
public record UpdateDocumentRequest(
        String title,
        DocType docType,
        DocStatus status,
        List<String> tagNames,
        UUID ticketId) {}