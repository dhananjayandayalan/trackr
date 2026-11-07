package dev.trakr.api.sprint;

import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The inputs of SprintService, already checked for shape by the controller. */
public final class SprintCommands {

    private SprintCommands() {
    }

    /** POST /sprints/{id}/start (00-product §4.5: name and dates required). */
    public record StartSprint(String name, @Nullable String goal, LocalDate startsOn, LocalDate endsOn) {
    }

    /**
     * PATCH /sprints/{id} as merge patch: a null field is not in the patch; {@code Field(null)} clears it.
     * The name can't be cleared, so it is a plain nullable value.
     */
    public record SprintPatch(@Nullable String name, @Nullable Field<String> goal, @Nullable Field<LocalDate> startsOn,
            @Nullable Field<LocalDate> endsOn) {
    }

    public record Field<T>(@Nullable T value) {
    }

    /** Where the open issues go when a sprint completes: the backlog ({@code sprintId} null) or a planned sprint. */
    public record CompleteSprint(@Nullable UUID moveOpenIssuesToSprintId) {
    }
}
