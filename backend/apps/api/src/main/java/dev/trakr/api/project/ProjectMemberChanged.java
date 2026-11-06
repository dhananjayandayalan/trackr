package dev.trakr.api.project;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A member was added or got a new role; {@code role} is null when they were removed. */
public record ProjectMemberChanged(UUID projectId, UUID userId, @Nullable ProjectRole role, UUID actorId) {
}
