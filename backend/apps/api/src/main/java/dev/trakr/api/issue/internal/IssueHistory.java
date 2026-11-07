package dev.trakr.api.issue.internal;

import dev.trakr.api.issue.internal.IssueEventRecord.Kind;
import dev.trakr.api.workflow.WorkflowStatusView;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns what happened to an issue into issue_event rows (02-data-model: history as data). Called by
 * IssueService inside the same transaction as the change, so history and change commit together.
 *
 * <p>The issue row must already be in the database (flushed): issue_event's foreign key points at it,
 * and an identity-keyed entity is inserted the moment it's saved, before any pending issue INSERT.
 */
@Component
public class IssueHistory {

    private final IssueEventRepository events;
    private final JsonMapper json;
    private final Clock clock;

    IssueHistory(IssueEventRepository events, JsonMapper json, Clock clock) {
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    public void created(Issue issue, UUID actorId) {
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("type", issue.type().value());
        created.put("summary", issue.summary());
        save(issue, actorId, Kind.CREATED, null, null, created);
    }

    /** One row per changed field, so the History tab can show "Priority: Medium → High". */
    public void changed(Issue issue, UUID actorId, List<FieldChange> changes) {
        for (FieldChange change : changes) {
            save(issue, actorId, Kind.FIELD_CHANGED, change.field(), change.oldValue(), change.newValue());
        }
    }

    // id and name: the name as it was then, so renaming a status later doesn't rewrite history
    public void transitioned(Issue issue, UUID actorId, WorkflowStatusView from, WorkflowStatusView to) {
        save(issue, actorId, Kind.TRANSITIONED, "status", Map.of("id", from.id().toString(), "name", from.name()),
                Map.of("id", to.id().toString(), "name", to.name()));
    }

    /** Where it was and where it is: field "sprint" when the list changed, "rank" for a re-order within it. */
    public void moved(Issue issue, UUID actorId, @Nullable UUID fromSprintId, String fromRank) {
        boolean sprintChanged = !Objects.equals(fromSprintId, issue.sprintId());
        save(issue, actorId, Kind.MOVED, sprintChanged ? "sprint" : "rank", position(fromSprintId, fromRank),
                position(issue.sprintId(), issue.rank()));
    }

    private static Map<String, @Nullable Object> position(@Nullable UUID sprintId, String rank) {
        Map<String, @Nullable Object> position = new LinkedHashMap<>();
        position.put("sprintId", sprintId == null ? null : sprintId.toString());
        position.put("rank", rank);
        return position;
    }

    public void deleted(Issue issue, UUID actorId) {
        save(issue, actorId, Kind.DELETED, null, null, null);
    }

    /** {@code label} as read from this issue ("blocks"); old null = added, new null = removed. */
    public void linked(Issue issue, UUID actorId, String label, @Nullable Object oldLink, @Nullable Object newLink) {
        save(issue, actorId, Kind.LINKED, label, oldLink, newLink);
    }

    public void loggedWork(Issue issue, UUID actorId, UUID worklogId, int minutes) {
        save(issue, actorId, Kind.LOGGED_WORK, null, null,
                Map.of("worklogId", worklogId.toString(), "minutes", minutes));
    }

    private void save(Issue issue, UUID actorId, Kind kind, @Nullable String field, @Nullable Object oldValue,
            @Nullable Object newValue) {
        events.save(IssueEventRecord.of(issue.id(), actorId, clock.instant(), kind, field, toJson(oldValue),
                toJson(newValue)));
    }

    // null stays SQL NULL (no value), not the JSON literal null
    private @Nullable String toJson(@Nullable Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }
}
