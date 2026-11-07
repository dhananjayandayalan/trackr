package dev.trakr.api.issue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * PATCH /issues/{key} as JSON merge patch. Every field is a {@link Value} or null: null means "not
 * in the patch, leave it"; {@code Value(null)} means "clear it"; {@code Value(x)} means "set to x".
 * A plain nullable field couldn't tell the first two apart.
 */
public record PatchIssue(
        @Nullable Value<String> summary,
        @Nullable Value<String> description,
        @Nullable Value<IssueType> type,
        @Nullable Value<Priority> priority,
        @Nullable Value<UUID> assigneeId,
        @Nullable Value<String> parentKey,
        @Nullable Value<BigDecimal> storyPoints,
        @Nullable Value<List<String>> labels,
        @Nullable Value<LocalDate> dueDate,
        @Nullable Value<LocalDate> startDate,
        @Nullable Value<Boolean> flagged,
        @Nullable Value<String> fixVersion,
        @Nullable Value<Integer> originalEstimateMin) {

    /** A field that is in the patch; {@code value} null = clear it. */
    public record Value<T>(@Nullable T value) {
    }

    /** A patch that changes nothing; add fields with the {@code with…} methods (tests, internal callers). */
    public static PatchIssue none() {
        return new PatchIssue(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public PatchIssue withSummary(String newSummary) {
        return new PatchIssue(new Value<>(newSummary), description, type, priority, assigneeId, parentKey,
                storyPoints, labels, dueDate, startDate, flagged, fixVersion, originalEstimateMin);
    }

    public PatchIssue withPriority(Priority newPriority) {
        return new PatchIssue(summary, description, type, new Value<>(newPriority), assigneeId, parentKey,
                storyPoints, labels, dueDate, startDate, flagged, fixVersion, originalEstimateMin);
    }
}
