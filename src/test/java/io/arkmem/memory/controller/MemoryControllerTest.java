package io.arkmem.memory.controller;

import com.jayway.jsonpath.JsonPath;
import io.arkmem.memory.InMemoryMemoryRepository;
import io.arkmem.memory.MemoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
)
@AutoConfigureMockMvc
class MemoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("arkmem.storage.provider", () -> "memory");
        registry.add("arkmem.embedding.provider", () -> "local");
        registry.add("arkmem.llm.provider", () -> "local");
        registry.add("arkmem.embedding.local-dimensions", () -> "128");
    }

    @TestConfiguration
    static class TestConfig {

        @Bean
        @Primary
        MemoryRepository memoryRepository() {
            return new InMemoryMemoryRepository();
        }
    }

    @Test
    void exposesSupportedProviderNames() throws Exception {
        mockMvc.perform(get("/configure/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.llm", hasItems("openai", "openai-compatible", "aliyun-bailian", "local")))
                .andExpect(jsonPath("$.embedder", hasItems("openai", "openai-compatible", "aliyun-bailian", "local")))
                .andExpect(jsonPath("$.prompt_language", hasItems("en", "zh")));
    }

    @Test
    void exposesOperationalEndpointsAndOpenApi() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/api/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ready"))
                .andExpect(jsonPath("$.database").value("ready"));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("ArkMem API"));
    }

    @Test
    void exposesStructuredErrorResponses() throws Exception {
        mockMvc.perform(post("/search")
                        .contentType("application/json")
                        .header("X-Request-Id", "request-123")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.request_id").value("request-123"))
                .andExpect(jsonPath("$.path").value("/search"));
    }

    @Test
    void exposesMemoryCrudAndSearchApis() throws Exception {
        mockMvc.perform(post("/reset"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/memories")
                        .contentType("application/json")
                        .content("""
                                {
                                  "messages": [
                                    {"role": "user", "content": "I prefer concise technical summaries."}
                                  ],
                                  "user_id": "user-1",
                                  "metadata": {"source": "mvc-test"},
                                  "infer": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].id", notNullValue()))
                .andExpect(jsonPath("$.results[0].event").value("ADD"));

        mockMvc.perform(post("/search")
                        .contentType("application/json")
                        .content("""
                                {
                                  "query": "concise summaries",
                                  "user_id": "user-1",
                                  "filters": {
                                    "source": {"eq": "mvc-test"},
                                    "OR": [
                                      {"source": {"contains": "mvc"}},
                                      {"source": {"eq": "manual"}}
                                    ]
                                  },
                                  "search_mode": "hybrid",
                                  "top_k": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].memory", containsString("concise technical summaries")))
                .andExpect(jsonPath("$.results[0].search_mode").value("hybrid"))
                .andExpect(jsonPath("$.results[0].semantic_score", notNullValue()))
                .andExpect(jsonPath("$.results[0].keyword_score", notNullValue()));

        mockMvc.perform(get("/memories?user_id=user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].user_id").value("user-1"));

        mockMvc.perform(delete("/memories?user_id=user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Deleted 1 memories"));
    }

    @Test
    void getsAgentMemoriesAcrossRunsByUserAndAgent() throws Exception {
        mockMvc.perform(post("/reset"))
                .andExpect(status().isOk());

        createMemory("psxjoy", "chat_agent", "run-1", "Memory from the first run.");
        createMemory("psxjoy", "chat_agent", "run-2", "Memory from the second run.");
        createMemory("psxjoy", "other_agent", "run-3", "Memory from another agent.");
        createMemory("other-user", "chat_agent", "run-4", "Memory from another user.");

        mockMvc.perform(get("/memories")
                        .param("user_id", "psxjoy")
                        .param("agent_id", "chat_agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[*].user_id", hasItems("psxjoy")))
                .andExpect(jsonPath("$.results[*].agent_id", hasItems("chat_agent")))
                .andExpect(jsonPath("$.results[*].run_id", hasItems("run-1", "run-2")));
    }

    @Test
    void getsExactMemoriesBySupportedScopeCombinations() throws Exception {
        mockMvc.perform(post("/reset"))
                .andExpect(status().isOk());

        createUserMemory("psxjoy", "User-level memory.");
        createMemory("psxjoy", "chat_agent", "run-1", "Memory from the first run.");
        createMemory("psxjoy", "chat_agent", "run-2", "Memory from the second run.");
        createMemory("psxjoy", "other_agent", "run-3", "Memory from another agent.");
        createMemory("other-user", "chat_agent", "run-4", "Memory from another user.");

        mockMvc.perform(get("/exact/memories")
                        .param("user_id", "psxjoy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].memory").value("User-level memory."))
                .andExpect(jsonPath("$.results[0].user_id").value("psxjoy"))
                .andExpect(jsonPath("$.results[0].agent_id").doesNotExist())
                .andExpect(jsonPath("$.results[0].run_id").doesNotExist());

        mockMvc.perform(get("/exact/memories")
                        .param("user_id", "psxjoy")
                        .param("agent_id", "chat_agent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(2)))
                .andExpect(jsonPath("$.results[*].run_id", hasItems("run-1", "run-2")));

        mockMvc.perform(get("/exact/memories")
                        .param("user_id", "psxjoy")
                        .param("agent_id", "chat_agent")
                        .param("run_id", "run-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].user_id").value("psxjoy"))
                .andExpect(jsonPath("$.results[0].agent_id").value("chat_agent"))
                .andExpect(jsonPath("$.results[0].run_id").value("run-2"));
    }

    @Test
    void rejectsInvalidExactMemoryScope() throws Exception {
        mockMvc.perform(get("/exact/memories")
                        .param("user_id", "psxjoy")
                        .param("run_id", "run-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("agent_id is required when run_id is provided"));

        mockMvc.perform(get("/exact/memories")
                        .param("agent_id", "chat_agent"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("user_id is required"));

        mockMvc.perform(get("/exact/memories")
                        .param("user_id", "psxjoy")
                        .param("source", "manual"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Unsupported query parameter: source"));
    }

    @Test
    void queriesMemoriesByScopeMetadataAndPagination() throws Exception {
        mockMvc.perform(post("/reset"))
                .andExpect(status().isOk());

        createMemoryWithMetadata(
                "psxjoy",
                "chat-agent",
                "run-1",
                "Current rolling summary.",
                """
                        {
                          "scope": "chat_thread",
                          "slot": "rolling_summary",
                          "source": "agent-run-summary",
                          "memory_tier": "L1"
                        }
                        """
        );
        createMemoryWithMetadata(
                "psxjoy",
                "chat-agent",
                "run-2",
                "Another rolling summary.",
                """
                        {
                          "scope": "chat_thread",
                          "slot": "rolling_summary",
                          "source": "agent-run-summary",
                          "memory_tier": "L1"
                        }
                        """
        );
        createMemoryWithMetadata(
                "psxjoy",
                "chat-agent",
                "run-3",
                "Different slot.",
                """
                        {
                          "scope": "chat_thread",
                          "slot": "scratch",
                          "source": "agent-run-summary",
                          "memory_tier": "L1"
                        }
                        """
        );

        mockMvc.perform(post("/memories/query")
                        .contentType("application/json")
                        .content("""
                                {
                                  "user_id": "psxjoy",
                                  "agent_id": "chat-agent",
                                  "filters": {
                                    "scope": {"eq": "chat_thread"},
                                    "slot": {"eq": "rolling_summary"},
                                    "memory_tier": "L1"
                                  },
                                  "limit": 1,
                                  "offset": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.offset").value(1))
                .andExpect(jsonPath("$.results[0].metadata.slot").value("rolling_summary"));

        mockMvc.perform(get("/memories/query")
                        .param("user_id", "psxjoy")
                        .param("agent_id", "chat-agent")
                        .param("scope", "chat_thread")
                        .param("slot", "rolling_summary")
                        .param("memory_tier", "L1")
                        .param("limit", "1")
                        .param("offset", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.offset").value(1))
                .andExpect(jsonPath("$.results[0].metadata.slot").value("rolling_summary"));
    }

    @Test
    void queriesExactUserMemoriesWithAdditionalMetadataFilters() throws Exception {
        mockMvc.perform(post("/reset"))
                .andExpect(status().isOk());

        createUserMemory("psxjoy", "User-level memory.");
        createMemory("psxjoy", "chat-agent", "run-1", "Run-level memory.");

        mockMvc.perform(post("/memories/query")
                        .contentType("application/json")
                        .content("""
                                {
                                  "user_id": "psxjoy",
                                  "scope_mode": "exact",
                                  "filters": {
                                    "memory": {"exists": true}
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(0)));

        mockMvc.perform(post("/memories/query")
                        .contentType("application/json")
                        .content("""
                                {
                                  "user_id": "psxjoy",
                                  "scope_mode": "exact"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].memory").value("User-level memory."));

        mockMvc.perform(get("/memories/query")
                        .param("user_id", "psxjoy")
                        .param("scope_mode", "exact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(1)))
                .andExpect(jsonPath("$.results[0].memory").value("User-level memory."));
    }

    @Test
    void guardsByIdApisWithScopedUserId() throws Exception {
        mockMvc.perform(post("/reset"))
                .andExpect(status().isOk());

        String memoryId = createMemory("user-1", "chat-agent", "run-1", "Scoped memory.");

        mockMvc.perform(get("/memories/{memoryId}", memoryId)
                        .param("user_id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(memoryId))
                .andExpect(jsonPath("$.user_id").value("user-1"));

        mockMvc.perform(get("/memories/{memoryId}", memoryId)
                        .param("user_id", "other-user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(get("/memories/{memoryId}/history", memoryId)
                        .param("user_id", "other-user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(put("/memories/{memoryId}", memoryId)
                        .param("user_id", "other-user")
                        .contentType("application/json")
                        .content("""
                                {
                                  "text": "Other user update should be rejected.",
                                  "metadata": {"source": "rejected"}
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(delete("/memories/{memoryId}", memoryId)
                        .param("user_id", "other-user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(put("/memories/{memoryId}", memoryId)
                        .param("user_id", "user-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "text": "Scoped memory updated.",
                                  "metadata": {"source": "scoped-update"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(memoryId))
                .andExpect(jsonPath("$.event").value("UPDATE"));

        mockMvc.perform(get("/memories/{memoryId}/history", memoryId)
                        .param("user_id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results", hasSize(2)));

        mockMvc.perform(delete("/memories/{memoryId}", memoryId)
                        .param("user_id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Memory deleted successfully"));

        mockMvc.perform(get("/memories/{memoryId}", memoryId)
                        .param("user_id", "user-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsUnsupportedScopedByIdQueryParameter() throws Exception {
        mockMvc.perform(post("/reset"))
                .andExpect(status().isOk());

        String memoryId = createMemory("user-1", "chat-agent", "run-1", "Scoped memory.");

        mockMvc.perform(get("/memories/{memoryId}", memoryId)
                        .param("agent_id", "chat-agent"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("Unsupported query parameter: agent_id"));
    }

    private String createMemory(String userId, String agentId, String runId, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/memories")
                        .contentType("application/json")
                        .content("""
                                {
                                  "messages": [
                                    {"role": "user", "content": "%s"}
                                  ],
                                  "user_id": "%s",
                                  "agent_id": "%s",
                                  "run_id": "%s",
                                  "infer": false
                                }
                                """.formatted(content, userId, agentId, runId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].event").value("ADD"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.results[0].id");
    }

    private String createMemoryWithMetadata(
            String userId,
            String agentId,
            String runId,
            String content,
            String metadata
    ) throws Exception {
        MvcResult result = mockMvc.perform(post("/memories")
                        .contentType("application/json")
                        .content("""
                                {
                                  "messages": [
                                    {"role": "user", "content": "%s"}
                                  ],
                                  "user_id": "%s",
                                  "agent_id": "%s",
                                  "run_id": "%s",
                                  "metadata": %s,
                                  "infer": false
                                }
                                """.formatted(content, userId, agentId, runId, metadata)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].event").value("ADD"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.results[0].id");
    }

    private String createUserMemory(String userId, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/memories")
                        .contentType("application/json")
                        .content("""
                                {
                                  "messages": [
                                    {"role": "user", "content": "%s"}
                                  ],
                                  "user_id": "%s",
                                  "infer": false
                                }
                                """.formatted(content, userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].event").value("ADD"))
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.results[0].id");
    }
}
