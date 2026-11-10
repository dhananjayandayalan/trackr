package dev.trakr.api.attachment.web;

import dev.trakr.api.attachment.AttachmentService;
import dev.trakr.api.attachment.AttachmentService.NewUpload;
import dev.trakr.api.attachment.AttachmentService.UploadTicket;
import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.issue.IssueDetails.AttachmentView;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 03-api §3.6: presign → (browser PUTs to S3) → confirm; download by redirect; delete. */
@RestController
class AttachmentController {

    // the 25 MB limit is the service's (a 413 with its own code), not a validation message
    record UploadRequest(@NotBlank @Size(max = 255) String fileName, @NotBlank @Size(max = 100) String contentType,
            @Positive long sizeBytes) {
    }

    record ConfirmRequest(@NotNull UUID uploadId) {
    }

    private final AttachmentService attachments;

    AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @Operation(operationId = "presignAttachmentUpload")
    @PostMapping("/api/v1/issues/{key}/attachments/uploads")
    UploadTicket presign(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @Valid @RequestBody UploadRequest body) {
        return attachments.presignUpload(principal.id(), key,
                new NewUpload(body.fileName(), body.contentType(), body.sizeBytes()));
    }

    @Operation(operationId = "confirmAttachment")
    @PostMapping("/api/v1/issues/{key}/attachments")
    ResponseEntity<AttachmentView> confirm(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @Valid @RequestBody ConfirmRequest body) {
        AttachmentView attachment = attachments.confirm(principal.id(), key, body.uploadId());
        return ResponseEntity.created(URI.create("/api/v1/attachments/" + attachment.id() + "/content"))
                .body(attachment);
    }

    /**
     * 302 to a presigned GET. A link or {@code <img src>} can point here: the browser sends our session
     * cookie, we check access, and it follows the redirect to S3. Never cached: the target expires in a minute.
     */
    @Operation(operationId = "downloadAttachment")
    @GetMapping("/api/v1/attachments/{id}/content")
    ResponseEntity<Void> download(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(attachments.presignDownload(principal.id(), id))
                .cacheControl(CacheControl.noStore())
                .build();
    }

    @Operation(operationId = "deleteAttachment")
    @DeleteMapping("/api/v1/attachments/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id) {
        attachments.delete(principal.id(), id);
        return ResponseEntity.noContent().build();
    }
}
