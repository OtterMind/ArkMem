package io.arkmem.memory;

public record MemoryOperationResult(
        String id,
        String memory,
        MemoryEvent event,
        String oldMemory,
        Double score
) {
}
