package io.arkmem.memory.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiProviderSupportTest {

    @Test
    void resolvesBailianDefaultsWhenOpenAiDefaultsAreStillConfigured() {
        assertThat(AiProviderSupport.resolveBaseUrl("aliyun-bailian", AiProviderSupport.OPENAI_DEFAULT_BASE_URL))
                .isEqualTo(AiProviderSupport.BAILIAN_DEFAULT_BASE_URL);
        assertThat(AiProviderSupport.resolveLlmModel("aliyun-bailian", AiProviderSupport.OPENAI_DEFAULT_LLM_MODEL))
                .isEqualTo(AiProviderSupport.BAILIAN_DEFAULT_LLM_MODEL);
        assertThat(AiProviderSupport.resolveEmbeddingModel("aliyun-bailian", AiProviderSupport.OPENAI_DEFAULT_EMBEDDING_MODEL))
                .isEqualTo(AiProviderSupport.BAILIAN_DEFAULT_EMBEDDING_MODEL);
    }

    @Test
    void preservesExplicitBailianOverrides() {
        assertThat(AiProviderSupport.resolveBaseUrl("aliyun-bailian", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1/"))
                .isEqualTo("https://dashscope-intl.aliyuncs.com/compatible-mode/v1");
        assertThat(AiProviderSupport.resolveLlmModel("aliyun-bailian", "qwen-max"))
                .isEqualTo("qwen-max");
        assertThat(AiProviderSupport.resolveEmbeddingModel("aliyun-bailian", "text-embedding-v3"))
                .isEqualTo("text-embedding-v3");
    }

    @Test
    void treatsBailianAliasesAsRemoteProviders() {
        assertThat(AiProviderSupport.shouldUseRemoteProvider("aliyun-bailian", null)).isTrue();
        assertThat(AiProviderSupport.shouldUseRemoteProvider("bailian", null)).isTrue();
        assertThat(AiProviderSupport.shouldUseRemoteProvider("dashscope", null)).isTrue();
        assertThat(AiProviderSupport.shouldUseRemoteProvider("local", "sk-test")).isFalse();
        assertThat(AiProviderSupport.shouldUseRemoteProvider("auto", "sk-test")).isTrue();
    }

    @Test
    void infersProviderAndApiKeyFromDashscopeEnvironmentWhenAuto() {
        assertThat(AiProviderSupport.resolveEffectiveProvider("auto", null, "sk-dashscope"))
                .isEqualTo("aliyun-bailian");
        assertThat(AiProviderSupport.resolveApiKey("auto", "", null, "sk-dashscope"))
                .isEqualTo("sk-dashscope");
        assertThat(AiProviderSupport.resolveBaseUrl("aliyun-bailian", AiProviderSupport.OPENAI_DEFAULT_BASE_URL))
                .isEqualTo(AiProviderSupport.BAILIAN_DEFAULT_BASE_URL);
    }

    @Test
    void prefersDashscopeOverOpenAiWhenAutoHasBothKeys() {
        assertThat(AiProviderSupport.resolveEffectiveProvider("auto", "sk-openai", "sk-dashscope"))
                .isEqualTo("aliyun-bailian");
        assertThat(AiProviderSupport.resolveApiKey("auto", "", "sk-openai", "sk-dashscope"))
                .isEqualTo("sk-dashscope");
    }

    @Test
    void usesConfiguredApiKeyForExplicitBailianProvider() {
        assertThat(AiProviderSupport.resolveEffectiveProvider("aliyun-bailian", "sk-openai", "sk-dashscope"))
                .isEqualTo("aliyun-bailian");
        assertThat(AiProviderSupport.resolveApiKey("aliyun-bailian", "sk-configured", "sk-openai", "sk-dashscope"))
                .isEqualTo("sk-configured");
    }

    @Test
    void fallsBackToDashscopeApiKeyForBailianWhenConfiguredKeyIsMissing() {
        assertThat(AiProviderSupport.resolveApiKey("aliyun-bailian", "", "sk-openai", "sk-dashscope"))
                .isEqualTo("sk-dashscope");
    }

    @Test
    void doesNotUseOpenAiApiKeyForBailianWhenDashscopeAndConfiguredKeysAreMissing() {
        assertThat(AiProviderSupport.resolveApiKey("aliyun-bailian", "sk-configured", "sk-openai", null))
                .isEqualTo("sk-configured");
        assertThat(AiProviderSupport.resolveApiKey("aliyun-bailian", "", "sk-openai", null))
                .isEmpty();
    }
}
