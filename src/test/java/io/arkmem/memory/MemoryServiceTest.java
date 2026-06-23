package io.arkmem.memory;

import io.arkmem.memory.config.ArkMemProperties;
import io.arkmem.memory.embedding.LocalHashEmbeddingClient;
import io.arkmem.memory.llm.HeuristicMemoryExtractor;
import io.arkmem.memory.llm.MemoryChange;
import io.arkmem.memory.llm.MemoryDecisionRequest;
import io.arkmem.memory.llm.MemoryExtractionRequest;
import io.arkmem.memory.llm.MemoryExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryServiceTest {

    private MemoryService memoryService;

    @BeforeEach
    void setUp() {
        ArkMemProperties properties = new ArkMemProperties();
        properties.getEmbedding().setLocalDimensions(128);

        memoryService = new MemoryService(
                new InMemoryMemoryRepository(),
                new LocalHashEmbeddingClient(128),
                new HeuristicMemoryExtractor(),
                properties
        );
    }

    @Test
    void addSearchUpdateHistoryAndDeleteMemory() {
        List<MemoryOperationResult> addResults = memoryService.add(new MemoryCreateCommand(
                List.of(new ChatMessage("user", "I like spicy ramen for late dinners.", null)),
                "user-1",
                null,
                null,
                Map.of("source", "test"),
                true,
                null,
                null
        ));

        assertThat(addResults).hasSize(1);
        assertThat(addResults.get(0).event()).isEqualTo(MemoryEvent.ADD);

        List<MemorySearchResult> searchResults = memoryService.search(new SearchCommand(
                "spicy ramen",
                new MemoryFilter("user-1", null, null, Map.of()),
                5,
                0.0d
        ));

        assertThat(searchResults).isNotEmpty();
        String memoryId = searchResults.get(0).id();

        MemoryOperationResult updateResult = memoryService.update(
                memoryId,
                "I like spicy miso ramen for late dinners.",
                Map.of("updated_by", "test")
        );

        assertThat(updateResult.event()).isEqualTo(MemoryEvent.UPDATE);
        assertThat(memoryService.history(memoryId)).hasSize(2);

        memoryService.delete(memoryId);

        assertThatThrownBy(() -> memoryService.get(memoryId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void skipsDuplicateMemory() {
        MemoryCreateCommand command = new MemoryCreateCommand(
                List.of(new ChatMessage("user", "My preferred IDE is IntelliJ IDEA.", null)),
                "user-1",
                null,
                null,
                Map.of(),
                true,
                null,
                null
        );

        assertThat(memoryService.add(command).get(0).event()).isEqualTo(MemoryEvent.ADD);
        assertThat(memoryService.add(command).get(0).event()).isEqualTo(MemoryEvent.NONE);
    }

    @Test
    void supportsKeywordAndHybridSearchModes() {
        memoryService.add(new MemoryCreateCommand(
                List.of(new ChatMessage("user", "Project codename zeta uses pgvector hybrid retrieval.", null)),
                "user-1",
                null,
                null,
                Map.of("source", "search-mode-test"),
                false,
                null,
                null
        ));
        memoryService.add(new MemoryCreateCommand(
                List.of(new ChatMessage("user", "User prefers short status updates.", null)),
                "user-1",
                null,
                null,
                Map.of("source", "search-mode-test"),
                false,
                null,
                null
        ));

        List<MemorySearchResult> keywordResults = memoryService.search(new SearchCommand(
                "codename zeta",
                new MemoryFilter("user-1", null, null, Map.of()),
                5,
                0.0d,
                "keyword"
        ));

        assertThat(keywordResults).isNotEmpty();
        assertThat(keywordResults.get(0).memory()).contains("codename zeta");
        assertThat(keywordResults.get(0).searchMode()).isEqualTo("keyword");
        assertThat(keywordResults.get(0).keywordScore()).isPositive();
        assertThat(keywordResults.get(0).semanticScore()).isNull();

        List<MemorySearchResult> hybridResults = memoryService.search(new SearchCommand(
                "pgvector retrieval",
                new MemoryFilter("user-1", null, null, Map.of()),
                5,
                0.0d,
                "hybrid"
        ));

        assertThat(hybridResults).isNotEmpty();
        assertThat(hybridResults.get(0).searchMode()).isEqualTo("hybrid");
        assertThat(hybridResults.get(0).semanticScore()).isNotNull();
        assertThat(hybridResults.get(0).keywordScore()).isNotNull();
    }

    @Test
    void rejectsInvalidSearchMode() {
        assertThatThrownBy(() -> memoryService.search(new SearchCommand(
                "anything",
                new MemoryFilter("user-1", null, null, Map.of()),
                5,
                0.0d,
                "invalid"
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("search_mode must be one of semantic, keyword, or hybrid");
    }

    @Test
    void appliesPromptPlannedUpdateAndDelete() {
        ArkMemProperties properties = new ArkMemProperties();
        InMemoryMemoryRepository repository = new InMemoryMemoryRepository();
        MemoryService rawService = new MemoryService(
                repository,
                new LocalHashEmbeddingClient(128),
                new HeuristicMemoryExtractor(),
                properties
        );
        String memoryId = rawService.add(new MemoryCreateCommand(
                List.of(new ChatMessage("user", "User prefers tea.", null)),
                "user-1",
                null,
                null,
                Map.of(),
                false,
                null,
                null
        )).get(0).id();

        MemoryService updatingService = new MemoryService(
                repository,
                new LocalHashEmbeddingClient(128),
                new PlannedMemoryExtractor(List.of(new MemoryChange(
                        memoryId,
                        "User prefers coffee.",
                        MemoryEvent.UPDATE,
                        "User prefers tea."
                ))),
                properties
        );

        MemoryOperationResult update = updatingService.add(new MemoryCreateCommand(
                List.of(new ChatMessage("user", "Actually I prefer coffee.", null)),
                "user-1",
                null,
                null,
                Map.of(),
                true,
                null,
                null
        )).get(0);

        assertThat(update.event()).isEqualTo(MemoryEvent.UPDATE);
        assertThat(rawService.get(memoryId).getMemory()).isEqualTo("User prefers coffee.");

        MemoryService deletingService = new MemoryService(
                repository,
                new LocalHashEmbeddingClient(128),
                new PlannedMemoryExtractor(List.of(new MemoryChange(memoryId, null, MemoryEvent.DELETE, null))),
                properties
        );

        MemoryOperationResult delete = deletingService.add(new MemoryCreateCommand(
                List.of(new ChatMessage("user", "Forget my beverage preference.", null)),
                "user-1",
                null,
                null,
                Map.of(),
                true,
                null,
                null
        )).get(0);

        assertThat(delete.event()).isEqualTo(MemoryEvent.DELETE);
        assertThatThrownBy(() -> rawService.get(memoryId)).isInstanceOf(NotFoundException.class);
    }

    private static class PlannedMemoryExtractor implements MemoryExtractor {

        private final List<MemoryChange> changes;

        private PlannedMemoryExtractor(List<MemoryChange> changes) {
            this.changes = new ArrayList<>(changes);
        }

        @Override
        public List<ExtractedMemory> extractMemories(MemoryExtractionRequest request) {
            return List.of(new ExtractedMemory("planned fact", Map.of()));
        }

        @Override
        public List<MemoryChange> planMemoryChanges(MemoryDecisionRequest request) {
            return changes;
        }
    }
}
