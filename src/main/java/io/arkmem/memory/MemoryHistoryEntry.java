package io.arkmem.memory;

import java.time.Instant;

public class MemoryHistoryEntry {

    private String id;
    private String memoryId;
    private String oldMemory;
    private String newMemory;
    private MemoryEvent event;
    private Instant createdAt;

    public MemoryHistoryEntry() {
    }

    public MemoryHistoryEntry(String id, String memoryId, String oldMemory, String newMemory, MemoryEvent event, Instant createdAt) {
        this.id = id;
        this.memoryId = memoryId;
        this.oldMemory = oldMemory;
        this.newMemory = newMemory;
        this.event = event;
        this.createdAt = createdAt;
    }

    public static MemoryHistoryEntry copyOf(MemoryHistoryEntry source) {
        return new MemoryHistoryEntry(
                source.id,
                source.memoryId,
                source.oldMemory,
                source.newMemory,
                source.event,
                source.createdAt
        );
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(String memoryId) {
        this.memoryId = memoryId;
    }

    public String getOldMemory() {
        return oldMemory;
    }

    public void setOldMemory(String oldMemory) {
        this.oldMemory = oldMemory;
    }

    public String getNewMemory() {
        return newMemory;
    }

    public void setNewMemory(String newMemory) {
        this.newMemory = newMemory;
    }

    public MemoryEvent getEvent() {
        return event;
    }

    public void setEvent(MemoryEvent event) {
        this.event = event;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
