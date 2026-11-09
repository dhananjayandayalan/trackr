package dev.trakr.api.issue;

import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * POST /issues/bulk: one action on many issues. The argument that goes with it: {@code statusId}
 * (transition), {@code assigneeId} (assign; null = unassign), {@code sprintId} (moveToSprint; null =
 * backlog). Bulk edits act on each issue's current version: the user picked the issues from a list,
 * not from a version they read.
 */
public record BulkCommand(List<String> keys, Action action, @Nullable UUID statusId, @Nullable UUID assigneeId,
        @Nullable UUID sprintId) {

    public enum Action {
        TRANSITION("transition"),
        ASSIGN("assign"),
        MOVE_TO_SPRINT("moveToSprint"),
        DELETE("delete");

        private final String value;

        Action(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }
    }

    public BulkCommand {
        keys = List.copyOf(keys);
    }

    /** One issue's outcome: ok with its new version (null after delete), or the error code and message. */
    @Schema(name = "BulkResult")
    public record Result(String key, boolean ok, @Nullable Long version, @Nullable String code,
            @Nullable String message) {

        static Result ok(String key, @Nullable Long version) {
            return new Result(key, true, version, null, null);
        }

        static Result failed(String key, String code, String message) {
            return new Result(key, false, null, code, message);
        }
    }
}
