package io.arkmem.memory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "arkmem")
public class ArkMemProperties {

    private final Storage storage = new Storage();
    private final Llm llm = new Llm();
    private final Embedding embedding = new Embedding();
    private final Memory memory = new Memory();
    private final Prompt prompt = new Prompt();
    private final Api api = new Api();

    public Storage getStorage() {
        return storage;
    }

    public Llm getLlm() {
        return llm;
    }

    public Embedding getEmbedding() {
        return embedding;
    }

    public Memory getMemory() {
        return memory;
    }

    public Prompt getPrompt() {
        return prompt;
    }

    public Api getApi() {
        return api;
    }

    public static class Storage {
        private String provider = "postgres";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }

    public static class Llm {
        private String provider = "auto";
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "gpt-4.1-nano-2025-04-14";
        private double temperature = 0.2d;
        private int timeoutSeconds = 30;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Embedding {
        private String provider = "auto";
        private String apiKey;
        private String baseUrl = "https://api.openai.com/v1";
        private String model = "text-embedding-3-small";
        private Integer dimensions;
        private int localDimensions = 384;
        private int timeoutSeconds = 30;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public Integer getDimensions() {
            return dimensions;
        }

        public void setDimensions(Integer dimensions) {
            this.dimensions = dimensions;
        }

        public int getLocalDimensions() {
            return localDimensions;
        }

        public void setLocalDimensions(int localDimensions) {
            this.localDimensions = localDimensions;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    public static class Memory {
        private int defaultTopK = 10;
        private double defaultThreshold = 0.0d;
        private double duplicateThreshold = 0.985d;
        private boolean fallbackExtractionEnabled = true;

        public int getDefaultTopK() {
            return defaultTopK;
        }

        public void setDefaultTopK(int defaultTopK) {
            this.defaultTopK = defaultTopK;
        }

        public double getDefaultThreshold() {
            return defaultThreshold;
        }

        public void setDefaultThreshold(double defaultThreshold) {
            this.defaultThreshold = defaultThreshold;
        }

        public double getDuplicateThreshold() {
            return duplicateThreshold;
        }

        public void setDuplicateThreshold(double duplicateThreshold) {
            this.duplicateThreshold = duplicateThreshold;
        }

        public boolean isFallbackExtractionEnabled() {
            return fallbackExtractionEnabled;
        }

        public void setFallbackExtractionEnabled(boolean fallbackExtractionEnabled) {
            this.fallbackExtractionEnabled = fallbackExtractionEnabled;
        }
    }

    public static class Prompt {
        private String language = "en";

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }
    }

    public static class Api {
        private String internalToken = "";

        public String getInternalToken() {
            return internalToken;
        }

        public void setInternalToken(String internalToken) {
            this.internalToken = internalToken;
        }
    }

}
