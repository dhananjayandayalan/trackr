package dev.trakr.api.home;

import com.fasterxml.jackson.annotation.JsonValue;
import dev.trakr.api.issue.IssueDetails.UserRef;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The response records of /me/work, /me/stats and /me/activity (03-api §3.1). */
public final class HomeViews {

    private HomeViews() {
    }

    /** The four tabs of "Your work". */
    public enum Tab {
        ASSIGNED("assigned"),
        WORKED("worked"),
        VIEWED("viewed"),
        STARRED("starred");

        private final String value;

        Tab(String value) {
            this.value = value;
        }

        @JsonValue
        public String value() {
            return value;
        }

        public static Tab fromValue(String value) {
            for (Tab candidate : values()) {
                if (candidate.value.equals(value)) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unknown tab: " + value);
        }
    }

    /**
     * One issue in a tab. {@code at} is what the tab sorts by: last update (assigned), my last change
     * (worked), when I opened it (viewed), when I starred it (starred).
     */
    public record IssueRow(UUID id, String key, String projectKey, String summary, String type, String priority,
            String statusName, String statusCategory, String statusColor, @Nullable UUID assigneeId, Instant at) {
    }

    /** {@code unreadMentions} stays 0 until notifications exist (phase 10). */
    public record Stats(int assignedOpen, int dueThisWeek, int waitingReview, int unreadMentions) {
    }

    /** One history entry from a project the user can see, newest first: the team activity feed. */
    public record ActivityItem(long id, String kind, @Nullable String field, @Nullable Object oldValue,
            @Nullable Object newValue, UserRef actor, String issueKey, String issueSummary, Instant occurredAt) {
    }
}
