package dev.trakr.contracts.events;

/**
 * BLOCK 2: per-topic sub-interfaces. Events on {@code trakr.issues.v1}, keyed by the issue id: every
 * event about one issue goes to the same partition, so consumers see them in the order they happened.
 */
public sealed interface IssueEvent extends TrakrEvent
        permits IssueCreated, IssueUpdated, IssueTransitioned, IssueMoved, IssueDeleted, IssueLinked, WorkLogged {

    @Override
    default String subject() {
        return "issue/" + aggregateId();
    }
}
