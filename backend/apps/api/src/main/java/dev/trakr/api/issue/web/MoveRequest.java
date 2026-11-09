package dev.trakr.api.issue.web;

import dev.trakr.api.issue.MoveIssue;
import dev.trakr.api.issue.PatchIssue.Value;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * POST /issues/{key}/move, bound to a Map: {@code "toSprintId": null} (to the backlog) and no
 * {@code toSprintId} at all (stay in the sprint) mean different things (03-api §3.4). The record only
 * documents the shape for OpenAPI. For the other optional fields null and absent mean the same thing — no
 * status change, no neighbour — because the document says they are nullable, and generated clients send null.
 */
record MoveRequest(@Nullable UUID toStatusId, @Nullable UUID toSprintId, @Nullable String beforeKey,
        @Nullable String afterKey, long expectedVersion) {

    private static final Set<String> FIELDS = Set.of("toStatusId", "toSprintId", "beforeKey", "afterKey",
            "expectedVersion");

    static MoveIssue parse(Map<String, @Nullable Object> body) {
        MergePatch p = new MergePatch(body);
        p.rejectUnknown(FIELDS, "is not a move field");

        Value<UUID> toStatusId = p.optional("toStatusId", String.class,
                value -> p.parse("toStatusId", value, UUID::fromString, "must be a status id"));
        Value<UUID> toSprintId = p.optional("toSprintId", String.class,
                value -> p.parse("toSprintId", value, UUID::fromString, "must be a sprint id"));
        Value<String> beforeKey = p.optional("beforeKey", String.class, value -> key(p, "beforeKey", value));
        Value<String> afterKey = p.optional("afterKey", String.class, value -> key(p, "afterKey", value));
        Value<Long> version = p.required("expectedVersion", Number.class, value -> {
            p.check("expectedVersion", value.longValue() >= 0 && value.doubleValue() == value.longValue(),
                    "must be the version you last saw");
            return value.longValue();
        });
        p.check("expectedVersion", version != null, "is required");
        p.throwIfInvalid("The move has invalid fields.");

        return new MoveIssue(value(toStatusId), toSprintId, value(beforeKey), value(afterKey),
                version == null || version.value() == null ? 0 : version.value());
    }

    private static String key(MergePatch p, String field, String value) {
        p.check(field, value.matches(IssueRequests.KEY), "must be an issue key");
        return value;
    }

    private static <T> @Nullable T value(@Nullable Value<T> value) {
        return value == null ? null : value.value();
    }
}
