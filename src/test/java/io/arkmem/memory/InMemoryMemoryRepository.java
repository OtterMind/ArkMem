package io.arkmem.memory;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class InMemoryMemoryRepository implements MemoryRepository {

    private final Map<String, MemoryRecord> memories = new LinkedHashMap<>();
    private final Map<String, List<MemoryHistoryEntry>> history = new LinkedHashMap<>();

    @Override
    public MemoryRecord save(MemoryRecord record) {
        memories.put(record.getId(), MemoryRecord.copyOf(record));
        return MemoryRecord.copyOf(record);
    }

    @Override
    public Optional<MemoryRecord> findById(String id) {
        MemoryRecord record = memories.get(id);
        return record == null ? Optional.empty() : Optional.of(MemoryRecord.copyOf(record));
    }

    @Override
    public List<MemoryRecord> findAll(MemoryFilter filter) {
        return memories.values().stream()
                .filter(record -> MemoryFilters.matches(record, filter))
                .map(MemoryRecord::copyOf)
                .sorted((left, right) -> Comparator.nullsLast(Instant::compareTo)
                        .compare(right.getCreatedAt(), left.getCreatedAt()))
                .toList();
    }

    @Override
    public List<MemorySearchResult> search(
            String query,
            List<Double> embedding,
            MemoryFilter filter,
            int topK,
            double threshold,
            SearchMode searchMode
    ) {
        return findAll(filter).stream()
                .map(record -> toSearchResult(record, query, embedding, searchMode))
                .filter(result -> result.score() >= threshold)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(topK)
                .toList();
    }

    @Override
    public void markDeleted(String id) {
        MemoryRecord record = memories.get(id);
        if (record != null) {
            record.setDeleted(true);
        }
    }

    @Override
    public int markDeleted(MemoryFilter filter) {
        int count = 0;
        for (MemoryRecord record : memories.values()) {
            if (MemoryFilters.matches(record, filter)) {
                record.setDeleted(true);
                count++;
            }
        }
        return count;
    }

    @Override
    public void appendHistory(MemoryHistoryEntry entry) {
        history.computeIfAbsent(entry.getMemoryId(), ignored -> new java.util.ArrayList<>())
                .add(MemoryHistoryEntry.copyOf(entry));
    }

    @Override
    public List<MemoryHistoryEntry> history(String memoryId) {
        return history.getOrDefault(memoryId, List.of()).stream()
                .map(MemoryHistoryEntry::copyOf)
                .toList();
    }

    @Override
    public void reset() {
        memories.clear();
        history.clear();
    }

    private static MemorySearchResult toSearchResult(
            MemoryRecord record,
            String query,
            List<Double> embedding,
            SearchMode searchMode
    ) {
        double semanticScore = searchMode.requiresEmbedding()
                ? cosineSimilarity(embedding, record.getEmbedding())
                : 0.0d;
        double keywordScore = searchMode == SearchMode.KEYWORD || searchMode == SearchMode.HYBRID
                ? keywordScore(query, record.getMemory())
                : 0.0d;
        double score = switch (searchMode) {
            case SEMANTIC -> semanticScore;
            case KEYWORD -> keywordScore;
            case HYBRID -> (semanticScore * 0.75d) + (keywordScore * 0.25d);
        };
        return new MemorySearchResult(
                record.getId(),
                record.getMemory(),
                score,
                searchMode.requiresEmbedding() ? semanticScore : null,
                searchMode == SearchMode.KEYWORD || searchMode == SearchMode.HYBRID ? keywordScore : null,
                searchMode.value(),
                record.getMetadata(),
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }

    private static double keywordScore(String query, String memory) {
        List<String> terms = tokenize(query);
        if (terms.isEmpty() || memory == null || memory.isBlank()) {
            return 0.0d;
        }
        String normalizedMemory = memory.toLowerCase(Locale.ROOT);
        long matches = terms.stream()
                .filter(normalizedMemory::contains)
                .count();
        return (double) matches / terms.size();
    }

    private static List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{Alnum}]+"))
                .filter(token -> !token.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    private static double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0d;
        }
        double dot = 0.0d;
        double leftNorm = 0.0d;
        double rightNorm = 0.0d;
        for (int i = 0; i < left.size(); i++) {
            double leftValue = left.get(i);
            double rightValue = right.get(i);
            dot += leftValue * rightValue;
            leftNorm += leftValue * leftValue;
            rightNorm += rightValue * rightValue;
        }
        if (leftNorm == 0.0d || rightNorm == 0.0d) {
            return 0.0d;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
