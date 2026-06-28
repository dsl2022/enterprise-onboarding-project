package com.eop;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real backing services for the {@code data}-profile integration tests. {@code @ServiceConnection} wires
 * Spring Boot's datasource + Redis connection details straight from the containers, so the tests exercise
 * the same Flyway/JPA/Spring-Session stack that runs on ECS — not an embedded substitute.
 *
 * <p>The Postgres image is {@code pgvector/pgvector:pg16} so {@code CREATE EXTENSION vector} in the V1
 * baseline succeeds exactly as it does on RDS (where the managed master user holds {@code rds_superuser}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    }
}
