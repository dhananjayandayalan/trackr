package dev.trakr.api.comment.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.UUID;

/** "This comment mentions that user": one row per person, resolved when the comment was saved. */
@Entity
@Table(name = "comment_mention")
@IdClass(CommentMention.Key.class)
public class CommentMention {

    public record Key(UUID commentId, UUID userId) implements Serializable {
    }

    @Id
    private UUID commentId;
    @Id
    private UUID userId;

    protected CommentMention() {
        // for JPA
    }

    public static CommentMention of(UUID commentId, UUID userId) {
        CommentMention mention = new CommentMention();
        mention.commentId = commentId;
        mention.userId = userId;
        return mention;
    }

    public UUID userId() {
        return userId;
    }
}
