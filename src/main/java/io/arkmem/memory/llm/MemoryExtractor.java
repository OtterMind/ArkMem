package io.arkmem.memory.llm;

import io.arkmem.memory.ChatMessage;
import io.arkmem.memory.ExtractedMemory;
import io.arkmem.memory.MemoryRecord;

import java.util.List;

public interface MemoryExtractor {

    List<ExtractedMemory> extractMemories(MemoryExtractionRequest request);

    List<MemoryChange> planMemoryChanges(MemoryDecisionRequest request);

    default List<ExtractedMemory> extractMemories(
            List<ChatMessage> messages,
            List<MemoryRecord> existingMemories,
            String customPrompt
    ) {
        return extractMemories(new MemoryExtractionRequest(messages, existingMemories, customPrompt, null, false));
    }
}
