package dev.trakr.workers;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The second Spring Boot app: no users, no HTTP API — Kafka consumers that keep the read models
 * (Elasticsearch, report tables, notifications) in step with what the api writes. Split out because
 * its load is bursty and different: reindexing 100k issues must never slow down a board drag.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class WorkersApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkersApplication.class, args);
    }
}
