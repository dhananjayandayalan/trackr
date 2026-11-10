package dev.trakr.api.comment.internal;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CommentMentionRepository extends JpaRepository<CommentMention, CommentMention.Key> {

    List<CommentMention> findByCommentId(UUID commentId);

    // an edit replaces the whole list: one DELETE, then the new rows
    @Modifying
    @Query("delete from CommentMention m where m.commentId = :commentId")
    void deleteByCommentId(UUID commentId);
}
