package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code trakr.issue.linked.v1}, about the source issue: "source {@code linkType} target". Linking
 * bumps the source's version, so the event has a fresh sequence like every other issue event.
 */
public record IssueLinked(UUID eventId, Instant occurredAt, UUID actorId, UUID workspaceId, UUID projectId,
        UUID issueId, long sequence, UUID linkId, UUID targetIssueId, String linkType) implements IssueEvent {

    public static IssueLinked of(Instant occurredAt, UUID actorId, IssueSnapshot source, UUID linkId,
            UUID targetIssueId, String linkType) {
        return new IssueLinked(Ids.newId(), occurredAt, actorId, source.workspaceId(), source.projectId(), source.id(),
                source.version(), linkId, targetIssueId, linkType);
    }

    @Override
    public String type() {
        return EventTypes.ISSUE_LINKED;
    }

    @Override
    public UUID aggregateId() {
        return issueId;
    }
}
