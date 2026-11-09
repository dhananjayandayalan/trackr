package dev.trakr.contracts.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An integration event: a fact other apps may learn about (04-events §3).
 *
 * <p>BLOCK 1: sealed event hierarchy. Sealed, and split by topic below: a consumer's {@code switch}
 * over the event types is checked by the compiler — add a new event type and every switch that
 * doesn't handle it stops compiling, instead of silently ignoring it at runtime. Collab events join
 * the list in phase 16 (a sealed interface can only name types that exist).
 *
 * <p>The envelope fields come from here: {@code id} = {@link #eventId()} (made when the event is
 * created, so retries keep it and consumers can dedupe on it), {@code sequence} = the aggregate's
 * version after the change (consumers drop anything older than what they've seen).
 */
public sealed interface TrakrEvent permits IssueEvent, ProjectEvent, CommentEvent, NotificationEvent {

    UUID eventId();

    /** CloudEvents type, e.g. {@code trakr.issue.created.v1} (EventTypes). */
    String type();

    Instant occurredAt();

    UUID actorId();

    UUID workspaceId();

    UUID projectId();

    /** What the event is about; also the order: one aggregate's events keep their order. */
    UUID aggregateId();

    /** CloudEvents subject, e.g. {@code issue/0190f2aa-…}. */
    String subject();

    long sequence();
}
