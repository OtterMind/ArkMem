package io.arkmem.memory.llm;

import io.arkmem.memory.ChatMessage;
import io.arkmem.memory.ExtractedMemory;
import io.arkmem.memory.MemoryEvent;
import io.arkmem.memory.MemoryRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

public class HeuristicMemoryExtractor implements MemoryExtractor, InstructionGenerator {

    private final MemoryPromptProvider promptProvider;

    public HeuristicMemoryExtractor() {
        this(new MemoryPromptProvider("en"));
    }

    public HeuristicMemoryExtractor(MemoryPromptProvider promptProvider) {
        this.promptProvider = promptProvider;
    }

    @Override
    public List<ExtractedMemory> extractMemories(MemoryExtractionRequest request) {
        if ("procedural_memory".equals(request.memoryType())) {
            return List.of(new ExtractedMemory(renderProceduralSummary(request.messages()), new LinkedHashMap<>()));
        }
        return request.messages().stream()
                .filter(message -> shouldExtractMessage(message, request.agentScoped()))
                .filter(message -> message.content() != null && !message.content().isBlank())
                .map(message -> new ExtractedMemory(message.content().trim(), new LinkedHashMap<>()))
                .toList();
    }

    @Override
    public List<MemoryChange> planMemoryChanges(MemoryDecisionRequest request) {
        List<MemoryChange> changes = new ArrayList<>();
        for (ExtractedMemory memory : request.extractedMemories()) {
            MemoryRecord duplicate = findExactDuplicate(memory, request.existingMemories());
            if (duplicate != null) {
                changes.add(new MemoryChange(duplicate.getId(), duplicate.getMemory(), MemoryEvent.NONE, null));
            } else {
                changes.add(new MemoryChange("new", memory.text(), MemoryEvent.ADD, null));
            }
        }
        return changes;
    }

    @Override
    public InstructionSuggestion generateInstructions(String useCase) {
        String normalizedUseCase = useCase == null || useCase.isBlank() ? "the application" : useCase.trim();
        if (MemoryPromptProvider.CHINESE.equals(promptProvider.language())) {
            return new InstructionSuggestion(
                    "优先记录有助于个性化 " + normalizedUseCase + " 的稳定用户事实、偏好、计划、约束和反复出现的上下文。",
                    "我偏好简洁直接的回答，也希望你记住我的项目约束。"
            );
        }
        return new InstructionSuggestion(
                "Capture stable user facts, preferences, plans, constraints, and recurring context that help personalize " + normalizedUseCase + ".",
                "I prefer concise direct answers and want you to remember my project constraints."
        );
    }

    private boolean shouldExtractMessage(ChatMessage message, boolean agentScoped) {
        if (message.isSystem()) {
            return false;
        }
        if (agentScoped) {
            return "assistant".equalsIgnoreCase(message.role());
        }
        return !"assistant".equalsIgnoreCase(message.role());
    }

    private MemoryRecord findExactDuplicate(ExtractedMemory memory, List<MemoryRecord> existingMemories) {
        String normalized = normalize(memory.text());
        return existingMemories.stream()
                .filter(existing -> normalize(existing.getMemory()).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private String renderProceduralSummary(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder("Procedural memory summary:");
        for (ChatMessage message : messages) {
            if (!message.isSystem() && message.content() != null && !message.content().isBlank()) {
                builder.append("\n").append(message.role()).append(": ").append(message.content().trim());
            }
        }
        return builder.toString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
