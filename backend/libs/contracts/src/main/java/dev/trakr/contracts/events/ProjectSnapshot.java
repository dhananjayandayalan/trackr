package dev.trakr.contracts.events;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A project's columns at one version; enumerations as their wire values ("scrum", "private"). */
public record ProjectSnapshot(UUID id, UUID workspaceId, String key, String name, @Nullable String description,
        String template, UUID leadId, String access, String color, @Nullable Instant archivedAt, long version) {
}
