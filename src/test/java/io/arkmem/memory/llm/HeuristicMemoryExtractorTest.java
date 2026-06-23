package io.arkmem.memory.llm;

import io.arkmem.memory.ChatMessage;
import io.arkmem.memory.ExtractedMemory;
import io.arkmem.memory.MemoryEvent;
import io.arkmem.memory.MemoryRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicMemoryExtractorTest {

    @Test
    void generatesChineseInstructionSuggestionWhenConfigured() {
        InstructionSuggestion suggestion = new HeuristicMemoryExtractor(new MemoryPromptProvider("zh"))
                .generateInstructions("个人助手");

        assertThat(suggestion.customInstructions())
                .contains("优先记录")
                .contains("个人助手");
        assertThat(suggestion.testMessage()).contains("我偏好");
    }

    @Test
    void plansNoneForExactDuplicatesAndAddForNewFacts() {
        HeuristicMemoryExtractor extractor = new HeuristicMemoryExtractor(new MemoryPromptProvider("en"));
        List<ExtractedMemory> extractedMemories = extractor.extractMemories(new MemoryExtractionRequest(
                List.of(new ChatMessage("user", "User prefers concise answers.", null)),
                List.of(),
                null,
                null,
                false
        ));

        List<MemoryChange> changes = extractor.planMemoryChanges(new MemoryDecisionRequest(
                extractedMemories,
                List.of(memory("11111111-1111-1111-1111-111111111111", "User prefers concise answers.")),
                null
        ));

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).event()).isEqualTo(MemoryEvent.NONE);
    }

    private MemoryRecord memory(String id, String text) {
        return new MemoryRecord(id, text, Map.of(), List.of(0.1d, 0.2d), Instant.now(), Instant.now(), false);
    }
}
