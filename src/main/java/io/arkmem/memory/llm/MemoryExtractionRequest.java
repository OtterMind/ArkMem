package io.arkmem.memory.llm;

import io.arkmem.memory.ChatMessage;
import io.arkmem.memory.MemoryRecord;

import java.util.List;

public record MemoryExtractionRequest(
        List<ChatMessage> messages,
        List<MemoryRecord> existingMemories,
        String customPrompt,
        String memoryType,
        boolean agentScoped
) {
}
