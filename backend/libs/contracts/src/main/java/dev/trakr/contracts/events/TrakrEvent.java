package dev.trakr.contracts.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An integration event: a fact other apps may learn about (04-events §3). Sealed, so a consumer's
 * {@code switch} over the event types is checked for completeness by the compiler.
 *
 * <p>The envelope fields come from here: {@code id} = {@link #eventId()} (made when the event is
 * created, so retries keep it and consumers can dedupe on it), {@code sequence} = the aggregate's
 * version after the change (consumers drop anything at or below the last one they saw).
 */
public sealed interface TrakrEvent
        permits IssueCreated, IssueUpdated, IssueTransitioned, IssueDeleted, IssueLinked, WorkLogged {

    UUID eventId();

    /** CloudEvents type, e.g. {@code trakr.issue.created.v1}. */
    String type();

    Instant occurredAt();

    UUID actorId();

    UUID workspaceId();

    UUID projectId();

    /** The Kafka record key: events about one aggregate stay in order on one partition. */
    UUID aggregateId();

    /** CloudEvents subject, e.g. {@code issue/0190f2aa-…}. */
    default String subject() {
        return "issue/" + aggregateId();
    }

    long sequence();
}
