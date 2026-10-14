package tn.hydra.document.dto;

import tn.hydra.document.domain.DocStatus;
import tn.hydra.document.domain.DocType;

import java.time.Instant;
import java.util.UUID;

/** Query parameters for document search (all optional filters). */
public record DocumentSearchQuery(
        UUID folderId,
        UUID projectId,
        DocType type,
        DocStatus status,
        UUID tagId,
        String q,
        Instant dateFrom,
        Instant dateTo,
        UUID uploaderId,
        String revision) {}