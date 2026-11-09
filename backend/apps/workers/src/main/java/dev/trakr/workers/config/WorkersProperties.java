package dev.trakr.workers.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code admin.token}: what /admin/** requests must send in X-Admin-Token; {@code app.origin}: the SPA's
 * address, for links in emails; {@code events.partitions}: the partition count of the topics we create.
 */
@Validated
@ConfigurationProperties("trakr")
public record WorkersProperties(@Valid @NotNull Admin admin, @Valid @NotNull App app, @Valid @NotNull Mail mail,
        @Valid @NotNull Events events) {

    public record Admin(@NotBlank String token) {
    }

    public record App(@NotNull URI origin) {
    }

    public record Mail(@NotBlank String from) {
    }

    public record Events(@Min(1) int partitions) {
    }
}
