package io.arkmem.memory.llm;

import io.arkmem.memory.ChatMessage;
import io.arkmem.memory.ExtractedMemory;
import io.arkmem.memory.MemoryRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MemoryPromptProvider {

    public static final String ENGLISH = "en";
    public static final String CHINESE = "zh";

    private final String language;

    public MemoryPromptProvider(String language) {
        this.language = normalizeLanguage(language);
    }

    public String language() {
        return language;
    }

    public String extractionSystemPrompt(MemoryExtractionRequest request) {
        if ("procedural_memory".equals(request.memoryType())) {
            return template("procedural_memory_system");
        }
        if (request.agentScoped()) {
            return template("agent_memory_extraction_system");
        }
        return template("user_memory_extraction_system");
    }

    public String extractionUserPrompt(MemoryExtractionRequest request) {
        return render(template("fact_extraction_user"), Map.of(
                "today", LocalDate.now().toString(),
                "custom_instructions", optionalBlock(request.customPrompt()),
                "existing_memories", renderExistingMemories(request.existingMemories()),
                "new_messages", renderMessages(request.messages())
        ));
    }

    public String decisionSystemPrompt() {
        return template("memory_decision_system");
    }

    public String decisionUserPrompt(MemoryDecisionRequest request) {
        return render(template("memory_decision_user"), Map.of(
                "custom_instructions", optionalBlock(request.customPrompt()),
                "existing_memories", renderExistingMemories(request.existingMemories()),
                "retrieved_facts", renderExtractedMemories(request.extractedMemories())
        ));
    }

    public String instructionGenerationSystemPrompt() {
        return template("instruction_generation_system");
    }

    public String instructionGenerationUserPrompt(String useCase) {
        String normalizedUseCase = useCase == null || useCase.isBlank() ? "the application" : useCase.trim();
        return render(template("instruction_generation_user"), Map.of("use_case", normalizedUseCase));
    }

    private String template(String name) {
        String path = "/prompts/" + language + "/" + name + ".txt";
        try (var stream = MemoryPromptProvider.class.getResourceAsStream(path)) {
            if (stream != null) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read prompt template " + path, e);
        }

        if (!ENGLISH.equals(language)) {
            return new MemoryPromptProvider(ENGLISH).template(name);
        }
        throw new IllegalStateException("Prompt template not found: " + path);
    }

    private String render(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }

    private String renderMessages(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.isSystem()) {
                continue;
            }
            builder.append("- role: ").append(safe(message.role())).append('\n');
            if (message.name() != null && !message.name().isBlank()) {
                builder.append("  name: ").append(safe(message.name())).append('\n');
            }
            builder.append("  content: ").append(safe(message.content())).append('\n');
        }
        return builder.length() == 0 ? "[]" : builder.toString().trim();
    }

    private String renderExistingMemories(List<MemoryRecord> memories) {
        if (memories == null || memories.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[\n");
        for (MemoryRecord memory : memories.stream().limit(30).toList()) {
            builder.append("  {\"id\":\"")
                    .append(escape(memory.getId()))
                    .append("\",\"text\":\"")
                    .append(escape(memory.getMemory()))
                    .append("\"},\n");
        }
        if (builder.charAt(builder.length() - 2) == ',') {
            builder.delete(builder.length() - 2, builder.length() - 1);
        }
        return builder.append(']').toString();
    }

    private String renderExtractedMemories(List<ExtractedMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder("[\n");
        for (ExtractedMemory memory : memories) {
            builder.append("  \"").append(escape(memory.text())).append("\",\n");
        }
        if (builder.charAt(builder.length() - 2) == ',') {
            builder.delete(builder.length() - 2, builder.length() - 1);
        }
        return builder.append(']').toString();
    }

    private String optionalBlock(String value) {
        return value == null || value.isBlank() ? "None." : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String escape(String value) {
        return safe(value)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return ENGLISH;
        }
        String normalized = language.trim().toLowerCase(Locale.ROOT);
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("english", ENGLISH);
        aliases.put("en-us", ENGLISH);
        aliases.put("en_us", ENGLISH);
        aliases.put("chinese", CHINESE);
        aliases.put("cn", CHINESE);
        aliases.put("zh-cn", CHINESE);
        aliases.put("zh_cn", CHINESE);
        aliases.put("中文", CHINESE);
        String resolved = aliases.getOrDefault(normalized, normalized);
        if (ENGLISH.equals(resolved) || CHINESE.equals(resolved)) {
            return resolved;
        }
        return ENGLISH;
    }
}
