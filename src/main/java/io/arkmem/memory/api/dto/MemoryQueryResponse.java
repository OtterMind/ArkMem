package io.arkmem.memory.api.dto;

import java.util.List;

public record MemoryQueryResponse<T>(
        List<T> results,
        int total,
        int limit,
        int offset
) {
}
