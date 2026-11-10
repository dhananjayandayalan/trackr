package dev.trakr.api.attachment;

import dev.trakr.api.attachment.internal.Attachment;
import dev.trakr.api.attachment.internal.AttachmentQueries;
import dev.trakr.api.attachment.internal.AttachmentRepository;
import dev.trakr.api.attachment.internal.PendingUpload;
import dev.trakr.api.attachment.internal.PendingUploads;
import dev.trakr.api.attachment.internal.S3Properties;
import dev.trakr.api.issue.IssueDetails.AttachmentView;
import dev.trakr.api.issue.IssueHandle;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.kernel.Ids;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/**
 * Attachments (03-api §3.6). The file's bytes never pass through the api:
 * <ol>
 * <li>{@link #presignUpload}: the api checks the request and signs a URL that allows exactly one PUT — this
 * key, this content type, this size — for 5 minutes;</li>
 * <li>the browser PUTs the file to S3 with that URL;</li>
 * <li>{@link #confirm}: the api looks at the object in S3 (HEAD) and only then records it.</li>
 * </ol>
 * The api stays small and fast (no 25 MB request bodies on its threads), and S3 does what it's built for.
 */
@Service
public class AttachmentService {

    /** 09-security §4 */
    public static final long MAX_BYTES = 25L * 1024 * 1024;
    static final Duration UPLOAD_URL_TTL = Duration.ofMinutes(5);
    static final Duration DOWNLOAD_URL_TTL = Duration.ofMinutes(1);

    // BLOCK: the content-type allow-list. No text/html or image/svg+xml: both can carry scripts, and a script in
    // a file served from a domain the user trusts is stored XSS.
    static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp",
            "application/pdf", "text/plain", "text/csv", "text/markdown", "application/json", "application/zip",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    // shown in the browser; everything else downloads (Content-Disposition: attachment)
    static final Set<String> INLINE_TYPES = Set.of("image/png", "image/jpeg", "image/gif", "image/webp",
            "application/pdf");

    /** What the browser asks to upload. */
    public record NewUpload(String fileName, String contentType, long sizeBytes) {
    }

    /** Step 1's answer: send {@code PUT url} with these headers and the file as the body, then confirm uploadId. */
    @Schema(name = "UploadTicket")
    public record UploadTicket(UUID uploadId, String objectKey, String url, Map<String, String> headers) {
    }

    private final AttachmentRepository attachments;
    private final AttachmentQueries queries;
    private final PendingUploads pending;
    private final IssueService issues;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final S3Properties props;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    AttachmentService(AttachmentRepository attachments, AttachmentQueries queries, PendingUploads pending,
            IssueService issues, S3Client s3, S3Presigner presigner, S3Properties props,
            ApplicationEventPublisher events, Clock clock) {
        this.attachments = attachments;
        this.queries = queries;
        this.pending = pending;
        this.issues = issues;
        this.s3 = s3;
        this.presigner = presigner;
        this.props = props;
        this.events = events;
        this.clock = clock;
    }

