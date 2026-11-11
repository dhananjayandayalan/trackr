package dev.trakr.workers;

import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Everything the workers talk to, in containers, plus a clock tests can move and a mailbox they can read. */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    // Postgres with the three app roles (db/test-roles.sql). Not a @ServiceConnection: that would hand the
    // container's superuser to the app too, and a superuser skips both the grants and row-level security.
    // The app connects as trakr_workers; Flyway and the fixtures (OwnerJdbc) as the owner — like dev and prod (phase 18).
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
                return "trakr_workers";
            }

            @Override
            public String getPassword() {
                return "trakr_workers";
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
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.0.0").withReuse(true);
    }

    // the version infra/docker-compose.yml runs; security off like there, and a small heap for a test
    @Bean
    @ServiceConnection
    ElasticsearchContainer elasticsearch() {
        return new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.4.8")
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withReuse(true);
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379).withReuse(true);
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(Instant.parse("2026-11-09T09:00:00Z"));
    }

    @Bean
    RecordingMailSender mailSender() {
        return new RecordingMailSender();
    }

    /** A clock whose "now" the test sets: one day of the burndown at a time. */
    public static class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        public void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    /** Collects messages instead of sending them; Mailpit checks the real thing on the running app. */
    public static class RecordingMailSender extends JavaMailSenderImpl {

        public final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(SimpleMailMessage... messages) {
            sent.addAll(List.of(messages));
        }
    }
}
