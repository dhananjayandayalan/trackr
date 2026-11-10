package dev.trakr.collab.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TimeConfig {

    // event times, session expiry, presence heartbeats
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
