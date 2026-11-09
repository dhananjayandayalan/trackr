package dev.trakr.api.search;

import com.fasterxml.jackson.annotation.JsonValue;
import dev.trakr.api.issue.IssueDetails.UserRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The request and response records of /search and /filters (03-api §3.8). */
public final class SearchViews {

    private SearchViews() {
    }

    /**
     * One result row: what the issues table shows, straight from the index (no database join after the
     * search). {@code highlights}: field → fragments with the matched words in {@code <mark>…</mark>};
     * the rest of the text is HTML-escaped, so the fragments can be rendered as HTML safely.
     */
    public record SearchHit(UUID id, String key, String projectKey, String summary, String type, String priority,
            @Nullable String statusName, String statusCategory, @Nullable UUID assigneeId,
            @Nullable String assigneeName, @Nullable String sprintName, @Nullable BigDecimal storyPoints,
            List<String> labels, boolean flagged, @Nullable LocalDate dueDate, Instant createdAt, Instant updatedAt,
            Map<String, List<String>> highlights) {
    }

    /**
     * A page of results. {@code total} counts every match (exact up to 10,000); {@code nextCursor} is null on
     * the last page; {@code tookMs} is Elasticsearch's own time for the query.
     */
    public record SearchPage(long total, List<SearchHit> items, @Nullable String nextCursor, long tookMs) {
    }

    /** The top-nav typeahead: a few of each kind (05-search §4). */
    public record Suggestions(List<IssueSuggestion> issues, List<ProjectSuggestion> projects, List<UserRef> people) {
    }

    public record IssueSuggestion(String key, String summary, String type, String statusCategory) {
    }

    public record ProjectSuggestion(String key, String name, String color) {
    }

    /** What autocomplete offers. */
    public enum CompletionKind {
        FIELD, OPERATOR, VALUE, KEYWORD;

        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Completion(CompletionKind kind, String text) {
    }

    /** {@code from}: the offset where the word being typed starts; a picked suggestion replaces from there to the caret. */
    public record Completions(int from, List<Completion> expected) {
    }

    /** A saved filter as the filter picker lists it; {@code mine}: the caller owns it and may change it. */
    public record FilterView(UUID id, String name, String tql, boolean starred, boolean shared, boolean mine,
            UserRef owner, Instant createdAt) {
    }

    public record NewFilter(String name, String tql, boolean starred, boolean shared) {
    }

    /** Absent (null) fields stay as they are. */
    public record FilterPatch(@Nullable String name, @Nullable String tql, @Nullable Boolean starred,
            @Nullable Boolean shared) {
    }
}
