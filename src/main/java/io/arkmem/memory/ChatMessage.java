package io.arkmem.memory;

public record ChatMessage(String role, String content, String name) {

    public boolean isSystem() {
        return "system".equalsIgnoreCase(role);
    }
}
