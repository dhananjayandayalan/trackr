package dev.trakr.api.sprint;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A sprint's own fields; {@code version} is what the ETag carries. */
public record SprintView(UUID id, UUID projectId, String name, @Nullable String goal, SprintState state,
        @Nullable LocalDate startsOn, @Nullable LocalDate endsOn, int position, @Nullable Instant startedAt,
        @Nullable Instant completedAt, @Nullable BigDecimal committedPoints, long version) {
}
