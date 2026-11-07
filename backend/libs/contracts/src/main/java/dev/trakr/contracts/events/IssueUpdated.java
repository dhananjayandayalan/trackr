package dev.trakr.contracts.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.trakr.kernel.Ids;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code trakr.issue.updated.v1}: {@code changes} is keyed by field name, e.g. {@code {"summary": {old, new}}}. */
public record IssueUpdated(UUID eventId, Instant occurredAt, UUID actorId, Map<String, Change> changes,
        IssueSnapshot snapshot) implements TrakrEvent {

    /** One field's values before and after; "new" is a Java keyword, so the JSON names are set by hand. */
    public record Change(@JsonProperty("old") @Nullable Object oldValue,
            @JsonProperty("new") @Nullable Object newValue) {
    }

    public IssueUpdated {
        changes = Map.copyOf(changes);
    }

    public static IssueUpdated of(Instant occurredAt, UUID actorId, Map<String, Change> changes,
            IssueSnapshot snapshot) {
        return new IssueUpdated(Ids.newId(), occurredAt, actorId, changes, snapshot);
    }

    @Override
    public String type() {
        return "trakr.issue.updated.v1";
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
