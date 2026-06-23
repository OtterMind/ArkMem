package io.arkmem.memory;

import java.util.Locale;

public enum SearchMode {
    SEMANTIC("semantic"),
    KEYWORD("keyword"),
    HYBRID("hybrid");

    private final String value;

    SearchMode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public boolean requiresEmbedding() {
        return this == SEMANTIC || this == HYBRID;
    }

    public static SearchMode from(String value) {
        if (value == null || value.isBlank()) {
            return SEMANTIC;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (SearchMode mode : values()) {
            if (mode.value.equals(normalized)) {
                return mode;
            }
        }
        throw new BadRequestException("search_mode must be one of semantic, keyword, or hybrid");
    }
}
