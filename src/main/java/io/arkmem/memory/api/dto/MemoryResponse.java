package io.arkmem.memory.api.dto;

import io.arkmem.memory.MemoryRecord;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record MemoryResponse(
        String id,
        String memory,
        String userId,
        String agentId,
        String runId,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {

    public static MemoryResponse from(MemoryRecord record) {
        Map<String, Object> metadata = new LinkedHashMap<>(record.getMetadata());
        return new MemoryResponse(
                record.getId(),
                record.getMemory(),
                stringValue(metadata.remove("user_id")),
                stringValue(metadata.remove("agent_id")),
                stringValue(metadata.remove("run_id")),
                metadata,
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
