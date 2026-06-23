package io.arkmem.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MemoryRecord {

    private String id;
    private String memory;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private List<Double> embedding = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;
    private boolean deleted;

    public MemoryRecord() {
    }

    public MemoryRecord(
            String id,
            String memory,
            Map<String, Object> metadata,
            List<Double> embedding,
            Instant createdAt,
            Instant updatedAt,
            boolean deleted
    ) {
        this.id = id;
        this.memory = memory;
        this.metadata = copyMap(metadata);
        this.embedding = copyList(embedding);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deleted = deleted;
    }

    public static MemoryRecord copyOf(MemoryRecord source) {
        return new MemoryRecord(
                source.id,
                source.memory,
                source.metadata,
                source.embedding,
                source.createdAt,
                source.updatedAt,
                source.deleted
        );
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = copyMap(metadata);
    }

    public List<Double> getEmbedding() {
        return embedding;
    }

    public void setEmbedding(List<Double> embedding) {
        this.embedding = copyList(embedding);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    private static Map<String, Object> copyMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private static List<Double> copyList(List<Double> source) {
        return source == null ? new ArrayList<>() : new ArrayList<>(source);
    }
}
