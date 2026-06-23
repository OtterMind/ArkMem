package io.arkmem.memory;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresMemoryFilterSqlBuilderTest {

    @Test
    void buildsParameterizedSqlForAdvancedFilters() {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("source", Map.of("eq", "chat"));
        filters.put("priority", Map.of("gte", 5));
        filters.put("topic", Map.of("icontains", "Spring_%"));
        filters.put("OR", List.of(
                Map.of("tag", Map.of("in", List.of("java", "postgres"))),
                Map.of("status", Map.of("ne", "archived"))
        ));

        PostgresMemoryFilterSqlBuilder.SqlFilter sqlFilter = PostgresMemoryFilterSqlBuilder.build(
                new MemoryFilter("user-1", null, null, filters),
                true
        );

        assertThat(sqlFilter.whereClause())
                .startsWith("where deleted_at is null and")
                .contains("user_id = :filterValue0")
                .contains("metadata ->> :filterKey1 = :filterValue2")
                .contains("cast(metadata ->> :filterKey3 as numeric) >= :filterNumber4")
                .contains("lower(metadata ->> :filterKey5) like lower(:filterPattern6) escape '!'")
                .contains("metadata ->> :filterKey7 in (:filterValues8)")
                .contains("not jsonb_exists(metadata, :filterKey9)");
        assertThat(sqlFilter.parameters().getValue("filterValue0")).isEqualTo("user-1");
        assertThat(sqlFilter.parameters().getValue("filterKey5")).isEqualTo("topic");
        assertThat(sqlFilter.parameters().getValue("filterPattern6")).isEqualTo("%Spring!_!%%");
        assertThat(sqlFilter.parameters().getValue("filterValues8")).isEqualTo(List.of("java", "postgres"));
    }

    @Test
    void keepsMetadataKeysOutOfSqlText() {
        String suspiciousKey = "source' or true --";

        PostgresMemoryFilterSqlBuilder.SqlFilter sqlFilter = PostgresMemoryFilterSqlBuilder.build(
                new MemoryFilter(null, null, null, Map.of(suspiciousKey, "chat")),
                true
        );

        assertThat(sqlFilter.whereClause()).doesNotContain(suspiciousKey);
        assertThat(sqlFilter.parameters().getValue("filterKey0")).isEqualTo(suspiciousKey);
        assertThat(sqlFilter.parameters().getValue("filterValue1")).isEqualTo("chat");
    }

    @Test
    void buildsParameterizedSqlForMissingAgentAndRunScope() {
        Map<String, Object> filters = Map.of(
                "NOT", List.of(
                        Map.of("agent_id", "*"),
                        Map.of("run_id", "*")
                )
        );

        PostgresMemoryFilterSqlBuilder.SqlFilter sqlFilter = PostgresMemoryFilterSqlBuilder.build(
                new MemoryFilter("user-1", null, null, filters),
                true
        );

        assertThat(sqlFilter.whereClause())
                .startsWith("where deleted_at is null and")
                .contains("user_id = :filterValue0")
                .contains("not (agent_id is not null or run_id is not null)");
        assertThat(sqlFilter.parameters().getValue("filterValue0")).isEqualTo("user-1");
    }

    @Test
    void rejectsInvalidFilterShapes() {
        assertThatThrownBy(() -> PostgresMemoryFilterSqlBuilder.build(
                new MemoryFilter(null, null, null, Map.of("priority", Map.of("gt", "high"))),
                true
        )).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("numeric metadata filter value must be a number");

        assertThatThrownBy(() -> PostgresMemoryFilterSqlBuilder.build(
                new MemoryFilter(null, null, null, Map.of("OR", List.of("invalid"))),
                true
        )).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("OR filter requires objects");
    }
}
