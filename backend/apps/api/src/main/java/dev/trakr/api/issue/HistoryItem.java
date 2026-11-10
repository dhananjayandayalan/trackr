package dev.trakr.api.issue;

import dev.trakr.api.issue.IssueDetails.UserRef;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One row of the History tab. {@code kind}: created, field_changed, transitioned, linked,
 * moved, commented, logged_work, deleted. The values are JSON as
 * stored: a string, number, list or object, or null.
 */
public record HistoryItem(long id, String kind, @Nullable String field, @Nullable Object oldValue,
        @Nullable Object newValue, UserRef actor, Instant occurredAt) {
}
