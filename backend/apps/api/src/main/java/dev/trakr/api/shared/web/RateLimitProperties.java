package dev.trakr.api.shared.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * trakr.rate-limit.* — the token buckets of 09-security §8. {@code tokens} per {@code per} is the steady rate,
 * {@code burst} how many may come at once after a quiet spell. Defaults are the spec's numbers.
 */
@Validated
@ConfigurationProperties("trakr.rate-limit")
public record RateLimitProperties(@DefaultValue("true") boolean enabled, @Valid Bucket write, @Valid Bucket search, @Valid Bucket auth) {

    public record Bucket(@Positive int tokens, Duration per, @Positive int burst) {

        /** Refill rate in tokens per second, what the Lua script takes. */
        public double perSecond() {
            return tokens / (per.toMillis() / 1000.0);
        }
    }

    public RateLimitProperties {
        write = write == null ? new Bucket(20, Duration.ofSeconds(1), 40) : write;
        search = search == null ? new Bucket(10, Duration.ofSeconds(1), 20) : search;
        auth = auth == null ? new Bucket(5, Duration.ofMinutes(1), 5) : auth;
    }
}
