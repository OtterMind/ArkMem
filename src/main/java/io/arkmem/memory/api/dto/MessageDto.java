package io.arkmem.memory.api.dto;

import io.arkmem.memory.ChatMessage;
import jakarta.validation.constraints.NotBlank;

public record MessageDto(
        @NotBlank String role,
        @NotBlank String content,
        String name
) {

    public ChatMessage toCommand() {
        return new ChatMessage(role, content, name);
    }
}
