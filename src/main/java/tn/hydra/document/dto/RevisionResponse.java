package tn.hydra.document.dto;

import java.time.Instant;
import java.util.UUID;

public record RevisionResponse(
        UUID id,
        String revLabel,
        String fileName,
        String mimeType,
        Long sizeBytes,
        String changeNote,
        UUID uploadedBy,
        Instant uploadedAt) {}