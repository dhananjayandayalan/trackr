package dev.trakr.api.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} methods: SSE heartbeats and presence, outbox housekeeping. Always on, except in the
 * container image's AOT training run (Dockerfile, trakr.scheduling.enabled=false): Spring Boot never initializes
 * beans with {@code @Scheduled} methods lazily, and the outbox housekeeping bean needs the database.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "trakr.scheduling.enabled", matchIfMissing = true)
@EnableScheduling
class SchedulingConfig {
}
