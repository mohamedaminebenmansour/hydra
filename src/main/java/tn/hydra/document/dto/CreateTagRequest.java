package tn.hydra.document.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateTagRequest(@NotBlank String name) {}