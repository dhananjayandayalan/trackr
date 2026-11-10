package dev.trakr.contracts.events;

import java.util.Map;
import java.util.Optional;

/**
 * Every CloudEvents {@code type} string in one place (04-events §4). Schema evolution: never change a
 * value; a breaking change gets a new {@code .v2} constant next to the old one.
 */
public final class EventTypes {

    public static final String ISSUE_CREATED = "trakr.issue.created.v1";
    public static final String ISSUE_UPDATED = "trakr.issue.updated.v1";
    public static final String ISSUE_TRANSITIONED = "trakr.issue.transitioned.v1";
    public static final String ISSUE_MOVED = "trakr.issue.moved.v1";
    public static final String ISSUE_DELETED = "trakr.issue.deleted.v1";
    public static final String ISSUE_LINKED = "trakr.issue.linked.v1";
    public static final String ISSUE_WORK_LOGGED = "trakr.issue.work-logged.v1";
    /** Emitted by the api after it applied a collab snapshot (phase 16). */
    public static final String ISSUE_DESCRIPTION_CHANGED = "trakr.issue.description-changed.v1";

    public static final String PROJECT_CREATED = "trakr.project.created.v1";
    public static final String PROJECT_UPDATED = "trakr.project.updated.v1";
    public static final String PROJECT_ARCHIVED = "trakr.project.archived.v1";
    public static final String PROJECT_MEMBER_CHANGED = "trakr.project.member-changed.v1";

    public static final String SPRINT_CREATED = "trakr.sprint.created.v1";
    public static final String SPRINT_UPDATED = "trakr.sprint.updated.v1";
    public static final String SPRINT_STARTED = "trakr.sprint.started.v1";
    public static final String SPRINT_COMPLETED = "trakr.sprint.completed.v1";
    public static final String SPRINT_DELETED = "trakr.sprint.deleted.v1";

    public static final String COMMENT_ADDED = "trakr.comment.added.v1";
    public static final String COMMENT_EDITED = "trakr.comment.edited.v1";
    public static final String COMMENT_DELETED = "trakr.comment.deleted.v1";

    public static final String NOTIFICATION_CREATED = "trakr.notification.created.v1";

    // produced by the collab app on trakr.collab.v1 (phase 16)
    public static final String COLLAB_UPDATE = "trakr.collab.update.v1";
    public static final String COLLAB_AWARENESS = "trakr.collab.awareness.v1";
    public static final String COLLAB_SNAPSHOT = "trakr.collab.snapshot.v1";

    // which record a type's data is, for consumers that turn an envelope's data back into the event
    private static final Map<String, Class<? extends TrakrEvent>> CLASSES = Map.ofEntries(
            Map.entry(ISSUE_CREATED, IssueCreated.class),
            Map.entry(ISSUE_UPDATED, IssueUpdated.class),
            Map.entry(ISSUE_TRANSITIONED, IssueTransitioned.class),
            Map.entry(ISSUE_MOVED, IssueMoved.class),
            Map.entry(ISSUE_DELETED, IssueDeleted.class),
            Map.entry(ISSUE_LINKED, IssueLinked.class),
            Map.entry(ISSUE_WORK_LOGGED, WorkLogged.class),
            Map.entry(ISSUE_DESCRIPTION_CHANGED, IssueDescriptionChanged.class),
            Map.entry(PROJECT_CREATED, ProjectLifecycle.Created.class),
            Map.entry(PROJECT_UPDATED, ProjectLifecycle.Updated.class),
            Map.entry(PROJECT_ARCHIVED, ProjectLifecycle.Archived.class),
            Map.entry(PROJECT_MEMBER_CHANGED, ProjectLifecycle.MemberChanged.class),
            Map.entry(SPRINT_CREATED, SprintEvent.SprintCreated.class),
            Map.entry(SPRINT_UPDATED, SprintEvent.SprintUpdated.class),
            Map.entry(SPRINT_STARTED, SprintEvent.SprintStarted.class),
            Map.entry(SPRINT_COMPLETED, SprintEvent.SprintCompleted.class),
            Map.entry(SPRINT_DELETED, SprintEvent.SprintDeleted.class),
            Map.entry(COMMENT_ADDED, CommentEvent.CommentAdded.class),
            Map.entry(COMMENT_EDITED, CommentEvent.CommentEdited.class),
            Map.entry(COMMENT_DELETED, CommentEvent.CommentDeleted.class),
            Map.entry(NOTIFICATION_CREATED, NotificationEvent.NotificationCreated.class),
            Map.entry(COLLAB_UPDATE, CollabEvent.CollabUpdate.class),
            Map.entry(COLLAB_AWARENESS, CollabEvent.CollabAwareness.class),
            Map.entry(COLLAB_SNAPSHOT, CollabEvent.CollabSnapshot.class));

    private EventTypes() {
    }

    /** The record for a type; empty for a type this version doesn't know (a newer producer, or garbage). */
    public static Optional<Class<? extends TrakrEvent>> classOf(String type) {
        return Optional.ofNullable(CLASSES.get(type));
    }

    /** {@code trakr.issue.moved.v1} → {@code issue.moved}: the SSE event name (03-api §4). */
    public static String sseName(String type) {
        String withoutPrefix = type.startsWith("trakr.") ? type.substring("trakr.".length()) : type;
        int version = withoutPrefix.lastIndexOf(".v");
        return version > 0 ? withoutPrefix.substring(0, version) : withoutPrefix;
    }
}
