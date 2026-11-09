package dev.trakr.workers.search;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What a document needs beyond the event's snapshot: names that live in other tables. NameLookup
 * fills it from Postgres (cached); tests build it by hand.
 */
public record IssueContext(@Nullable String statusName, @Nullable String assigneeName, @Nullable UUID epicId,
        @Nullable String epicName, @Nullable String sprintName, @Nullable String sprintState, List<UUID> watcherIds) {

    public IssueContext {
        watcherIds = List.copyOf(watcherIds);
    }

    public static IssueContext empty() {
        return new IssueContext(null, null, null, null, null, null, List.of());
    }
}
