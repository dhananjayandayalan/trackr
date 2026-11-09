package dev.trakr.api.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("trakr")
public record TrakrProperties(@Valid @NotNull App app, @Valid @NotNull Mail mail, @Valid @NotNull Events events,
        @Valid @NotNull Search search) {

    public record App(@NotNull URI origin) {
    }

    /** {@code from}: the sender of every email, e.g. {@code TRAKR <no-reply@trakr.local>}. */
    public record Mail(@NotBlank String from) {
    }

    /** {@code partitions}: how many Kafka partitions each topic gets when KafkaTopicsConfig creates it. */
    public record Events(@Min(1) int partitions) {
    }

    /** {@code index}: the alias search reads (workers points it at the current trakr-issues-v{n}). */
    public record Search(@NotBlank String index) {
    }
}
