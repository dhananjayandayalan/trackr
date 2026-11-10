package dev.trakr.api.comment.web;

import dev.trakr.api.comment.CommentService;
import dev.trakr.api.comment.CommentView;
import dev.trakr.api.identity.TrakrPrincipal;
import dev.trakr.api.shared.web.CursorPage;
import dev.trakr.api.shared.web.ETags;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 03-api §3.5. Listed and added under their issue, edited and deleted by their own id. Every comment response
 * carries its ETag, which edit and delete want back in If-Match.
 */
@RestController
class CommentController {

    /** 09-security §4: comments up to 20 kB. Checked before sanitising, so a huge body is refused unread. */
    record CommentRequest(@NotBlank @Size(max = 20_000) String bodyHtml) {
    }

    private final CommentService comments;

    CommentController(CommentService comments) {
        this.comments = comments;
    }

    @Operation(operationId = "listComments")
    @GetMapping("/api/v1/issues/{key}/comments")
    CursorPage<CommentView> list(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @RequestParam(required = false) @Nullable String cursor) {
        return comments.list(principal.id(), key, cursor);
    }

    // safe to retry with an Idempotency-Key header (IdempotencyFilter)
    @Operation(operationId = "addComment")
    @PostMapping("/api/v1/issues/{key}/comments")
    ResponseEntity<CommentView> add(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable String key,
            @Valid @RequestBody CommentRequest body) {
        CommentView comment = comments.add(principal.id(), key, body.bodyHtml());
        return ResponseEntity.created(URI.create("/api/v1/comments/" + comment.id()))
                .eTag(ETags.of(comment.version()))
                .body(comment);
    }

    @Operation(operationId = "editComment")
    @PatchMapping("/api/v1/comments/{id}")
    ResponseEntity<CommentView> edit(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch,
            @Valid @RequestBody CommentRequest body) {
        long expectedVersion = ETags.versionFrom(ifMatch);
        CommentView comment = comments.edit(principal.id(), id, expectedVersion, body.bodyHtml());
        return ResponseEntity.ok().eTag(ETags.of(comment.version())).body(comment);
    }

    @Operation(operationId = "deleteComment")
    @DeleteMapping("/api/v1/comments/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal TrakrPrincipal principal, @PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) @Nullable String ifMatch) {
        comments.delete(principal.id(), id, ETags.versionFrom(ifMatch));
        return ResponseEntity.noContent().build();
    }
}
