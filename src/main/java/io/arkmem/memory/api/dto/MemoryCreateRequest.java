package io.arkmem.memory.api.dto;

import io.arkmem.memory.MemoryCreateCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MemoryCreateRequest {

    @NotEmpty
    @Valid
    private List<MessageDto> messages;
    private String userId;
    private String agentId;
    private String runId;
    private Map<String, Object> metadata = new LinkedHashMap<>();
    private Boolean infer = true;
    private String memoryType;
    private String prompt;

    public MemoryCreateCommand toCommand() {
        return new MemoryCreateCommand(
                messages.stream().map(MessageDto::toCommand).toList(),
                userId,
                agentId,
                runId,
                metadata,
                infer == null || infer,
                memoryType,
                prompt
        );
    }

    public List<MessageDto> getMessages() {
        return messages;
    }

    public void setMessages(List<MessageDto> messages) {
        this.messages = messages;
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

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    public Boolean getInfer() {
        return infer;
    }

    public void setInfer(Boolean infer) {
        this.infer = infer;
    }

    public String getMemoryType() {
        return memoryType;
    }

    public void setMemoryType(String memoryType) {
        this.memoryType = memoryType;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }
}
