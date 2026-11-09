package dev.trakr.workers.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TimeConfig {

    // one clock for "today" in burndown rows and digests; tests replace it to move between days
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
