package dev.trakr.api.attachment.internal;

import java.util.UUID;

/**
 * An upload we signed a URL for but haven't seen finish. Everything confirm needs comes from here, never
 * from the browser's second request: the browser only says which upload it means (the uploadId).
 */
public record PendingUpload(UUID issueId, String objectKey, String fileName, String contentType, long sizeBytes,
        UUID userId) {
}
