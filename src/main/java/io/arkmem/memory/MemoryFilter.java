package io.arkmem.memory;

import java.util.LinkedHashMap;
import java.util.Map;

public record MemoryFilter(String userId, String agentId, String runId, Map<String, Object> metadata) {

    public MemoryFilter {
        metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    public boolean isEmpty() {
        return isBlank(userId) && isBlank(agentId) && isBlank(runId) && metadata.isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
