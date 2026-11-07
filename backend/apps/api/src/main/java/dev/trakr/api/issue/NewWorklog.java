package dev.trakr.api.issue;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** POST /issues/{key}/worklogs. */
public record NewWorklog(Instant startedAt, int minutes, @Nullable String note) {
}
