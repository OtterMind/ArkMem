package io.arkmem.memory;

import java.util.Map;

public record ExtractedMemory(String text, Map<String, Object> metadata) {
}
