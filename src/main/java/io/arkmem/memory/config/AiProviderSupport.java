package io.arkmem.memory.config;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AiProviderSupport {

    public static final String OPENAI_DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String BAILIAN_DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    public static final String OPENAI_DEFAULT_LLM_MODEL = "gpt-4.1-nano-2025-04-14";
    public static final String BAILIAN_DEFAULT_LLM_MODEL = "qwen3.5-plus";
    public static final String OPENAI_DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";
    public static final String BAILIAN_DEFAULT_EMBEDDING_MODEL = "text-embedding-v4";

    private static final Set<String> REMOTE_PROVIDERS = Set.of(
            "openai",
            "openai-compatible",
            "aliyun-bailian",
            "bailian",
            "dashscope"
    );
    private static final Set<String> BAILIAN_PROVIDERS = Set.of("aliyun-bailian", "bailian", "dashscope");
    private static final List<String> CONFIGURABLE_PROVIDERS = List.of(
            "auto",
            "openai",
            "openai-compatible",
            "aliyun-bailian",
            "local"
    );

    private AiProviderSupport() {
    }

    public static List<String> configurableProviders() {
        return CONFIGURABLE_PROVIDERS;
    }

    public static boolean shouldUseRemoteProvider(String provider, String apiKey) {
        String normalizedProvider = normalizeProvider(provider);
        boolean hasApiKey = apiKey != null && !apiKey.isBlank();
        return REMOTE_PROVIDERS.contains(normalizedProvider) || ("auto".equals(normalizedProvider) && hasApiKey);
    }

    public static String resolveEffectiveProvider(String provider, String openAiApiKey, String dashscopeApiKey) {
        String normalizedProvider = normalizeProvider(provider);
        if (!"auto".equals(normalizedProvider)) {
            return normalizedProvider;
        }
        if (hasText(dashscopeApiKey)) {
            return "aliyun-bailian";
        }
        if (hasText(openAiApiKey)) {
            return "openai";
        }
        return normalizedProvider;
    }

    public static String resolveApiKey(
            String provider,
            String configuredApiKey,
            String openAiApiKey,
            String dashscopeApiKey
    ) {
        String effectiveProvider = resolveEffectiveProvider(provider, openAiApiKey, dashscopeApiKey);
        if (isBailianProvider(effectiveProvider)) {
            if (hasText(configuredApiKey)) {
                return configuredApiKey.trim();
            }
            return trimToEmpty(dashscopeApiKey);
        }
        if (hasText(configuredApiKey)) {
            return configuredApiKey.trim();
        }
        if ("openai".equals(effectiveProvider) || "openai-compatible".equals(effectiveProvider)) {
            return trimToEmpty(openAiApiKey);
        }
        return "";
    }

    public static String resolveBaseUrl(String provider, String configuredBaseUrl) {
        String baseUrl = normalizeBaseUrl(configuredBaseUrl);
        if (isBailianProvider(provider) && OPENAI_DEFAULT_BASE_URL.equals(baseUrl)) {
            return BAILIAN_DEFAULT_BASE_URL;
        }
        return baseUrl;
    }

    public static String resolveLlmModel(String provider, String configuredModel) {
        String model = normalizeModel(configuredModel, OPENAI_DEFAULT_LLM_MODEL);
        if (isBailianProvider(provider) && OPENAI_DEFAULT_LLM_MODEL.equals(model)) {
            return BAILIAN_DEFAULT_LLM_MODEL;
        }
        return model;
    }

    public static String resolveEmbeddingModel(String provider, String configuredModel) {
        String model = normalizeModel(configuredModel, OPENAI_DEFAULT_EMBEDDING_MODEL);
        if (isBailianProvider(provider) && OPENAI_DEFAULT_EMBEDDING_MODEL.equals(model)) {
            return BAILIAN_DEFAULT_EMBEDDING_MODEL;
        }
        return model;
    }

    private static boolean isBailianProvider(String provider) {
        return BAILIAN_PROVIDERS.contains(normalizeProvider(provider));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "auto";
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return OPENAI_DEFAULT_BASE_URL;
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String normalizeModel(String model, String defaultModel) {
        if (model == null || model.isBlank()) {
            return defaultModel;
        }
        return model.trim();
    }
}
