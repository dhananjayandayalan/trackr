package dev.trakr.api.comment;

import dev.trakr.api.issue.IssueDetails.UserRef;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One comment as the Comments tab shows it. {@code bodyHtml} is sanitised: the frontend may render it as
 * HTML (through its one SafeHtml component). {@code version} is what the comment's ETag carries.
 */
@Schema(name = "Comment")
public record CommentView(UUID id, UUID issueId, UserRef author, String bodyHtml, Instant createdAt,
        @Nullable Instant editedAt, long version) {
}
