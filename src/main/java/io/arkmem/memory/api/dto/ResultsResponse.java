package io.arkmem.memory.api.dto;

import java.util.List;

public record ResultsResponse<T>(List<T> results) {
}
