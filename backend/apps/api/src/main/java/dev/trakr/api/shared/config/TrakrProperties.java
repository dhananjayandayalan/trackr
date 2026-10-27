package dev.trakr.api.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("trakr")
public record TrakrProperties(@Valid @NotNull App app) {

    public record App(@NotNull URI origin) {
    }
}
