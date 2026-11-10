package dev.trakr.contracts.events;

import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code trakr.issue.description-changed.v1}: the api applied a collaborative-editing snapshot to the issue
 * (06-collab §4 step 5). {@code text}/{@code html} are the sanitised description, null when it was emptied; the
 * snapshot carries them too ({@code descriptionText}), so the search indexer treats this like any other update.
 */
public record IssueDescriptionChanged(UUID eventId, Instant occurredAt, UUID actorId, @Nullable String text,
        @Nullable String html, IssueSnapshot snapshot) implements IssueEvent {

    public static IssueDescriptionChanged of(Instant occurredAt, UUID actorId, @Nullable String text,
            @Nullable String html, IssueSnapshot snapshot) {
        return new IssueDescriptionChanged(Ids.newId(), occurredAt, actorId, text, html, snapshot);
    }

    @Override
    public String type() {
        return EventTypes.ISSUE_DESCRIPTION_CHANGED;
    }

    @Override
    public UUID workspaceId() {
        return snapshot.workspaceId();
    }

    @Override
    public UUID projectId() {
        return snapshot.projectId();
    }

    @Override
    public UUID aggregateId() {
        return snapshot.id();
    }

    @Override
    public long sequence() {
        return snapshot.version();
    }
}
