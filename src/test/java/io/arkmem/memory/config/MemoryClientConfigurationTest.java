package io.arkmem.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.arkmem.memory.embedding.EmbeddingClient;
import io.arkmem.memory.embedding.LocalHashEmbeddingClient;
import io.arkmem.memory.embedding.OpenAiEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryClientConfigurationTest {

    private final MemoryClientConfiguration configuration = new MemoryClientConfiguration();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final MockEnvironment environment = new MockEnvironment();

    @Test
    void usesLocalEmbeddingClientWhenAutoProviderHasNoApiKey() {
        ArkMemProperties properties = new ArkMemProperties();
        properties.getEmbedding().setProvider("auto");
        properties.getEmbedding().setApiKey("");

        EmbeddingClient client = configuration.embeddingClient(properties, objectMapper, httpClient, environment);

        assertThat(client).isInstanceOf(LocalHashEmbeddingClient.class);
    }

    @Test
    void usesRemoteEmbeddingClientWhenAutoProviderHasApiKey() {
        ArkMemProperties properties = new ArkMemProperties();
        properties.getEmbedding().setProvider("auto");
        properties.getEmbedding().setApiKey("sk-test");

        EmbeddingClient client = configuration.embeddingClient(properties, objectMapper, httpClient, environment);

        assertThat(client).isInstanceOf(OpenAiEmbeddingClient.class);
    }

    @Test
    void usesRemoteEmbeddingClientWhenDashscopeKeyIsPresentWithAutoProvider() {
        ArkMemProperties properties = new ArkMemProperties();
        properties.getEmbedding().setProvider("auto");
        properties.getEmbedding().setApiKey("");
        environment.setProperty("DASHSCOPE_API_KEY", "sk-dashscope");

        EmbeddingClient client = configuration.embeddingClient(properties, objectMapper, httpClient, environment);

        assertThat(client).isInstanceOf(OpenAiEmbeddingClient.class);
    }
}
