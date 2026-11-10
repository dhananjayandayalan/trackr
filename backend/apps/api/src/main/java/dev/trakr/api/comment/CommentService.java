package dev.trakr.api.comment;

import dev.trakr.api.comment.internal.Comment;
import dev.trakr.api.comment.internal.CommentMention;
import dev.trakr.api.comment.internal.CommentMentionRepository;
import dev.trakr.api.comment.internal.CommentQueries;
import dev.trakr.api.comment.internal.CommentRepository;
import dev.trakr.api.comment.internal.MentionExtractor;
import dev.trakr.api.issue.IssueHandle;
import dev.trakr.api.issue.IssueService;
import dev.trakr.api.project.ProjectRole;
import dev.trakr.api.shared.web.CursorPage;
import dev.trakr.api.shared.web.HtmlSanitizer;
import dev.trakr.api.shared.web.TrakrException;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import dev.trakr.contracts.events.CommentEvent.CommentAdded;
import dev.trakr.contracts.events.CommentEvent.CommentDeleted;
import dev.trakr.contracts.events.CommentEvent.CommentEdited;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comments (03-api §3.5). Reading needs the viewer role on the issue's project, writing the member role;
 * editing is for the author only, deleting for the author or a project admin.
 *
 * <p>Every body goes through the same two steps before it's stored: sanitise (allow-list, 09-security §4),
 * then derive the text and the mentions from the <em>sanitised</em> HTML — never from what the browser sent.
 */
@Service
public class CommentService {

    static final int PAGE = 50;

    private final CommentRepository comments;
    private final CommentMentionRepository mentions;
    private final CommentQueries queries;
    private final MentionExtractor mentionExtractor;
    private final IssueService issues;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    CommentService(CommentRepository comments, CommentMentionRepository mentions, CommentQueries queries,
            MentionExtractor mentionExtractor, IssueService issues, ApplicationEventPublisher events, Clock clock) {
        this.comments = comments;
        this.mentions = mentions;
        this.queries = queries;
        this.mentionExtractor = mentionExtractor;
        this.issues = issues;
        this.events = events;
        this.clock = clock;
    }

    /** Newest first, {@value #PAGE} per page. */
    @Transactional(readOnly = true)
    public CursorPage<CommentView> list(UUID userId, String issueKey, @Nullable String cursor) {
        IssueHandle issue = issues.require(userId, issueKey, ProjectRole.VIEWER);
        UUID beforeId = cursor == null ? null : parseCursor(cursor);
        // one extra row tells whether there is a next page, without a COUNT
        List<CommentView> rows = queries.page(issue.id(), beforeId, PAGE + 1);
        if (rows.size() <= PAGE) {
            return new CursorPage<>(rows, null);
        }
        List<CommentView> page = rows.subList(0, PAGE);
        return new CursorPage<>(page, CursorPage.encode(page.getLast().id().toString()));
    }

    @Transactional
    public CommentView add(UUID userId, String issueKey, String bodyHtml) {
        // BLOCK 1: may the caller comment here?
        IssueHandle issue = issues.require(userId, issueKey, ProjectRole.MEMBER);

        // BLOCK 2: sanitise, then derive everything else from the clean HTML
        SanitizedBody body = SanitizedBody.of(bodyHtml);
        List<UUID> mentioned = mentionExtractor.mentionedMembers(body.html(), issue.projectId());

        // BLOCK 3: store comment, mentions and a history row in one transaction
        Comment comment = comments.saveAndFlush(Comment.add(issue.id(), userId, body.html(), body.text(), clock));
        mentions.saveAll(mentioned.stream().map(user -> CommentMention.of(comment.id(), user)).toList());
        issues.recordComment(issue.id(), userId, comment.id());

        // BLOCK 4: publish — the outbox sends it to trakr.comments.v1: notifications (mentions, watchers),
        // search (comment text), and the live stream of everyone looking at the issue
        events.publishEvent(CommentAdded.of(clock.instant(), issue.workspaceId(), issue.projectId(), comment.id(),
                issue.id(), issue.key(), userId, body.text(), mentioned));
        return view(comment.id());
    }

    /** Author only, with the version the client last saw (If-Match). */
    @Transactional
    public CommentView edit(UUID userId, UUID commentId, long expectedVersion, String bodyHtml) {
        Comment comment = load(commentId);
        IssueHandle issue = issues.require(userId, comment.issueId(), ProjectRole.MEMBER);
        if (!comment.authorId().equals(userId)) {
            throw new TrakrException.Forbidden("Only the author can edit a comment.");
        }
        requireVersion(expectedVersion, comment);

        SanitizedBody body = SanitizedBody.of(bodyHtml);
        if (!comment.edit(body.html(), body.text(), clock)) {
            return view(commentId);
        }
        // flushed: the UPDATE runs now, so the version below is the new one
        comments.flush();
        List<UUID> mentioned = mentionExtractor.mentionedMembers(body.html(), issue.projectId());
        mentions.deleteByCommentId(commentId);
        mentions.saveAll(mentioned.stream().map(user -> CommentMention.of(commentId, user)).toList());
        events.publishEvent(CommentEdited.of(clock.instant(), issue.workspaceId(), issue.projectId(), commentId,
                issue.id(), issue.key(), userId, body.text(), mentioned, comment.version()));
        return view(commentId);
    }

    /** The author, or an admin of the project (moderation). */
    @Transactional
    public void delete(UUID userId, UUID commentId, long expectedVersion) {
        Comment comment = load(commentId);
        IssueHandle issue = issues.require(userId, comment.issueId(), ProjectRole.MEMBER);
        boolean author = comment.authorId().equals(userId);
        if (!author && !issue.role().atLeast(ProjectRole.ADMIN)) {
            throw new TrakrException.Forbidden("Only the author or a project admin can delete a comment.");
        }
        requireVersion(expectedVersion, comment);
        comment.delete(clock);
        comments.flush();
        events.publishEvent(CommentDeleted.of(clock.instant(), userId, issue.workspaceId(), issue.projectId(),
                commentId, issue.id(), issue.key(), comment.authorId(), comment.version()));
    }

    // a deleted comment is gone as far as the API is concerned
    private Comment load(UUID commentId) {
        return comments.findById(commentId).filter(comment -> !comment.isDeleted())
                .orElseThrow(() -> new TrakrException.NotFound("No such comment."));
    }

    private void requireVersion(long expectedVersion, Comment comment) {
        if (expectedVersion != comment.version()) {
            throw new TrakrException.VersionMismatch(view(comment.id()));
        }
    }

    private CommentView view(UUID commentId) {
        return queries.one(commentId).orElseThrow(() -> new TrakrException.NotFound("No such comment."));
    }

    private static UUID parseCursor(String cursor) {
        try {
            return UUID.fromString(CursorPage.decode(cursor));
        } catch (IllegalArgumentException e) {
            throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The cursor is not valid.");
        }
    }

    /** A body after the allow-list, and its text. Blank once sanitised (only tags, or only a script) is refused. */
    private record SanitizedBody(String html, String text) {

        static SanitizedBody of(String untrusted) {
            String html = HtmlSanitizer.sanitize(untrusted);
            String text = HtmlSanitizer.text(html);
            if (text.isBlank()) {
                throw new TrakrException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The comment is empty.",
                        List.of(new FieldMessage("bodyHtml", "must not be blank")));
            }
            return new SanitizedBody(html, text);
        }
    }
}
