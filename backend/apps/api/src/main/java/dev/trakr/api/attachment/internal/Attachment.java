package dev.trakr.api.attachment.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What we know about one uploaded file; the file itself is the S3 object at {@code objectKey}. */
@Entity
@Table(name = "attachment")
public class Attachment {

    @Id
    private UUID id;
    private UUID issueId;
    private UUID uploaderId;
    private String objectKey;
    private String fileName;
    private String contentType;
    private long sizeBytes;
    private Instant createdAt;
    private @Nullable Instant deletedAt;

    protected Attachment() {
        // for JPA
    }

    /** Only after the object was seen in S3 with the expected size (AttachmentService.confirm). */
    public static Attachment confirmed(UUID id, PendingUpload upload, long sizeBytes, Clock clock) {
        Attachment attachment = new Attachment();
        attachment.id = id;
        attachment.issueId = upload.issueId();
        attachment.uploaderId = upload.userId();
        attachment.objectKey = upload.objectKey();
        attachment.fileName = upload.fileName();
        attachment.contentType = upload.contentType();
        attachment.sizeBytes = sizeBytes;
        attachment.createdAt = clock.instant();
        return attachment;
    }

    /** Soft delete: the issue page stops listing it; the object stays in the bucket for a cleanup job. */
    public void delete(Clock clock) {
        deletedAt = clock.instant();
    }

    public UUID id() {
        return id;
    }

    public UUID issueId() {
        return issueId;
    }

    public UUID uploaderId() {
        return uploaderId;
    }

    public String objectKey() {
        return objectKey;
    }

    public String fileName() {
        return fileName;
    }

    public String contentType() {
        return contentType;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
