package io.arkmem.memory.llm;

import io.arkmem.memory.MemoryEvent;

public record MemoryChange(String id, String text, MemoryEvent event, String oldMemory) {
}
