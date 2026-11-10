package dev.trakr.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

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

    // the image infra/docker-compose.yml runs, with the same identity file: S3 on 8333. Not a service connection
    // (Boot has none for S3), so its address goes into trakr.s3.endpoint below.
    @Bean
    GenericContainer<?> seaweedfs() {
        return new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.48"))
                .withCommand("server", "-dir=/data", "-s3", "-s3.config=/etc/seaweedfs/s3.json")
                .withCopyFileToContainer(MountableFile.forHostPath(System.getProperty("trakr.s3.config")),
                        "/etc/seaweedfs/s3.json")
                .withExposedPorts(8333)
                // the S3 port answers once the master, volume and filer are up
                .waitingFor(Wait.forHttp("/").forPort(8333).forStatusCodeMatching(status -> status < 500)
                        .withStartupTimeout(Duration.ofMinutes(2)))
                .withReuse(true);
    }

    @Bean
    DynamicPropertyRegistrar s3Endpoint(GenericContainer<?> seaweedfs) {
        return registry -> registry.add("trakr.s3.endpoint",
                () -> "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333));
    }

    // a reused container can be the workers tests' one too: search reads its own alias, which
    // SearchIndexFixture fills, so neither suite sees the other's documents
    @Bean
    DynamicPropertyRegistrar searchIndex() {
        return registry -> registry.add("trakr.search.index", () -> "trakr-issues-api-it");
    }
}
