package tn.hydra.document.dto;

import tn.hydra.document.domain.DocStatus;
import tn.hydra.document.domain.DocType;

import java.util.List;
import java.util.UUID;

/**
 * Partial update: title / docType / status / description / ticketId are applied only
 * when non-null. A non-null tagNames replaces the whole tag set.
 */
public record UpdateDocumentRequest(
        String title,
        String description,
        DocType docType,
        DocStatus status,
        List<String> tagNames,
        UUID ticketId) {}