package dev.trakr.api.attachment.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CORSRule;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

/**
 * At startup: make sure the bucket exists and lets the frontend's origin upload to it (11-infra §4).
 *
 * <p>Why CORS: the browser PUTs the file to {@code localhost:8333}, a different origin than the page
 * ({@code localhost:5173}). Before a cross-origin PUT the browser asks the bucket first (a preflight
 * OPTIONS request): "may this origin PUT with a Content-Type header?". Without a CORS rule on the bucket
 * the answer is no, and the upload never leaves the browser — even though the URL's signature is valid.
 *
 * <p>Failure is logged, not fatal: everything except attachments works without S3, so a stopped SeaweedFS
 * shouldn't keep the api from starting. Both calls are idempotent; running on every start is fine.
 */
@Component
class BucketInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BucketInitializer.class);

    private final S3Client s3;
    private final S3Properties props;

    BucketInitializer(S3Client s3, S3Properties props) {
        this.s3 = s3;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            createBucketIfMissing();
            applyCors();
            log.info("S3 bucket {} ready at {}", props.bucket(), props.endpoint());
        } catch (SdkException e) {
            log.warn("S3 bucket {} at {} is not ready; attachments will fail until it is: {}", props.bucket(),
                    props.endpoint(), e.getMessage());
        }
    }

    // BLOCK 1: HEAD first, so a normal start makes no write; "already owned" covers two instances starting together
    private void createBucketIfMissing() {
        try {
            s3.headBucket(r -> r.bucket(props.bucket()));
        } catch (NoSuchBucketException e) {
            try {
                s3.createBucket(r -> r.bucket(props.bucket()));
            } catch (BucketAlreadyOwnedByYouException alreadyThere) {
                // created by another instance a moment ago: what we wanted
            }
        }
    }

    // BLOCK 2: the browser may PUT (upload) and GET (download, image previews) from our origins; it may send
    // Content-Type (signed into the URL) and read ETag from the answer
    private void applyCors() {
        CORSRule rule = CORSRule.builder()
                .allowedOrigins(props.allowedOrigins())
                .allowedMethods("PUT", "GET", "HEAD")
                .allowedHeaders("*")
                .exposeHeaders("ETag")
                .maxAgeSeconds(3600)
                .build();
        s3.putBucketCors(r -> r.bucket(props.bucket()).corsConfiguration(c -> c.corsRules(rule)));
    }
}
