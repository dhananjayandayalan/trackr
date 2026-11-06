package dev.trakr.api.project;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A project's own fields; {@code version} is what the ETag carries. */
public record ProjectView(UUID id, UUID workspaceId, String key, String name, @Nullable String description,
        Template template, UUID leadId, Access access, String color, @Nullable Instant archivedAt, long version,
        Instant createdAt, Instant updatedAt) {
}
