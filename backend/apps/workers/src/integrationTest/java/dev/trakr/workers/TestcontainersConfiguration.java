package dev.trakr.workers;

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

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18").withReuse(true);
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
