package dev.trakr.collab;

import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** What collab talks to, in containers: the same images as infra/docker-compose.yml. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Postgres with the three app roles (db/test-roles.sql). Not a @ServiceConnection: that would hand the
    // container's superuser to the app too, and a superuser skips both the grants and row-level security.
    // The app connects as trakr_collab; Flyway and the fixtures (OwnerJdbc) as the owner — like dev and prod (phase 18).
    @Bean
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18").withInitScript("db/test-roles.sql").withReuse(true);
    }

    @Bean
    JdbcConnectionDetails appDatabase(PostgreSQLContainer postgres) {
        return new JdbcConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }

            @Override
            public String getUsername() {
                return "trakr_collab";
            }

            @Override
            public String getPassword() {
                return "trakr_collab";
            }
        };
    }

    @Bean
    FlywayConnectionDetails migrationDatabase(PostgreSQLContainer postgres) {
        return new FlywayConnectionDetails() {
            @Override
            public String getJdbcUrl() {
                return postgres.getJdbcUrl();
            }

            @Override
            public String getUsername() {
                return postgres.getUsername();
            }

            @Override
            public String getPassword() {
                return postgres.getPassword();
            }
        };
    }

    /** Test data and assertions as the owner: the app's own role may not write (or even read) every table. */
    public record OwnerJdbc(JdbcClient client) {
    }

    @Bean
    OwnerJdbc ownerJdbc(PostgreSQLContainer postgres) {
        return new OwnerJdbc(JdbcClient.create(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379).withReuse(true);
    }

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.0.0").withReuse(true);
    }
}
