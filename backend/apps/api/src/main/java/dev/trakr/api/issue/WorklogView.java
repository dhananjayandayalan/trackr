package dev.trakr.api.issue;

import dev.trakr.api.issue.IssueDetails.UserRef;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record WorklogView(UUID id, UserRef user, Instant startedAt, int minutes, @Nullable String note) {
}
