package dev.trakr.api.shared.events;

import dev.trakr.api.shared.config.TrakrProperties;
import dev.trakr.contracts.events.Topics;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The topics this app writes to (or reads before anyone writes), declared as beans: Spring Boot's
 * KafkaAdmin creates missing ones at startup. The broker has auto.create.topics.enable=false, so a
 * typo in a topic name fails loudly instead of creating a stray topic with default settings.
 */
@Configuration(proxyBeanMethods = false)
class KafkaTopicsConfig {

    private static final Duration WEEK = Duration.ofDays(7);

    private final int partitions;

    KafkaTopicsConfig(TrakrProperties properties) {
        this.partitions = properties.events().partitions();
    }

    @Bean
    NewTopic issuesTopic() {
        return topic(Topics.ISSUES, WEEK);
    }

    @Bean
    NewTopic projectsTopic() {
        return topic(Topics.PROJECTS, WEEK);
    }

    // written in phase 15; created now because the realtime consumer already listens to it
    @Bean
    NewTopic commentsTopic() {
        return topic(Topics.COMMENTS, WEEK);
    }

    // produced by the workers; declared here too, because the api reads it for /me/stream — whichever app starts
    // first creates it (KafkaAdmin leaves an existing topic alone)
    @Bean
    NewTopic notificationsTopic() {
        return topic(Topics.NOTIFICATIONS, Duration.ofDays(3));
    }

    // produced by collab (phase 16); declared here too because the api consumes its snapshots
    @Bean
    NewTopic collabTopic() {
        return topic(Topics.COLLAB, Duration.ofHours(1));
    }

    // replication 1 locally; production overrides it with the broker default (phase 18)
    private NewTopic topic(String name, Duration retention) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(retention.toMillis()))
                .build();
    }
}
