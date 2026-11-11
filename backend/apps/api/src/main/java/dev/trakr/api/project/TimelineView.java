package dev.trakr.api.project;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * GET /projects/{key}/timeline (03-api §3.9): the project's epics with their dates and progress, each with its
 * children. {@code version} on every row is what a drag sends back in If-Match (PATCH /issues/{key}).
 */
public record TimelineView(List<Epic> epics) {

    /** {@code progressPct}: done children of all children, 0–100 (0 for an epic without children). */
    @Schema(name = "TimelineEpic")
    public record Epic(UUID id, String key, String summary, String statusCategory, @Nullable LocalDate startDate,
            @Nullable LocalDate dueDate, int childCount, int doneCount, int progressPct, long version,
            List<Child> children) {
    }

    @Schema(name = "TimelineChild")
    public record Child(UUID id, String key, String summary, String type, String statusCategory,
            @Nullable LocalDate startDate, @Nullable LocalDate dueDate, @Nullable UUID assigneeId, long version) {
    }
}
