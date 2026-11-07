package dev.trakr.contracts.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A sprint's columns at one version; {@code state} is planned, active or closed. */
public record SprintSnapshot(
        UUID id,
        UUID workspaceId,
        UUID projectId,
        String name,
        @Nullable String goal,
        String state,
        @Nullable LocalDate startsOn,
        @Nullable LocalDate endsOn,
        int position,
        @Nullable Instant startedAt,
        @Nullable Instant completedAt,
        @Nullable BigDecimal committedPoints,
        long version) {
}
