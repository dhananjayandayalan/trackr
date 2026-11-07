package dev.trakr.api.issue.web;

import dev.trakr.api.issue.BulkCommand;
import dev.trakr.api.issue.LinkType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The small request bodies of the issue endpoints, and patterns they share. */
final class IssueRequests {

    static final String KEY = "^[A-Z]{2,6}-[1-9][0-9]{0,17}$";
    static final String LABEL = "^\\S+$";

    private IssueRequests() {
    }

    record TransitionRequest(@NotNull UUID toStatusId, @NotNull @Min(0) Long expectedVersion) {
    }

    record LinkRequest(@NotNull @Pattern(regexp = KEY, message = "must be an issue key") String targetKey,
            @NotNull LinkType type) {
    }

    /** {@code args} holds what the action needs: statusId, assigneeId (null = unassign) or sprintId (null = backlog). */
    record BulkRequest(
            @NotEmpty @Size(max = 100) List<@NotNull @Pattern(regexp = KEY, message = "must be an issue key") String> keys,
            @NotNull BulkCommand.Action action,
            @Nullable BulkArgs args) {

        BulkCommand toCommand() {
            BulkArgs a = args == null ? new BulkArgs(null, null, null) : args;
            return new BulkCommand(keys, action, a.statusId(), a.assigneeId(), a.sprintId());
        }
    }

    record BulkArgs(@Nullable UUID statusId, @Nullable UUID assigneeId, @Nullable UUID sprintId) {
    }

    // at most a week in one entry
    record WorklogRequest(@NotNull Instant startedAt, @NotNull @Min(1) @Max(10_080) Integer minutes,
            @Nullable @Size(max = 2000) String note) {
    }
}
