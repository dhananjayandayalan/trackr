package dev.trakr.api.issue.internal;

import org.jspecify.annotations.Nullable;

/**
 * One field's change, as Issue.apply reports it. The values are already JSON-friendly (strings,
 * numbers, booleans, lists of strings, null): they go into issue_event and into IssueUpdated as is.
 */
public record FieldChange(String field, @Nullable Object oldValue, @Nullable Object newValue) {
}
