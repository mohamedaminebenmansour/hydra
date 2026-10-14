package tn.hydra.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import tn.hydra.document.domain.DocType;

import java.util.List;
import java.util.UUID;

/** Create a document (metadata only; the first file is uploaded as Rev.0 afterwards). */
public record CreateDocumentRequest(
        @NotBlank String title,
        String description,
        @NotNull DocType docType,
        List<String> tagNames,
        UUID ticketId) {}