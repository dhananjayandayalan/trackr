package dev.trakr.api.attachment.internal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code trakr.s3.*}: where the attachment bucket lives (11-infra §4, §7). {@code endpoint} is the address
 * the api <em>and the browser</em> use: presigned URLs are signed for this host, so the browser must reach it
 * under exactly this name. {@code allowedOrigins}: the CORS rule BucketInitializer puts on the bucket.
 */
@Validated
@ConfigurationProperties("trakr.s3")
public record S3Properties(@NotNull URI endpoint, @NotBlank String bucket, @NotBlank String region,
        @NotBlank String accessKey, @NotBlank String secretKey, @NotNull List<String> allowedOrigins) {
}
