package tn.hydra.document.dto;

import tn.hydra.document.domain.DocStatus;
import tn.hydra.document.domain.DocType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String reference,
        UUID projectId,
        UUID folderId,
        UUID ticketId,
        String title,
        String description,
        DocType docType,
        String currentRev,
        DocStatus status,
        List<String> tags,
        int revisionCount,
        UUID createdBy,
        Instant createdAt,
        Instant updatedAt) {}