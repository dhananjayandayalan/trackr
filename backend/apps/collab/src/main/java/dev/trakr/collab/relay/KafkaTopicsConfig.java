package dev.trakr.collab.relay;

import dev.trakr.collab.config.CollabProperties;
import dev.trakr.contracts.events.Topics;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topic collab produces, created at startup by Spring Boot's KafkaAdmin if it is missing (the broker doesn't
 * auto-create topics). The api declares it too, because it consumes it: whichever app starts first creates it.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

    // 04-events §4: an hour is plenty — relay messages matter for milliseconds, snapshots until the api has read them
    @Bean
    NewTopic collabTopic(CollabProperties properties) {
        return TopicBuilder.name(Topics.COLLAB)
                .partitions(properties.events().partitions())
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(Duration.ofHours(1).toMillis()))
                .build();
    }
}
