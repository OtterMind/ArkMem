package io.arkmem.memory.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.arkmem.memory.ChatMessage;
import io.arkmem.memory.ExtractedMemory;
import io.arkmem.memory.MemoryEvent;
import io.arkmem.memory.MemoryRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiLlmClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsChineseExtractionPromptWhenConfigured() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(requestBody, """
                {"choices":[{"message":{"content":"{\\"facts\\":[{\\"text\\":\\"用户偏好简洁回答\\",\\"metadata\\":{}}]}"}}]}
                """);
        try {
            OpenAiLlmClient client = newClient(server, new MemoryPromptProvider("zh"));

            List<ExtractedMemory> memories = client.extractMemories(new MemoryExtractionRequest(
                    List.of(new ChatMessage("user", "我偏好简洁回答。", null)),
                    List.of(),
                    "只记录稳定偏好。",
                    null,
                    false
            ));

            JsonNode root = objectMapper.readTree(requestBody.get());
            String systemPrompt = root.path("messages").path(0).path("content").asText();
            String userPrompt = root.path("messages").path(1).path("content").asText();

            assertThat(systemPrompt).contains("个人信息整理器");
            assertThat(userPrompt)
                    .contains("额外抽取指令")
                    .contains("新消息");
            assertThat(memories).hasSize(1);
            assertThat(memories.get(0).text()).isEqualTo("用户偏好简洁回答");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsEnglishDecisionPromptAndParsesUpdate() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(requestBody, """
                {"choices":[{"message":{"content":"{\\"memory\\":[{\\"id\\":\\"11111111-1111-1111-1111-111111111111\\",\\"text\\":\\"User prefers concise implementation-focused summaries\\",\\"event\\":\\"UPDATE\\",\\"old_memory\\":\\"User prefers concise summaries\\"}]}"}}]}
                """);
        try {
            OpenAiLlmClient client = newClient(server, new MemoryPromptProvider("en"));

            List<MemoryChange> changes = client.planMemoryChanges(new MemoryDecisionRequest(
                    List.of(new ExtractedMemory("User prefers concise implementation-focused summaries", Map.of())),
                    List.of(memory("11111111-1111-1111-1111-111111111111", "User prefers concise summaries")),
                    null
            ));

            JsonNode root = objectMapper.readTree(requestBody.get());
            String systemPrompt = root.path("messages").path(0).path("content").asText();
            String userPrompt = root.path("messages").path(1).path("content").asText();

            assertThat(systemPrompt).contains("smart memory manager");
            assertThat(userPrompt)
                    .contains("Current memories")
                    .contains("New extracted facts");
            assertThat(changes).hasSize(1);
            assertThat(changes.get(0).event()).isEqualTo(MemoryEvent.UPDATE);
            assertThat(changes.get(0).oldMemory()).isEqualTo("User prefers concise summaries");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsInstructionGenerationPromptInConfiguredLanguage() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = startServer(requestBody, """
                {"choices":[{"message":{"content":"{\\"custom_instructions\\":\\"记录长期偏好。\\",\\"test_message\\":\\"我希望你记住我的回答偏好。\\"}"}}]}
                """);
        try {
            OpenAiLlmClient client = newClient(server, new MemoryPromptProvider("zh"));

            InstructionSuggestion suggestion = client.generateInstructions("个人助手");

            JsonNode root = objectMapper.readTree(requestBody.get());
            String systemPrompt = root.path("messages").path(0).path("content").asText();
            String userPrompt = root.path("messages").path(1).path("content").asText();

            assertThat(systemPrompt).contains("记忆抽取系统");
            assertThat(userPrompt)
                    .contains("使用场景")
                    .contains("个人助手");
            assertThat(suggestion.customInstructions()).isEqualTo("记录长期偏好。");
            assertThat(suggestion.testMessage()).isEqualTo("我希望你记住我的回答偏好。");
        } finally {
            server.stop(0);
        }
    }

    private OpenAiLlmClient newClient(HttpServer server, MemoryPromptProvider promptProvider) {
        return new OpenAiLlmClient(
                HttpClient.newHttpClient(),
                objectMapper,
                "sk-test",
                "http://localhost:" + server.getAddress().getPort() + "/v1",
                "test-model",
                0.2d,
                Duration.ofSeconds(5),
                promptProvider
        );
    }

    private MemoryRecord memory(String id, String text) {
        return new MemoryRecord(id, text, Map.of(), List.of(0.1d, 0.2d), Instant.now(), Instant.now(), false);
    }

    private static HttpServer startServer(AtomicReference<String> requestBody, String responseBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", exchange -> handle(exchange, requestBody, responseBody));
        server.start();
        return server;
    }

    private static void handle(HttpExchange exchange, AtomicReference<String> requestBody, String responseBody) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
