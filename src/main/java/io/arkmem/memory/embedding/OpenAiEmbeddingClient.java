package io.arkmem.memory.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.arkmem.memory.ExternalServiceException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final int MAX_BATCH_SIZE = 100;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final Integer dimensions;
    private final Duration timeout;

    public OpenAiEmbeddingClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            String apiKey,
            String baseUrl,
            String model,
            Integer dimensions,
            Duration timeout
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.endpoint = trimTrailingSlash(baseUrl) + "/embeddings";
        this.model = model;
        this.dimensions = dimensions;
        this.timeout = timeout;
    }

    @Override
    public List<Double> embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    @Override
    public List<List<Double>> embedBatch(List<String> texts) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ExternalServiceException("OpenAI embedding API key is not configured");
        }
        List<List<Double>> embeddings = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += MAX_BATCH_SIZE) {
            int end = Math.min(start + MAX_BATCH_SIZE, texts.size());
            embeddings.addAll(requestEmbeddings(texts.subList(start, end)));
        }
        return embeddings;
    }

    private List<List<Double>> requestEmbeddings(List<String> texts) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("encoding_format", "float");
        body.putArray("input").addAll(texts.stream()
                .map(text -> objectMapper.getNodeFactory().textNode(sanitize(text)))
                .toList());
        if (dimensions != null && dimensions > 0) {
            body.put("dimensions", dimensions);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new ExternalServiceException("Embedding request failed with status " + response.statusCode());
            }
            return parseEmbeddings(response.body());
        } catch (IOException e) {
            throw new ExternalServiceException("Embedding request failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("Embedding request was interrupted", e);
        }
    }

    private List<List<Double>> parseEmbeddings(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        List<JsonNode> data = new ArrayList<>();
        root.path("data").forEach(data::add);
        data.sort(Comparator.comparingInt(node -> node.path("index").asInt()));

        List<List<Double>> result = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            List<Double> vector = new ArrayList<>();
            item.path("embedding").forEach(value -> vector.add(value.asDouble()));
            result.add(vector);
        }
        return result;
    }

    private static String sanitize(String text) {
        return text == null ? "" : text.replace('\n', ' ');
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
