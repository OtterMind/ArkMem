package io.arkmem.memory.llm;

import io.arkmem.memory.ExtractedMemory;
import io.arkmem.memory.MemoryRecord;

import java.util.List;

public record MemoryDecisionRequest(
        List<ExtractedMemory> extractedMemories,
        List<MemoryRecord> existingMemories,
        String customPrompt
) {
}
