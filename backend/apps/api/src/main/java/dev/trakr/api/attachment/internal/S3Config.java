package dev.trakr.api.attachment.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The two S3 objects of the AWS SDK v2, pointed at SeaweedFS instead of AWS:
 * <ul>
 * <li>{@link S3Client} makes requests itself (create the bucket, HEAD an uploaded object);</li>
 * <li>{@link S3Presigner} makes no request at all: it computes a signature for a request <em>someone else</em>
 * will send — a URL that lets the browser PUT or GET one object for a few minutes.</li>
 * </ul>
 * Both are thread-safe and meant to live as long as the app (they hold an HTTP connection pool).
 */
@Configuration(proxyBeanMethods = false)
class S3Config {

    // destroyMethod: Spring calls close() on shutdown, which releases the connection pool
    @Bean(destroyMethod = "close")
    S3Client s3Client(S3Properties props) {
        return S3Client.builder()
                // BLOCK 1: where — SeaweedFS, not s3.amazonaws.com. The region is part of every signature, so it must
                // be set, but SeaweedFS accepts any; us-east-1 is the S3 default.
                .endpointOverride(props.endpoint())
                .region(Region.of(props.region()))
                // BLOCK 2: path-style URLs (http://host:8333/bucket/key) instead of virtual-host style
                // (http://bucket.host:8333/key), which would need a DNS name per bucket
                .forcePathStyle(true)
                // BLOCK 3: who — fixed dev credentials from the environment, never the AWS default chain (which
                // would look for ~/.aws and EC2 metadata)
                .credentialsProvider(credentials(props))
                .build();
    }

    @Bean(destroyMethod = "close")
    S3Presigner s3Presigner(S3Properties props) {
        return S3Presigner.builder()
                .endpointOverride(props.endpoint())
                .region(Region.of(props.region()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(credentials(props))
                .build();
    }

    private static StaticCredentialsProvider credentials(S3Properties props) {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
    }
}
