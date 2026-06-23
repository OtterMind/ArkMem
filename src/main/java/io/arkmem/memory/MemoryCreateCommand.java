package io.arkmem.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record MemoryCreateCommand(
        List<ChatMessage> messages,
        String userId,
        String agentId,
        String runId,
        Map<String, Object> metadata,
        boolean infer,
        String memoryType,
        String prompt
) {

    public MemoryCreateCommand {
        metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }
}
