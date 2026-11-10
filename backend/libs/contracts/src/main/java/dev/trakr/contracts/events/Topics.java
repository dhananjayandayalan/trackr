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
    /** Key: issue id; produced by collab (phase 16): the cross-instance relay and description snapshots. */
    public static final String COLLAB = "trakr.collab.v1";

    /** Where a consumer sends what it can't process, for a human to look at: {@code trakr.issues.v1.dlt}. */
    public static String deadLetter(String topic) {
        return topic + ".dlt";
    }

    private Topics() {
    }
}
