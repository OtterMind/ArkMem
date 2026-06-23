package io.arkmem.memory;

public record SearchCommand(String query, MemoryFilter filter, Integer topK, Double threshold, String searchMode) {

    public SearchCommand(String query, MemoryFilter filter, Integer topK, Double threshold) {
        this(query, filter, topK, threshold, null);
    }
}
