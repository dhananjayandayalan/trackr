package dev.trakr.api.comment.internal;

import dev.trakr.kernel.Ids;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A comment on an issue. The body arrives here already sanitised (HtmlSanitizer): an entity can't be
 * created or edited with HTML that hasn't been through the allow-list, because only the service calls
 * these methods and it sanitises first.
 */
@Entity
@Table(name = "comment")
public class Comment {

    @Id
    private UUID id;
    private UUID issueId;
    private UUID authorId;
    private String bodyHtml;
    private String bodyText;
    // bumped by Hibernate on every UPDATE: the ETag of the comment and the sequence of its events
    @Version
    private long version;
    private Instant createdAt;
    private @Nullable Instant editedAt;
    private @Nullable Instant deletedAt;

    protected Comment() {
        // for JPA
    }

    public static Comment add(UUID issueId, UUID authorId, String bodyHtml, String bodyText, Clock clock) {
        Comment comment = new Comment();
        comment.id = Ids.newId();
        comment.issueId = issueId;
        comment.authorId = authorId;
        comment.bodyHtml = bodyHtml;
        comment.bodyText = bodyText;
        comment.createdAt = clock.instant();
        return comment;
    }

    /** @return false when the body is what it already was: no UPDATE, no new version, no event. */
    public boolean edit(String newHtml, String newText, Clock clock) {
        if (newHtml.equals(bodyHtml)) {
            return false;
        }
        bodyHtml = newHtml;
        bodyText = newText;
        editedAt = clock.instant();
        return true;
    }

    /** Soft delete: the row stays (history and notifications may point at it), the Comments tab hides it. */
    public void delete(Clock clock) {
        deletedAt = clock.instant();
    }

    public UUID id() {
        return id;
    }

    public UUID issueId() {
        return issueId;
    }

    public UUID authorId() {
        return authorId;
    }

    public String bodyHtml() {
        return bodyHtml;
    }

    public String bodyText() {
        return bodyText;
    }

    public long version() {
        return version;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
