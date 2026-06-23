package io.arkmem.memory;

import java.util.List;
import java.util.Optional;

public interface MemoryRepository {

    MemoryRecord save(MemoryRecord record);

    Optional<MemoryRecord> findById(String id);

    List<MemoryRecord> findAll(MemoryFilter filter);

    List<MemorySearchResult> search(
            String query,
            List<Double> embedding,
            MemoryFilter filter,
            int topK,
            double threshold,
            SearchMode searchMode
    );

    void markDeleted(String id);

    int markDeleted(MemoryFilter filter);

    void appendHistory(MemoryHistoryEntry entry);

    List<MemoryHistoryEntry> history(String memoryId);

    void reset();
}
