package dev.trakr.collab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The third Spring Boot app: a WebSocket relay for collaborative description editing (06-collab). Browsers edit a
 * Yjs document; this app authenticates them, stores the binary updates they send, replays them to newcomers and
 * relays them to everyone else in the same issue's "room" — without ever understanding what is inside them.
 *
 * <p>Split out from the api because a socket lives for minutes or hours: collab scales by open connections,
 * the api by requests per second (01-architecture §2).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CollabApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollabApplication.class, args);
    }
}
