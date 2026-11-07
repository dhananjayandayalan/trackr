package dev.trakr.api.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Turns on {@code @Scheduled} methods: SSE heartbeats and presence, outbox housekeeping. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig {
}
