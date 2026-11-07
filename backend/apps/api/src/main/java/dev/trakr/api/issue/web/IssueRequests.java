package dev.trakr.api.issue.web;

import dev.trakr.api.issue.LinkType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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

    // at most a week in one entry
    record WorklogRequest(@NotNull Instant startedAt, @NotNull @Min(1) @Max(10_080) Integer minutes,
            @Nullable @Size(max = 2000) String note) {
    }
}
