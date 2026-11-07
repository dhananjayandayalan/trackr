package dev.trakr.api.issue;

import dev.trakr.api.issue.PatchIssue.Value;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * POST /issues/{key}/move. {@code toSprintId}: null = leave the sprint alone, Value(null) = to the
 * backlog, Value(id) = into that sprint. {@code beforeKey} / {@code afterKey}: the issues that should
 * end up directly above / below it in the destination list.
 */
public record MoveIssue(@Nullable UUID toStatusId, @Nullable Value<UUID> toSprintId, @Nullable String beforeKey,
        @Nullable String afterKey, long expectedVersion) {
}
