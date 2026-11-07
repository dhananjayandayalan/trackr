package dev.trakr.api.issue.web;

import dev.trakr.api.issue.IssueType;
import dev.trakr.api.issue.PatchIssue;
import dev.trakr.api.issue.PatchIssue.Value;
import dev.trakr.api.issue.Priority;
import dev.trakr.api.shared.web.TrakrException.FieldMessage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * PATCH /issues/{key} as JSON merge patch, parsed from a Map for the same reason as
 * ProjectPatchRequest: only a Map tells {"assigneeId": null} (unassign) from {} (leave it). The
 * record itself only documents the shape for OpenAPI. Status and sprint are not here: they change
 * through /transition and /move.
 */
record IssuePatchRequest(String summary, @Nullable String description, IssueType type, Priority priority,
        @Nullable UUID assigneeId, @Nullable String parentKey, @Nullable BigDecimal storyPoints, List<String> labels,
        @Nullable LocalDate dueDate, @Nullable LocalDate startDate, Boolean flagged, @Nullable String fixVersion,
        @Nullable Integer originalEstimateMin) {

    private static final Set<String> FIELDS = Set.of("summary", "description", "type", "priority", "assigneeId",
            "parentKey", "storyPoints", "labels", "dueDate", "startDate", "flagged", "fixVersion",
            "originalEstimateMin");
    private static final BigDecimal MAX_POINTS = new BigDecimal("100");

    static PatchIssue parse(Map<String, @Nullable Object> body) {
        MergePatch p = new MergePatch(body);
        p.rejectUnknown(FIELDS, "is not a field you can change here");

        Value<String> summary = p.required("summary", String.class, value -> {
            p.check("summary", !value.isBlank(), "must not be blank");
            p.check("summary", value.length() <= 255, "must be at most 255 characters");
            return value;
        });
        Value<String> description = p.optional("description", String.class, value -> {
            p.check("description", value.length() <= 50_000, "must be at most 50000 characters");
            return value;
        });
        Value<IssueType> type = p.required("type", String.class, value -> p.parse("type", value, IssueType::fromValue,
                "must be story, bug, task, epic or subtask"));
        Value<Priority> priority = p.required("priority", String.class, value -> p.parse("priority", value,
                Priority::fromValue, "must be highest, high, medium, low or lowest"));
        Value<UUID> assigneeId = p.optional("assigneeId", String.class, value -> p.parse("assigneeId", value,
                UUID::fromString, "must be a user id"));
        Value<String> parentKey = p.optional("parentKey", String.class, value -> {
            p.check("parentKey", value.matches(IssueRequests.KEY), "must be an issue key");
            return value;
        });
        Value<BigDecimal> storyPoints = p.optional("storyPoints", Number.class, value -> {
            BigDecimal points = new BigDecimal(value.toString());
            p.check("storyPoints", points.signum() >= 0 && points.compareTo(MAX_POINTS) <= 0, "must be between 0 and 100");
            p.check("storyPoints", points.stripTrailingZeros().scale() <= 1, "must have at most one decimal");
            return points;
        });
        Value<List<String>> labels = p.required("labels", Object.class, value -> labels(value, p));
        Value<LocalDate> dueDate = p.optional("dueDate", String.class, value -> p.parse("dueDate", value,
                LocalDate::parse, "must be a date like 2026-10-22"));
        Value<LocalDate> startDate = p.optional("startDate", String.class, value -> p.parse("startDate", value,
                LocalDate::parse, "must be a date like 2026-10-22"));
        Value<Boolean> flagged = p.required("flagged", Boolean.class, Function.identity());
        Value<String> fixVersion = p.optional("fixVersion", String.class, value -> {
            p.check("fixVersion", value.length() <= 50, "must be at most 50 characters");
            return value;
        });
        Value<Integer> estimate = p.optional("originalEstimateMin", Number.class, value -> {
            p.check("originalEstimateMin", value.doubleValue() == value.intValue() && value.intValue() >= 0,
                    "must be a whole number of minutes, 0 or more");
            return value.intValue();
        });

        p.throwIfInvalid("The patch has invalid fields.");
        return new PatchIssue(summary, description, type, priority, assigneeId, parentKey, storyPoints, labels,
                dueDate, startDate, flagged, fixVersion, estimate);
    }

    private static List<String> labels(Object raw, MergePatch p) {
        if (!(raw instanceof List<?> values)) {
            p.error(new FieldMessage("labels", "must be a list of strings"));
            return List.of();
        }
        p.check("labels", values.size() <= 20, "must have at most 20 labels");
        List<String> labels = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof String label && label.matches(IssueRequests.LABEL) && label.length() <= 50) {
                labels.add(label);
            } else {
                p.error(new FieldMessage("labels", "each label must be 1 to 50 characters without spaces"));
                break;
            }
        }
        return labels;
    }
}
