package dev.trakr.collab.config;

import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.origin}: the SPA origin(s) allowed to open a socket (comma-separated for several);
 * {@code session}: where the api keeps its sessions — the cookie's name and the Redis key prefix;
 * {@code events.partitions}: the partition count of the topic this app creates.
 */
@ConfigurationProperties("trakr")
public record CollabProperties(App app, Session session, Events events) {

    public CollabProperties {
        Objects.requireNonNull(app, "trakr.app");
        Objects.requireNonNull(session, "trakr.session");
        Objects.requireNonNull(events, "trakr.events");
    }

    public record App(List<String> origin) {

        public App {
            origin = List.copyOf(origin);
            if (origin.isEmpty()) {
                throw new IllegalArgumentException("trakr.app.origin needs at least one origin");
            }
        }
    }

    public record Session(String cookieName, String namespace) {

        public Session {
            Objects.requireNonNull(cookieName, "trakr.session.cookie-name");
            Objects.requireNonNull(namespace, "trakr.session.namespace");
        }
    }

    public record Events(int partitions) {
    }
}
