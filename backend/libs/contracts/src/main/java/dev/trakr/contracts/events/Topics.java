package dev.trakr.contracts.events;

/** Kafka topic names (04-events §4). The record key on every topic is the aggregate the order matters for. */
public final class Topics {

    /** Key: issue id — all events about one issue land on one partition, in order. */
    public static final String ISSUES = "trakr.issues.v1";
    /** Key: project id — project, member, workflow and sprint events. */
    public static final String PROJECTS = "trakr.projects.v1";
    /** Key: issue id (phase 15). */
    public static final String COMMENTS = "trakr.comments.v1";
    /** Key: user id; produced by workers (phase 10). */
    public static final String NOTIFICATIONS = "trakr.notifications.v1";

    private Topics() {
    }
}
