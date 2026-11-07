package dev.trakr.contracts.events;

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

    public static final String PROJECT_CREATED = "trakr.project.created.v1";
    public static final String PROJECT_UPDATED = "trakr.project.updated.v1";
    public static final String PROJECT_ARCHIVED = "trakr.project.archived.v1";
    public static final String PROJECT_MEMBER_CHANGED = "trakr.project.member-changed.v1";

    public static final String SPRINT_CREATED = "trakr.sprint.created.v1";
    public static final String SPRINT_UPDATED = "trakr.sprint.updated.v1";
    public static final String SPRINT_STARTED = "trakr.sprint.started.v1";
    public static final String SPRINT_COMPLETED = "trakr.sprint.completed.v1";
    public static final String SPRINT_DELETED = "trakr.sprint.deleted.v1";

    private EventTypes() {
    }

    /** {@code trakr.issue.moved.v1} → {@code issue.moved}: the SSE event name (03-api §4). */
    public static String sseName(String type) {
        String withoutPrefix = type.startsWith("trakr.") ? type.substring("trakr.".length()) : type;
        int version = withoutPrefix.lastIndexOf(".v");
        return version > 0 ? withoutPrefix.substring(0, version) : withoutPrefix;
    }
}
