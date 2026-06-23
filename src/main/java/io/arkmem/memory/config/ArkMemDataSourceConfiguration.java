package io.arkmem.memory.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class ArkMemDataSourceConfiguration {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    @ConditionalOnProperty(prefix = "arkmem.storage", name = "provider", havingValue = "postgres", matchIfMissing = true)
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean(name = "memoryJdbcTemplate")
    @Primary
    @ConditionalOnBean(name = "dataSource")
    public JdbcTemplate memoryJdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "memoryNamedParameterJdbcTemplate")
    @Primary
    @ConditionalOnBean(name = "memoryJdbcTemplate")
    public NamedParameterJdbcTemplate memoryNamedParameterJdbcTemplate(
            @Qualifier("memoryJdbcTemplate") JdbcTemplate jdbcTemplate
    ) {
        return new NamedParameterJdbcTemplate(jdbcTemplate);
    }
}
