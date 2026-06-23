package io.arkmem.memory.api.dto;

import io.arkmem.memory.MemoryFilter;
import io.arkmem.memory.SearchCommand;
import jakarta.validation.constraints.NotBlank;

import java.util.LinkedHashMap;
import java.util.Map;

public class SearchMemoryRequest {

    @NotBlank
    private String query;
    private String userId;
    private String agentId;
    private String runId;
    private Map<String, Object> filters = new LinkedHashMap<>();
    private Integer topK;
    private Double threshold;
    private String searchMode;

    public SearchCommand toCommand() {
        return new SearchCommand(
                query,
                new MemoryFilter(userId, agentId, runId, filters),
                topK,
                threshold,
                searchMode
        );
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
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

    public Integer getTopK() {
        return topK;
    }

    public void setTopK(Integer topK) {
        this.topK = topK;
    }

    public Double getThreshold() {
        return threshold;
    }

    public void setThreshold(Double threshold) {
        this.threshold = threshold;
    }

    public String getSearchMode() {
        return searchMode;
    }

    public void setSearchMode(String searchMode) {
        this.searchMode = searchMode;
    }
}
