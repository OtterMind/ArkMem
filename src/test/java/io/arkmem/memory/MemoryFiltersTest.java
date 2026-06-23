package io.arkmem.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryFiltersTest {

    @Test
    void matchesAdvancedMetadataFilters() {
        MemoryRecord record = record(Map.of(
                "user_id", "user-1",
                "source", "chat",
                "topic", "Spring Boot memory",
                "priority", 8,
                "status", "active",
                "tag", "java"
        ));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("source", Map.of("eq", "chat"));
        filters.put("topic", Map.of("icontains", "spring"));
        filters.put("priority", Map.of("gte", 5, "lt", 10));
        filters.put("tag", Map.of("in", List.of("java", "postgres")));

        assertThat(MemoryFilters.matches(record, new MemoryFilter("user-1", null, null, filters))).isTrue();
    }

    @Test
    void matchesLogicalFilters() {
        MemoryRecord record = record(Map.of(
                "user_id", "user-1",
                "source", "chat",
                "topic", "Spring Boot memory",
                "status", "active"
        ));

        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("OR", List.of(
                Map.of("topic", Map.of("contains", "Postgres")),
                Map.of("topic", Map.of("contains", "Spring"))
        ));
        filters.put("NOT", List.of(Map.of("status", Map.of("eq", "archived"))));

        assertThat(MemoryFilters.matches(record, new MemoryFilter("user-1", null, null, filters))).isTrue();
    }

    @Test
    void supportsWildcardNeAndNin() {
        MemoryRecord record = record(Map.of(
                "user_id", "user-1",
                "source", "chat",
                "status", "active"
        ));

        assertThat(MemoryFilters.matches(record, new MemoryFilter(null, null, null, Map.of("source", "*"))))
                .isTrue();
        assertThat(MemoryFilters.matches(record, new MemoryFilter(null, null, null, Map.of("status", Map.of("ne", "archived")))))
                .isTrue();
        assertThat(MemoryFilters.matches(record, new MemoryFilter(null, null, null, Map.of("status", Map.of("nin", List.of("deleted", "archived"))))))
                .isTrue();
    }

    @Test
    void supportsMissingAgentAndRunScopeFilter() {
        Map<String, Object> userLevelFilter = Map.of(
                "NOT", List.of(
                        Map.of("agent_id", "*"),
                        Map.of("run_id", "*")
                )
        );

        assertThat(MemoryFilters.matches(
                record(Map.of("user_id", "user-1")),
                new MemoryFilter("user-1", null, null, userLevelFilter)
        )).isTrue();
        assertThat(MemoryFilters.matches(
                record(Map.of("user_id", "user-1", "agent_id", "agent-1", "run_id", "run-1")),
                new MemoryFilter("user-1", null, null, userLevelFilter)
        )).isFalse();
    }

    @Test
    void rejectsUnsupportedOperators() {
        assertThatThrownBy(() -> MemoryFilters.matches(record(Map.of("source", "chat")), new MemoryFilter(
                null,
                null,
                null,
                Map.of("source", Map.of("regex", "chat"))
        ))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unsupported metadata filter operator");
    }

    private static MemoryRecord record(Map<String, Object> metadata) {
        return new MemoryRecord(
                "memory-1",
                "User works with Spring Boot.",
                metadata,
                List.of(1.0d, 0.0d),
                Instant.parse("2026-05-22T00:00:00Z"),
                Instant.parse("2026-05-22T00:00:00Z"),
                false
        );
    }
}
