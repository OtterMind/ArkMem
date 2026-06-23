package io.arkmem.memory.api.dto;

import jakarta.validation.constraints.NotBlank;

public record GenerateInstructionsRequest(@NotBlank String useCase) {
}
