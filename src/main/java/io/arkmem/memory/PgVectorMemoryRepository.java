package io.arkmem.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "arkmem.storage", name = "provider", havingValue = "postgres", matchIfMissing = true)
public class PgVectorMemoryRepository implements MemoryRepository {

    private static final TypeReference<Map<String, Object>> METADATA_TYPE = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PgVectorMemoryRepository(
            @Qualifier("memoryNamedParameterJdbcTemplate") NamedParameterJdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public MemoryRecord save(MemoryRecord record) {
        String sql = """
                insert into arkmem_memories (
                  id, memory, metadata, embedding, created_at, updated_at, deleted_at
                ) values (
                  cast(:id as uuid),
                  :memory,
                  cast(:metadata as jsonb),
                  cast(:embedding as vector),
                  :createdAt,
                  :updatedAt,
                  :deletedAt
                )
                on conflict (id) do update set
                  memory = excluded.memory,
                  metadata = excluded.metadata,
                  embedding = excluded.embedding,
                  updated_at = excluded.updated_at,
                  deleted_at = excluded.deleted_at
                """;
        jdbcTemplate.update(sql, new MapSqlParameterSource()
                .addValue("id", record.getId())
                .addValue("memory", record.getMemory())
                .addValue("metadata", writeMetadata(record.getMetadata()))
                .addValue("embedding", toVectorLiteral(record.getEmbedding()))
                .addValue("createdAt", Timestamp.from(record.getCreatedAt()))
                .addValue("updatedAt", Timestamp.from(record.getUpdatedAt()))
                .addValue("deletedAt", record.isDeleted() ? Timestamp.from(record.getUpdatedAt()) : null));
        return MemoryRecord.copyOf(record);
    }

    @Override
    public Optional<MemoryRecord> findById(String id) {
        Optional<UUID> memoryId = parseUuid(id);
        if (memoryId.isEmpty()) {
            return Optional.empty();
        }
        List<MemoryRecord> records = jdbcTemplate.query(
                """
                        select id, memory, metadata, created_at, updated_at, deleted_at
                        from arkmem_memories
                        where id = :id
                        """,
                new MapSqlParameterSource("id", memoryId.get()),
                this::mapMemoryRecord
        );
        return records.stream().findFirst();
    }

    @Override
    public List<MemoryRecord> findAll(MemoryFilter filter) {
        SqlFilter sqlFilter = buildFilter(filter, true);
        return jdbcTemplate.query(
                """
                        select id, memory, metadata, created_at, updated_at, deleted_at
                        from arkmem_memories
                        %s
                        order by created_at desc
                        """.formatted(sqlFilter.whereClause()),
                sqlFilter.parameters(),
                this::mapMemoryRecord
        );
    }

    @Override
    public List<MemorySearchResult> search(
            String query,
            List<Double> embedding,
            MemoryFilter filter,
            int topK,
            double threshold,
            SearchMode searchMode
    ) {
        return switch (searchMode) {
            case SEMANTIC -> searchSemantic(embedding, filter, topK, threshold, searchMode);
            case KEYWORD -> searchKeyword(query, filter, topK, threshold, searchMode);
            case HYBRID -> searchHybrid(query, embedding, filter, topK, threshold, searchMode);
        };
    }

    private List<MemorySearchResult> searchSemantic(
            List<Double> embedding,
            MemoryFilter filter,
            int topK,
            double threshold,
            SearchMode searchMode
    ) {
        SqlFilter sqlFilter = buildFilter(filter, true);
        MapSqlParameterSource parameters = sqlFilter.parameters()
                .addValue("embedding", toVectorLiteral(embedding))
                .addValue("topK", topK)
                .addValue("threshold", threshold)
                .addValue("searchMode", searchMode.value());
        return jdbcTemplate.query(
                """
                        select
                          id,
                          memory,
                          metadata,
                          created_at,
                          updated_at,
                          greatest(cast(0.0 as double precision), least(cast(1.0 as double precision), 1.0 - (embedding <=> cast(:embedding as vector)))) as score,
                          greatest(cast(0.0 as double precision), least(cast(1.0 as double precision), 1.0 - (embedding <=> cast(:embedding as vector)))) as semantic_score,
                          cast(null as double precision) as keyword_score,
                          :searchMode as search_mode
                        from arkmem_memories
                        %s
                          and greatest(cast(0.0 as double precision), least(cast(1.0 as double precision), 1.0 - (embedding <=> cast(:embedding as vector)))) >= :threshold
                        order by embedding <=> cast(:embedding as vector)
                        limit :topK
                        """.formatted(sqlFilter.whereClause()),
                parameters,
                this::mapSearchResult
        );
    }

    private List<MemorySearchResult> searchKeyword(
            String query,
            MemoryFilter filter,
            int topK,
            double threshold,
            SearchMode searchMode
    ) {
        SqlFilter sqlFilter = buildFilter(filter, true);
        MapSqlParameterSource parameters = sqlFilter.parameters()
                .addValue("query", query)
                .addValue("topK", topK)
                .addValue("threshold", threshold)
                .addValue("searchMode", searchMode.value());
        return jdbcTemplate.query(
                """
                        with query_input as (
                          select websearch_to_tsquery(cast('simple' as regconfig), :query) as keyword_query
                        ),
                        ranked as (
                          select
                            id,
                            memory,
                            metadata,
                            created_at,
                            updated_at,
                            greatest(cast(0.0 as double precision), least(cast(1.0 as double precision), ts_rank_cd(keyword_search_vector, query_input.keyword_query) * cast(8.0 as double precision))) as keyword_score
                          from arkmem_memories, query_input
                          %s
                            and keyword_search_vector @@ query_input.keyword_query
                        )
                        select
                          id,
                          memory,
                          metadata,
                          created_at,
                          updated_at,
                          keyword_score as score,
                          cast(null as double precision) as semantic_score,
                          keyword_score,
                          :searchMode as search_mode
                        from ranked
                        where keyword_score >= :threshold
                        order by keyword_score desc, updated_at desc
                        limit :topK
                        """.formatted(sqlFilter.whereClause()),
                parameters,
                this::mapSearchResult
        );
    }

    private List<MemorySearchResult> searchHybrid(
            String query,
            List<Double> embedding,
            MemoryFilter filter,
            int topK,
            double threshold,
            SearchMode searchMode
    ) {
        SqlFilter sqlFilter = buildFilter(filter, true);
        int candidateLimit = Math.max(topK * 4, 60);
        MapSqlParameterSource parameters = sqlFilter.parameters()
                .addValue("query", query)
                .addValue("embedding", toVectorLiteral(embedding))
                .addValue("candidateLimit", candidateLimit)
                .addValue("topK", topK)
                .addValue("threshold", threshold)
                .addValue("searchMode", searchMode.value());
        return jdbcTemplate.query(
                """
                        with query_input as (
                          select
                            cast(:embedding as vector) as query_embedding,
                            websearch_to_tsquery(cast('simple' as regconfig), :query) as keyword_query
                        ),
                        semantic_candidates as (
                          select id
                          from arkmem_memories, query_input
                          %s
                          order by embedding <=> query_input.query_embedding
                          limit :candidateLimit
                        ),
                        keyword_candidates as (
                          select id
                          from arkmem_memories, query_input
                          %s
                            and keyword_search_vector @@ query_input.keyword_query
                          order by ts_rank_cd(keyword_search_vector, query_input.keyword_query) desc
                          limit :candidateLimit
                        ),
                        candidate_ids as (
                          select id from semantic_candidates
                          union
                          select id from keyword_candidates
                        ),
                        ranked as (
                          select
                            arkmem_memories.id,
                            arkmem_memories.memory,
                            arkmem_memories.metadata,
                            arkmem_memories.created_at,
                            arkmem_memories.updated_at,
                            greatest(cast(0.0 as double precision), least(cast(1.0 as double precision), 1.0 - (arkmem_memories.embedding <=> query_input.query_embedding))) as semantic_score,
                            case
                              when arkmem_memories.keyword_search_vector @@ query_input.keyword_query
                                then greatest(cast(0.0 as double precision), least(cast(1.0 as double precision), ts_rank_cd(arkmem_memories.keyword_search_vector, query_input.keyword_query) * cast(8.0 as double precision)))
                              else 0.0
                            end as keyword_score
                          from candidate_ids
                          join arkmem_memories on arkmem_memories.id = candidate_ids.id
                          cross join query_input
                        ),
                        scored as (
                          select
                            id,
                            memory,
                            metadata,
                            created_at,
                            updated_at,
                            semantic_score,
                            keyword_score,
                            (semantic_score * 0.75 + keyword_score * 0.25) as score
                          from ranked
                        )
                        select
                          id,
                          memory,
                          metadata,
                          created_at,
                          updated_at,
                          score,
                          semantic_score,
                          keyword_score,
                          :searchMode as search_mode
                        from scored
                        where score >= :threshold
                        order by score desc, semantic_score desc, updated_at desc
                        limit :topK
                        """.formatted(sqlFilter.whereClause(), sqlFilter.whereClause()),
                parameters,
                this::mapSearchResult
        );
    }

    @Override
    public void markDeleted(String id) {
        Optional<UUID> memoryId = parseUuid(id);
        if (memoryId.isEmpty()) {
            return;
        }
        jdbcTemplate.update(
                """
                        update arkmem_memories
                        set deleted_at = now(), updated_at = now()
                        where id = :id and deleted_at is null
                        """,
                new MapSqlParameterSource("id", memoryId.get())
        );
    }

    @Override
    public int markDeleted(MemoryFilter filter) {
        SqlFilter sqlFilter = buildFilter(filter, true);
        return jdbcTemplate.update(
                """
                        update arkmem_memories
                        set deleted_at = now(), updated_at = now()
                        %s
                        """.formatted(sqlFilter.whereClause()),
                sqlFilter.parameters()
        );
    }

    @Override
    public void appendHistory(MemoryHistoryEntry entry) {
        jdbcTemplate.update(
                """
                        insert into arkmem_memory_history (
                          id, memory_id, old_memory, new_memory, event, created_at
                        ) values (
                          cast(:id as uuid),
                          cast(:memoryId as uuid),
                          :oldMemory,
                          :newMemory,
                          :event,
                          :createdAt
                        )
                        """,
                new MapSqlParameterSource()
                        .addValue("id", entry.getId())
                        .addValue("memoryId", entry.getMemoryId())
                        .addValue("oldMemory", entry.getOldMemory())
                        .addValue("newMemory", entry.getNewMemory())
                        .addValue("event", entry.getEvent().name())
                        .addValue("createdAt", Timestamp.from(entry.getCreatedAt()))
        );
    }

    @Override
    public List<MemoryHistoryEntry> history(String memoryId) {
        Optional<UUID> parsedMemoryId = parseUuid(memoryId);
        if (parsedMemoryId.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                        select id, memory_id, old_memory, new_memory, event, created_at
                        from arkmem_memory_history
                        where memory_id = :memoryId
                        order by created_at asc
                        """,
                new MapSqlParameterSource("memoryId", parsedMemoryId.get()),
                this::mapHistoryEntry
        );
    }

    @Override
    public void reset() {
        jdbcTemplate.getJdbcOperations().execute("truncate table arkmem_memory_history, arkmem_memories");
    }

    private MemoryRecord mapMemoryRecord(ResultSet rs, int rowNum) throws SQLException {
        MemoryRecord record = new MemoryRecord();
        record.setId(rs.getObject("id", UUID.class).toString());
        record.setMemory(rs.getString("memory"));
        record.setMetadata(readMetadata(rs.getString("metadata")));
        record.setEmbedding(List.of());
        record.setCreatedAt(readInstant(rs, "created_at"));
        record.setUpdatedAt(readInstant(rs, "updated_at"));
        record.setDeleted(rs.getTimestamp("deleted_at") != null);
        return record;
    }

    private MemorySearchResult mapSearchResult(ResultSet rs, int rowNum) throws SQLException {
        return new MemorySearchResult(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("memory"),
                rs.getDouble("score"),
                readNullableDouble(rs, "semantic_score"),
                readNullableDouble(rs, "keyword_score"),
                rs.getString("search_mode"),
                readMetadata(rs.getString("metadata")),
                readInstant(rs, "created_at"),
                readInstant(rs, "updated_at")
        );
    }

    private MemoryHistoryEntry mapHistoryEntry(ResultSet rs, int rowNum) throws SQLException {
        return new MemoryHistoryEntry(
                rs.getObject("id", UUID.class).toString(),
                rs.getObject("memory_id", UUID.class).toString(),
                rs.getString("old_memory"),
                rs.getString("new_memory"),
                MemoryEvent.valueOf(rs.getString("event")),
                readInstant(rs, "created_at")
        );
    }

    private SqlFilter buildFilter(MemoryFilter filter, boolean onlyActive) {
        PostgresMemoryFilterSqlBuilder.SqlFilter sqlFilter = PostgresMemoryFilterSqlBuilder.build(filter, onlyActive);
        return new SqlFilter(sqlFilter.whereClause(), sqlFilter.parameters());
    }

    private String writeMetadata(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize memory metadata", e);
        }
    }

    private Map<String, Object> readMetadata(String value) {
        if (value == null || value.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(value, METADATA_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize memory metadata", e);
        }
    }

    private String toVectorLiteral(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            throw new BadRequestException("embedding cannot be empty");
        }
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < embedding.size(); i++) {
            double value = embedding.get(i);
            if (!Double.isFinite(value)) {
                throw new BadRequestException("embedding contains a non-finite value");
            }
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Double.toString(value));
        }
        return builder.append(']').toString();
    }

    private Instant readInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Double readNullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private Optional<UUID> parseUuid(String value) {
        try {
            return Optional.of(UUID.fromString(value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private record SqlFilter(String whereClause, MapSqlParameterSource parameters) {
    }
}
