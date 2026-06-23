package io.arkmem.memory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record MemorySearchResult(
        String id,
        String memory,
        double score,
        Double semanticScore,
        Double keywordScore,
        String searchMode,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {

    public MemorySearchResult(
            String id,
            String memory,
            double score,
            Map<String, Object> metadata,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, memory, score, null, null, null, metadata, createdAt, updatedAt);
    }

    public MemorySearchResult {
        metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }
}
