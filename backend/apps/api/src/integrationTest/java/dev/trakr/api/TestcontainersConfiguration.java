package dev.trakr.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18").withReuse(true);
    }

    // Testcontainers has no RedisContainer class; name = "redis" tells Spring Boot what this generic one runs
    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse("redis:8")).withExposedPorts(6379).withReuse(true);
    }

    // the same image as infra/docker-compose.yml: KRaft mode, no ZooKeeper; sets spring.kafka.bootstrap-servers
    @Bean
    @ServiceConnection
    KafkaContainer kafka() {
        return new KafkaContainer("apache/kafka:4.0.0").withReuse(true);
    }

    // the version infra/docker-compose.yml runs, security off like there; sets spring.elasticsearch.uris
    @Bean
    @ServiceConnection
    ElasticsearchContainer elasticsearch() {
        return new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:9.4.8")
                .withEnv("xpack.security.enabled", "false")
                .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
                .withReuse(true);
    }

    // a reused container can be the workers tests' one too: search reads its own alias, which
    // SearchIndexFixture fills, so neither suite sees the other's documents
    @Bean
    DynamicPropertyRegistrar searchIndex() {
        return registry -> registry.add("trakr.search.index", () -> "trakr-issues-api-it");
    }
}
