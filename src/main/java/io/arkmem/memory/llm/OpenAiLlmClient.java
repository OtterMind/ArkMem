package io.arkmem.memory.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.arkmem.memory.ChatMessage;
import io.arkmem.memory.ExternalServiceException;
import io.arkmem.memory.ExtractedMemory;
import io.arkmem.memory.MemoryEvent;
import io.arkmem.memory.MemoryRecord;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OpenAiLlmClient implements MemoryExtractor, InstructionGenerator {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final double temperature;
    private final Duration timeout;
    private final MemoryPromptProvider promptProvider;

    public OpenAiLlmClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String apiKey,
            String baseUrl,
            String model,
            double temperature,
            Duration timeout
    ) {
        this(httpClient, objectMapper, apiKey, baseUrl, model, temperature, timeout, new MemoryPromptProvider("en"));
    }

    public OpenAiLlmClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String apiKey,
            String baseUrl,
            String model,
            double temperature,
            Duration timeout,
            MemoryPromptProvider promptProvider
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.endpoint = trimTrailingSlash(baseUrl) + "/chat/completions";
        this.model = model;
        this.temperature = temperature;
        this.timeout = timeout;
        this.promptProvider = promptProvider;
    }

    @Override
    public List<ExtractedMemory> extractMemories(MemoryExtractionRequest request) {
        String response = completeJson(
                promptProvider.extractionSystemPrompt(request),
                promptProvider.extractionUserPrompt(request)
        );
        return parseExtractedMemories(response);
    }

    @Override
    public List<MemoryChange> planMemoryChanges(MemoryDecisionRequest request) {
        if (request.extractedMemories() == null || request.extractedMemories().isEmpty()) {
            return List.of();
        }
        String response = completeJson(
                promptProvider.decisionSystemPrompt(),
                promptProvider.decisionUserPrompt(request)
        );
        return parseMemoryChanges(response);
    }

    @Override
    public InstructionSuggestion generateInstructions(String useCase) {
        String response = completeJson(
                promptProvider.instructionGenerationSystemPrompt(),
                promptProvider.instructionGenerationUserPrompt(useCase)
        );
        try {
            JsonNode root = objectMapper.readTree(stripCodeFence(response));
            return new InstructionSuggestion(
                    root.path("custom_instructions").asText("Capture stable user facts, preferences, plans, constraints, and recurring context."),
                    root.path("test_message").asText("I prefer concise implementation-focused answers.")
            );
        } catch (IOException e) {
            throw new ExternalServiceException("Failed to parse instruction generation response", e);
        }
    }

    private String completeJson(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ExternalServiceException("OpenAI LLM API key is not configured");
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        ArrayNode messages = body.putArray("messages");
        messages.add(messageNode("system", systemPrompt));
        messages.add(messageNode("user", userPrompt));
        body.set("response_format", objectMapper.createObjectNode().put("type", "json_object"));

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ExternalServiceException("LLM request failed with status " + response.statusCode());
            }
            return parseContent(response.body());
        } catch (IOException e) {
            throw new ExternalServiceException("LLM request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("LLM request was interrupted", e);
        }
    }

    private ObjectNode messageNode(String role, String content) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private String parseContent(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        String content = root.path("choices").path(0).path("message").path("content").asText();
        if (content == null || content.isBlank()) {
            throw new ExternalServiceException("LLM response did not contain message content");
        }
        return content;
    }

    private List<ExtractedMemory> parseExtractedMemories(String response) {
        try {
            JsonNode root = objectMapper.readTree(stripCodeFence(response));
            JsonNode facts = root.has("facts") ? root.path("facts") : root.path("memory");
            List<ExtractedMemory> result = new ArrayList<>();
            if (!facts.isArray()) {
                return result;
            }
            for (JsonNode item : facts) {
                ExtractedMemory extracted = parseFactNode(item);
                if (extracted != null) {
                    result.add(extracted);
                }
            }
            return result;
        } catch (IOException e) {
            throw new ExternalServiceException("Failed to parse memory extraction response", e);
        }
    }

    private List<MemoryChange> parseMemoryChanges(String response) {
        try {
            JsonNode root = objectMapper.readTree(stripCodeFence(response));
            JsonNode changes = root.path("memory");
            List<MemoryChange> result = new ArrayList<>();
            if (!changes.isArray()) {
                return result;
            }
            for (JsonNode item : changes) {
                MemoryChange change = parseMemoryChangeNode(item);
                if (change != null) {
                    result.add(change);
                }
            }
            return result;
        } catch (IOException e) {
            throw new ExternalServiceException("Failed to parse memory decision response", e);
        }
    }

    private MemoryChange parseMemoryChangeNode(JsonNode item) {
        String eventText = item.path("event").asText("NONE").trim();
        MemoryEvent event;
        try {
            event = MemoryEvent.valueOf(eventText.toUpperCase());
        } catch (IllegalArgumentException e) {
            event = MemoryEvent.NONE;
        }
        String text = item.path("text").asText(item.path("memory").asText(null));
        String id = item.path("id").asText(null);
        String oldMemory = item.path("old_memory").asText(null);
        if ((event == MemoryEvent.ADD || event == MemoryEvent.UPDATE) && (text == null || text.isBlank())) {
            return null;
        }
        return new MemoryChange(id, text == null ? null : text.trim(), event, oldMemory);
    }

    private ExtractedMemory parseFactNode(JsonNode item) {
        if (item.isTextual()) {
            String text = item.asText();
            return text.isBlank() ? null : new ExtractedMemory(text.trim(), new LinkedHashMap<>());
        }
        String text = item.path("text").asText(item.path("memory").asText(null));
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        JsonNode metadataNode = item.path("metadata");
        if (metadataNode.isObject()) {
            metadataNode.fields().forEachRemaining(entry -> metadata.put(entry.getKey(), toPlainValue(entry.getValue())));
        }
        return new ExtractedMemory(text.trim(), metadata);
    }

    private Object toPlainValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNull()) {
            return null;
        }
        return node.toString();
    }

    private static String stripCodeFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline >= 0 && lastFence > firstNewline) {
            return trimmed.substring(firstNewline + 1, lastFence).trim();
        }
        return trimmed;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://api.openai.com/v1";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