    public UploadTicket presignUpload(UUID userId, String issueKey, NewUpload upload) {
        // BLOCK 1: validate — member of the project, size, type, name
        IssueHandle issue = issues.require(userId, issueKey, ProjectRole.MEMBER);
        if (upload.sizeBytes() > MAX_BYTES) {
            throw new TrakrException(HttpStatus.CONTENT_TOO_LARGE, "ATTACHMENT_TOO_LARGE",
                    "Attachments can be at most 25 MB.");
        }
        String contentType = baseType(upload.contentType());
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "This kind of file can't be attached.",
                    List.of(new FieldMessage("contentType", "is not an allowed file type")));
        }
        String fileName = FileNames.safe(upload.fileName());

        // BLOCK 2: object key — ours except the file name; the random part means two uploads of report.pdf never collide
        String objectKey = "ws/%s/issues/%s/%s/%s".formatted(issue.workspaceId(), issue.id(), Ids.newId(), fileName);

        // BLOCK 3: presign PUT. Content type and length are signed into the URL: a different file (bigger, or
        // another type) doesn't match the signature, and S3 refuses it.
        PresignedPutObjectRequest presigned = presigner.presignPutObject(r -> r
                .signatureDuration(UPLOAD_URL_TTL)
                .putObjectRequest(p -> p.bucket(props.bucket()).key(objectKey).contentType(contentType)
                        .contentLength(upload.sizeBytes())));

        // BLOCK 4: remember the pending upload; confirm trusts this record, not the browser
        UUID uploadId = Ids.newId();
        pending.remember(uploadId,
                new PendingUpload(issue.id(), objectKey, fileName, contentType, upload.sizeBytes(), userId));
        // Content-Length is signed too, but the browser sets it itself (scripts may not); only Content-Type is ours
        return new UploadTicket(uploadId, objectKey, presigned.url().toString(), Map.of("Content-Type", contentType));
    }

    /**
     * Step 3: the browser says the PUT finished. We check S3 ourselves: the object exists and has the size we
     * signed for. Trusting the browser's word (or a size it sends now) would let anyone record files that
     * aren't there, or bigger than the limit.
     */
    @Transactional
    public AttachmentView confirm(UUID userId, String issueKey, UUID uploadId) {
        IssueHandle issue = issues.require(userId, issueKey, ProjectRole.MEMBER);
        // someone else's upload, or one for another issue, is as unknown to this caller as an expired one
        PendingUpload upload = pending.find(uploadId)
                .filter(candidate -> candidate.userId().equals(userId) && candidate.issueId().equals(issue.id()))
                .orElseThrow(() -> new TrakrException.NotFound("No such upload — it may have expired. Try again."));

        HeadObjectResponse head;
        try {
            head = s3.headObject(r -> r.bucket(props.bucket()).key(upload.objectKey()));
        } catch (NoSuchKeyException e) {
            throw new TrakrException.Conflict("UPLOAD_MISSING", "The file hasn't arrived in storage. Upload it again.");
        } catch (S3Exception e) {
            if (e.statusCode() == HttpStatus.NOT_FOUND.value()) {
                throw new TrakrException.Conflict("UPLOAD_MISSING", "The file hasn't arrived in storage. Upload it again.");
            }
            throw e;
        }
        if (head.contentLength() == null || head.contentLength() != upload.sizeBytes()) {
            // not the file we signed for: remove it, so it doesn't sit in the bucket unaccounted for
            s3.deleteObject(r -> r.bucket(props.bucket()).key(upload.objectKey()));
            pending.forget(uploadId);
            throw new TrakrException.Conflict("UPLOAD_MISMATCH", "The stored file isn't the one that was announced.");
        }

        Attachment attachment = attachments.saveAndFlush(
                Attachment.confirmed(Ids.newId(), upload, head.contentLength(), clock));
        // the key goes now: a second confirm of the same upload is a 404, and uq_attachment_object_key backs that up
        pending.forget(uploadId);
        events.publishEvent(new AttachmentAdded(attachment.id(), issue.id(), issue.projectId(), userId,
                attachment.fileName()));
        return queries.view(attachment.id()).orElseThrow();
    }

    /**
     * A short-lived (1 minute) URL for the file; GET /attachments/{id}/content redirects there. Images and PDFs
     * open in the browser, anything else downloads.
     */
    @Transactional(readOnly = true)
    public URI presignDownload(UUID userId, UUID attachmentId) {
        Attachment attachment = load(attachmentId);
        issues.require(userId, attachment.issueId(), ProjectRole.VIEWER);
        boolean inline = INLINE_TYPES.contains(attachment.contentType());
        PresignedGetObjectRequest presigned = presigner.presignGetObject(r -> r
                .signatureDuration(DOWNLOAD_URL_TTL)
                .getObjectRequest(g -> g.bucket(props.bucket()).key(attachment.objectKey())
                        // response-content-*: S3 answers with these headers, whatever was stored
                        .responseContentType(attachment.contentType())
                        .responseContentDisposition(FileNames.disposition(inline, attachment.fileName()))));
        try {
            return presigned.url().toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The SDK signed a URL that isn't a URI", e);
        }
    }

    /** The uploader, or a project admin. */
    @Transactional
    public void delete(UUID userId, UUID attachmentId) {
        Attachment attachment = load(attachmentId);
        IssueHandle issue = issues.require(userId, attachment.issueId(), ProjectRole.MEMBER);
        if (!attachment.uploaderId().equals(userId) && !issue.role().atLeast(ProjectRole.ADMIN)) {
            throw new TrakrException.Forbidden("Only the uploader or a project admin can remove an attachment.");
        }
        attachment.delete(clock);
        events.publishEvent(new AttachmentRemoved(attachment.id(), issue.id(), issue.projectId(), userId));
    }

    private Attachment load(UUID attachmentId) {
        return attachments.findById(attachmentId).filter(attachment -> !attachment.isDeleted())
                .orElseThrow(() -> new TrakrException.NotFound("No such attachment."));
    }

    // "Text/Plain; charset=UTF-8" → "text/plain"
    private static String baseType(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }
}
