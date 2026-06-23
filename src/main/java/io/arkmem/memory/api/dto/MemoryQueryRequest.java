package io.arkmem.memory.api.dto;

import io.arkmem.memory.BadRequestException;
import io.arkmem.memory.MemoryFilter;

import java.util.LinkedHashMap;
import java.util.Map;

public class MemoryQueryRequest {

    private String userId;
    private String agentId;
    private String runId;
    private Map<String, Object> filters = new LinkedHashMap<>();
    private Integer limit = 50;
    private Integer offset = 0;
    private String scopeMode = "normal";

    public MemoryFilter toNormalFilter() {
        return new MemoryFilter(userId, agentId, runId, filters);
    }

    public int resolvedLimit() {
        int resolved = limit == null ? 50 : limit;
        if (resolved <= 0) {
            throw new BadRequestException("limit must be positive");
        }
        return resolved;
    }

    public int resolvedOffset() {
        int resolved = offset == null ? 0 : offset;
        if (resolved < 0) {
            throw new BadRequestException("offset must be non-negative");
        }
        return resolved;
    }

    public String resolvedScopeMode() {
        return scopeMode == null || scopeMode.isBlank() ? "normal" : scopeMode.trim();
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public Map<String, Object> getFilters() {
        return filters;
    }

    public void setFilters(Map<String, Object> filters) {
        this.filters = filters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(filters);
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public Integer getOffset() {
        return offset;
    }

    public void setOffset(Integer offset) {
        this.offset = offset;
    }

    public String getScopeMode() {
        return scopeMode;
    }

    public void setScopeMode(String scopeMode) {
        this.scopeMode = scopeMode;
    }
}
