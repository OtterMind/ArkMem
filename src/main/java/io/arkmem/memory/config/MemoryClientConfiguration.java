package io.arkmem.memory.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.arkmem.memory.embedding.EmbeddingClient;
import io.arkmem.memory.embedding.LocalHashEmbeddingClient;
import io.arkmem.memory.embedding.OpenAiEmbeddingClient;
import io.arkmem.memory.llm.HeuristicMemoryExtractor;
import io.arkmem.memory.llm.InstructionGenerator;
import io.arkmem.memory.llm.MemoryPromptProvider;
import io.arkmem.memory.llm.MemoryExtractor;
import io.arkmem.memory.llm.OpenAiLlmClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class MemoryClientConfiguration {

    @Bean
    public HttpClient arkMemHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Bean
    public EmbeddingClient embeddingClient(
            ArkMemProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            Environment environment
    ) {
        ArkMemProperties.Embedding embedding = properties.getEmbedding();
        String provider = resolveEffectiveProvider(embedding.getProvider(), environment);
        String apiKey = resolveApiKey(provider, embedding.getApiKey(), environment);
        if (AiProviderSupport.shouldUseRemoteProvider(provider, apiKey)) {
            return new OpenAiEmbeddingClient(
                    httpClient,
                    objectMapper,
                    apiKey,
                    AiProviderSupport.resolveBaseUrl(provider, embedding.getBaseUrl()),
                    AiProviderSupport.resolveEmbeddingModel(provider, embedding.getModel()),
                    embedding.getDimensions(),
                    Duration.ofSeconds(embedding.getTimeoutSeconds())
            );
        }
        return new LocalHashEmbeddingClient(embedding.getLocalDimensions());
    }

    @Bean
    public MemoryPromptProvider memoryPromptProvider(ArkMemProperties properties) {
        return new MemoryPromptProvider(properties.getPrompt().getLanguage());
    }

    @Bean
    public OpenAiLlmClient openAiLlmClient(
            ArkMemProperties properties,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            MemoryPromptProvider promptProvider,
            Environment environment
    ) {
        ArkMemProperties.Llm llm = properties.getLlm();
        String provider = resolveEffectiveProvider(llm.getProvider(), environment);
        String apiKey = resolveApiKey(provider, llm.getApiKey(), environment);
        return new OpenAiLlmClient(
                httpClient,
                objectMapper,
                apiKey,
                AiProviderSupport.resolveBaseUrl(provider, llm.getBaseUrl()),
                AiProviderSupport.resolveLlmModel(provider, llm.getModel()),
                llm.getTemperature(),
                Duration.ofSeconds(llm.getTimeoutSeconds()),
                promptProvider
        );
    }

    @Bean
    public MemoryExtractor memoryExtractor(
            ArkMemProperties properties,
            OpenAiLlmClient openAiLlmClient,
            MemoryPromptProvider promptProvider,
            Environment environment
    ) {
        ArkMemProperties.Llm llm = properties.getLlm();
        String provider = resolveEffectiveProvider(llm.getProvider(), environment);
        String apiKey = resolveApiKey(provider, llm.getApiKey(), environment);
        if (AiProviderSupport.shouldUseRemoteProvider(provider, apiKey)) {
            return openAiLlmClient;
        }
        return new HeuristicMemoryExtractor(promptProvider);
    }

    @Bean
    public InstructionGenerator instructionGenerator(
            ArkMemProperties properties,
            OpenAiLlmClient openAiLlmClient,
            MemoryPromptProvider promptProvider,
            Environment environment
    ) {
        ArkMemProperties.Llm llm = properties.getLlm();
        String provider = resolveEffectiveProvider(llm.getProvider(), environment);
        String apiKey = resolveApiKey(provider, llm.getApiKey(), environment);
        if (AiProviderSupport.shouldUseRemoteProvider(provider, apiKey)) {
            return openAiLlmClient;
        }
        return new HeuristicMemoryExtractor(promptProvider);
    }

    private static String resolveEffectiveProvider(String provider, Environment environment) {
        return AiProviderSupport.resolveEffectiveProvider(
                provider,
                environment.getProperty("OPENAI_API_KEY"),
                environment.getProperty("DASHSCOPE_API_KEY")
        );
    }

    private static String resolveApiKey(String provider, String configuredApiKey, Environment environment) {
        return AiProviderSupport.resolveApiKey(
                provider,
                configuredApiKey,
                environment.getProperty("OPENAI_API_KEY"),
                environment.getProperty("DASHSCOPE_API_KEY")
        );
    }
}
