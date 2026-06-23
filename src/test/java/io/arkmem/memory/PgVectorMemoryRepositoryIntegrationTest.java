package io.arkmem.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.arkmem.memory.config.ArkMemProperties;
import io.arkmem.memory.embedding.LocalHashEmbeddingClient;
import io.arkmem.memory.llm.HeuristicMemoryExtractor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PgVectorMemoryRepositoryIntegrationTest {

    private static final DockerImageName PGVECTOR_IMAGE = DockerImageName
            .parse("pgvector/pgvector:pg16")
            .asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer<?> postgres;

    private MemoryService memoryService;

    @BeforeAll
    static void startPostgres() {
        Assumptions.assumeTrue(Boolean.getBoolean("arkmem.integration-tests"), "Integration tests are disabled");
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not available");
        postgres = new PostgreSQLContainer<>(PGVECTOR_IMAGE)
                .withDatabaseName("arkmem")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        initializeSchema();
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = dataSource();

        ArkMemProperties properties = new ArkMemProperties();
        properties.getEmbedding().setLocalDimensions(32);

        memoryService = new MemoryService(
                new PgVectorMemoryRepository(new NamedParameterJdbcTemplate(dataSource), new ObjectMapper()),
                new LocalHashEmbeddingClient(32),
                new HeuristicMemoryExtractor(),
                properties
        );
        memoryService.reset();
    }

    private static void initializeSchema() {
        try (Connection connection = dataSource().getConnection()) {
            ScriptUtils.executeSqlScript(
                    connection,
                    new EncodedResource(new ClassPathResource("db/schema.sql"), StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize PostgreSQL schema", e);
        }
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    @Test
    void persistsAndSearchesWithPgVectorAndKeywordIndex() {
        List<MemoryOperationResult> created = memoryService.add(new MemoryCreateCommand(
                List.of(new ChatMessage("user", "Project atlas uses pgvector keyword retrieval.", null)),
                "it-user",
                null,
                null,
                Map.of("source", "integration-test", "priority", 9),
                false,
                null,
                null
        ));

        assertThat(created).hasSize(1);

        List<MemorySearchResult> keywordResults = memoryService.search(new SearchCommand(
                "atlas keyword",
                new MemoryFilter("it-user", null, null, Map.of("source", Map.of("eq", "integration-test"))),
                5,
                0.0d,
                "keyword"
        ));

        assertThat(keywordResults).hasSize(1);
        assertThat(keywordResults.get(0).searchMode()).isEqualTo("keyword");
        assertThat(keywordResults.get(0).keywordScore()).isPositive();

        List<MemorySearchResult> hybridResults = memoryService.search(new SearchCommand(
                "pgvector retrieval",
                new MemoryFilter("it-user", null, null, Map.of("priority", Map.of("gte", 5))),
                5,
                0.0d,
                "hybrid"
        ));

        assertThat(hybridResults).hasSize(1);
        assertThat(hybridResults.get(0).semanticScore()).isNotNull();
        assertThat(hybridResults.get(0).keywordScore()).isNotNull();
    }
}
