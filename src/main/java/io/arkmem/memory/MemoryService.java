package io.arkmem.memory;

import io.arkmem.memory.config.ArkMemProperties;
import io.arkmem.memory.embedding.EmbeddingClient;
import io.arkmem.memory.llm.MemoryChange;
import io.arkmem.memory.llm.MemoryDecisionRequest;
import io.arkmem.memory.llm.MemoryExtractionRequest;
import io.arkmem.memory.llm.MemoryExtractor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class MemoryService {

    private static final String PROCEDURAL_MEMORY_TYPE = "procedural_memory";

    private final MemoryRepository repository;
    private final EmbeddingClient embeddingClient;
    private final MemoryExtractor memoryExtractor;
    private final ArkMemProperties properties;

    public MemoryService(
            MemoryRepository repository,
            EmbeddingClient embeddingClient,
            MemoryExtractor memoryExtractor,
            ArkMemProperties properties
    ) {
        this.repository = repository;
        this.embeddingClient = embeddingClient;
        this.memoryExtractor = memoryExtractor;
        this.properties = properties;
    }

    public List<MemoryOperationResult> add(MemoryCreateCommand command) {
        validateCommand(command);

        MemoryFilter filter = new MemoryFilter(command.userId(), command.agentId(), command.runId(), Map.of());
        Map<String, Object> baseMetadata = buildBaseMetadata(command);
        List<MemoryRecord> existingMemories = new ArrayList<>(repository.findAll(filter));
        List<MemoryChange> changes;
        Map<String, Map<String, Object>> extractedMetadata = new LinkedHashMap<>();
        if (command.infer()) {
            List<ExtractedMemory> extractedMemories = memoryExtractor.extractMemories(new MemoryExtractionRequest(
                    command.messages(),
                    existingMemories,
                    command.prompt(),
                    command.memoryType(),
                    shouldUseAgentMemoryExtraction(command)
            ));
            extractedMemories.forEach(memory -> extractedMetadata.put(memory.text(), memory.metadata()));
            changes = memoryExtractor.planMemoryChanges(new MemoryDecisionRequest(
                    extractedMemories,
                    existingMemories,
                    command.prompt()
            ));
        } else {
            changes = rawMemoryChanges(command.messages());
        }

        if (changes.isEmpty()) {
            return List.of();
        }

        List<MemoryOperationResult> results = new ArrayList<>();
        for (MemoryChange change : changes) {
            Optional<MemoryOperationResult> result = applyMemoryChange(change, baseMetadata, extractedMetadata);
            result.ifPresent(results::add);
        }
        return results;
    }

    public List<MemoryRecord> getAll(MemoryFilter filter) {
        return repository.findAll(filter == null ? new MemoryFilter(null, null, null, Map.of()) : filter);
    }

    public MemoryRecord get(String memoryId) {
        return get(memoryId, null);
    }

    public MemoryRecord get(String memoryId, String userId) {
        return repository.findById(memoryId)
                .filter(record -> !record.isDeleted())
                .filter(record -> isScopedToUser(record, userId))
                .orElseThrow(() -> new NotFoundException("Memory not found"));
    }

    public List<MemorySearchResult> search(SearchCommand command) {
        if (command.query() == null || command.query().isBlank()) {
            throw new BadRequestException("query is required");
        }
        int topK = command.topK() == null ? properties.getMemory().getDefaultTopK() : command.topK();
        if (topK <= 0) {
            throw new BadRequestException("top_k must be positive");
        }
        double threshold = command.threshold() == null ? properties.getMemory().getDefaultThreshold() : command.threshold();
        if (threshold < 0.0d || threshold > 1.0d) {
            throw new BadRequestException("threshold must be between 0 and 1");
        }

        SearchMode searchMode = SearchMode.from(command.searchMode());
        List<Double> queryEmbedding = searchMode.requiresEmbedding()
                ? embeddingClient.embed(command.query())
                : List.of();
        return repository.search(command.query(), queryEmbedding, command.filter(), topK, threshold, searchMode);
    }

    public MemoryOperationResult update(String memoryId, String text, Map<String, Object> metadata) {
        return update(memoryId, null, text, metadata);
    }

    public MemoryOperationResult update(String memoryId, String userId, String text, Map<String, Object> metadata) {
        String normalizedText = normalizeMemoryText(text);
        if (normalizedText == null) {
            throw new BadRequestException("text is required");
        }
        validateScopedMetadataUserId(userId, metadata);

        MemoryRecord existing = get(memoryId, userId);
        String oldMemory = existing.getMemory();
        Instant now = Instant.now();
        Map<String, Object> mergedMetadata = new LinkedHashMap<>(existing.getMetadata());
        if (metadata != null) {
            mergedMetadata.putAll(metadata);
        }

        existing.setMemory(normalizedText);
        existing.setMetadata(mergedMetadata);
        existing.setEmbedding(embeddingClient.embed(normalizedText));
        existing.setUpdatedAt(now);
        repository.save(existing);
        appendHistory(existing.getId(), oldMemory, normalizedText, MemoryEvent.UPDATE, now);
        return new MemoryOperationResult(existing.getId(), existing.getMemory(), MemoryEvent.UPDATE, oldMemory, null);
    }

    public void delete(String memoryId) {
        delete(memoryId, null);
    }

    public void delete(String memoryId, String userId) {
        MemoryRecord existing = get(memoryId, userId);
        repository.markDeleted(existing.getId());
        appendHistory(existing.getId(), existing.getMemory(), null, MemoryEvent.DELETE, Instant.now());
    }

    public int deleteAll(MemoryFilter filter) {
        if (filter == null || (isBlank(filter.userId()) && isBlank(filter.agentId()) && isBlank(filter.runId()))) {
            throw new BadRequestException("At least one identifier is required");
        }
        List<MemoryRecord> records = repository.findAll(filter);
        for (MemoryRecord record : records) {
            repository.markDeleted(record.getId());
            appendHistory(record.getId(), record.getMemory(), null, MemoryEvent.DELETE, Instant.now());
        }
        return records.size();
    }

    public List<MemoryHistoryEntry> history(String memoryId) {
        return history(memoryId, null);
    }

    public List<MemoryHistoryEntry> history(String memoryId, String userId) {
        get(memoryId, userId);
        return repository.history(memoryId);
    }

    public void reset() {
        repository.reset();
    }

    private void validateCommand(MemoryCreateCommand command) {
        if (command.messages() == null || command.messages().isEmpty()) {
            throw new BadRequestException("messages is required");
        }
        if (isBlank(command.userId()) && isBlank(command.agentId()) && isBlank(command.runId())) {
            throw new BadRequestException("At least one identifier (user_id, agent_id, run_id) is required");
        }
        validateIdentifier(command.userId(), "user_id");
        validateIdentifier(command.agentId(), "agent_id");
        validateIdentifier(command.runId(), "run_id");
        if (command.memoryType() != null && !PROCEDURAL_MEMORY_TYPE.equals(command.memoryType())) {
            throw new BadRequestException("memory_type must be procedural_memory when provided");
        }
    }

    private Map<String, Object> buildBaseMetadata(MemoryCreateCommand command) {
        Map<String, Object> metadata = new LinkedHashMap<>(command.metadata());
        putIfNotBlank(metadata, "user_id", command.userId());
        putIfNotBlank(metadata, "agent_id", command.agentId());
        putIfNotBlank(metadata, "run_id", command.runId());
        if (command.memoryType() != null) {
            metadata.put("memory_type", command.memoryType());
        }
        return metadata;
    }

    private List<MemoryChange> rawMemoryChanges(List<ChatMessage> messages) {
        return messages.stream()
                .filter(message -> !message.isSystem())
                .map(message -> {
                    String text = normalizeMemoryText(message.content());
                    return text == null ? null : new MemoryChange("new", text, MemoryEvent.ADD, null);
                })
                .filter(change -> change != null)
                .toList();
    }

    private Optional<MemoryOperationResult> applyMemoryChange(
            MemoryChange change,
            Map<String, Object> baseMetadata,
            Map<String, Map<String, Object>> extractedMetadata
    ) {
        MemoryEvent event = change.event() == null ? MemoryEvent.NONE : change.event();
        return switch (event) {
            case ADD -> addMemory(change, baseMetadata, extractedMetadata);
            case UPDATE -> updateMemory(change, baseMetadata, extractedMetadata);
            case DELETE -> deleteMemory(change);
            case NONE -> Optional.of(noopMemory(change));
        };
    }

    private Optional<MemoryOperationResult> addMemory(
            MemoryChange change,
            Map<String, Object> baseMetadata,
            Map<String, Map<String, Object>> extractedMetadata
    ) {
        String text = normalizeMemoryText(change.text());
        if (text == null) {
            return Optional.empty();
        }
        Map<String, Object> metadata = new LinkedHashMap<>(baseMetadata);
        metadata.putAll(extractedMetadata.getOrDefault(text, Map.of()));
        Instant now = Instant.now();
        MemoryRecord record = new MemoryRecord(
                UUID.randomUUID().toString(),
                text,
                metadata,
                embeddingClient.embed(text),
                now,
                now,
                false
        );
        repository.save(record);
        appendHistory(record.getId(), null, record.getMemory(), MemoryEvent.ADD, now);
        return Optional.of(new MemoryOperationResult(record.getId(), record.getMemory(), MemoryEvent.ADD, null, null));
    }

    private Optional<MemoryOperationResult> updateMemory(
            MemoryChange change,
            Map<String, Object> baseMetadata,
            Map<String, Map<String, Object>> extractedMetadata
    ) {
        String text = normalizeMemoryText(change.text());
        if (text == null || isBlank(change.id())) {
            return Optional.empty();
        }
        Optional<MemoryRecord> existingRecord = repository.findById(change.id()).filter(record -> !record.isDeleted());
        if (existingRecord.isEmpty()) {
            return addMemory(new MemoryChange("new", text, MemoryEvent.ADD, null), baseMetadata, extractedMetadata);
        }
        MemoryRecord existing = existingRecord.get();
        String oldMemory = existing.getMemory();
        Map<String, Object> metadata = new LinkedHashMap<>(existing.getMetadata());
        metadata.putAll(baseMetadata);
        metadata.putAll(extractedMetadata.getOrDefault(text, Map.of()));
        Instant now = Instant.now();
        existing.setMemory(text);
        existing.setMetadata(metadata);
        existing.setEmbedding(embeddingClient.embed(text));
        existing.setUpdatedAt(now);
        repository.save(existing);
        appendHistory(existing.getId(), oldMemory, text, MemoryEvent.UPDATE, now);
        return Optional.of(new MemoryOperationResult(existing.getId(), existing.getMemory(), MemoryEvent.UPDATE, oldMemory, null));
    }

    private Optional<MemoryOperationResult> deleteMemory(MemoryChange change) {
        if (isBlank(change.id())) {
            return Optional.empty();
        }
        Optional<MemoryRecord> existingRecord = repository.findById(change.id()).filter(record -> !record.isDeleted());
        if (existingRecord.isEmpty()) {
            return Optional.empty();
        }
        MemoryRecord existing = existingRecord.get();
        repository.markDeleted(existing.getId());
        appendHistory(existing.getId(), existing.getMemory(), null, MemoryEvent.DELETE, Instant.now());
        return Optional.of(new MemoryOperationResult(existing.getId(), existing.getMemory(), MemoryEvent.DELETE, null, null));
    }

    private MemoryOperationResult noopMemory(MemoryChange change) {
        if (!isBlank(change.id())) {
            Optional<MemoryRecord> existingRecord = repository.findById(change.id()).filter(record -> !record.isDeleted());
            if (existingRecord.isPresent()) {
                MemoryRecord existing = existingRecord.get();
                return new MemoryOperationResult(existing.getId(), existing.getMemory(), MemoryEvent.NONE, null, null);
            }
        }
        return new MemoryOperationResult(change.id(), change.text(), MemoryEvent.NONE, null, null);
    }

    private void appendHistory(String memoryId, String oldMemory, String newMemory, MemoryEvent event, Instant now) {
        repository.appendHistory(new MemoryHistoryEntry(
                UUID.randomUUID().toString(),
                memoryId,
                oldMemory,
                newMemory,
                event,
                now
        ));
    }

    private static boolean isScopedToUser(MemoryRecord record, String userId) {
        if (isBlank(userId)) {
            return true;
        }
        Object ownerUserId = record.getMetadata().get("user_id");
        return ownerUserId != null && userId.trim().equals(String.valueOf(ownerUserId));
    }

    private static void validateScopedMetadataUserId(String scopedUserId, Map<String, Object> metadata) {
        if (isBlank(scopedUserId) || metadata == null || !metadata.containsKey("user_id")) {
            return;
        }
        Object metadataUserId = metadata.get("user_id");
        if (!scopedUserId.trim().equals(String.valueOf(metadataUserId))) {
            throw new BadRequestException("metadata.user_id must match user_id");
        }
    }

    private static String normalizeMemoryText(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private boolean shouldUseAgentMemoryExtraction(MemoryCreateCommand command) {
        return !isBlank(command.agentId())
                && command.messages().stream().anyMatch(message -> "assistant".equalsIgnoreCase(message.role()));
    }

    private static void putIfNotBlank(Map<String, Object> metadata, String key, String value) {
        if (!isBlank(value)) {
            metadata.put(key, value.trim());
        }
    }

    private static void validateIdentifier(String value, String name) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new BadRequestException(name + " cannot be empty");
        }
        if (trimmed.chars().anyMatch(Character::isWhitespace)) {
            throw new BadRequestException(name + " cannot contain whitespace");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
