package dev.trakr.api.project;

import java.util.UUID;

/** Name, description, lead, access or colour changed; {@code version} is the project's version afterwards. */
public record ProjectUpdated(UUID projectId, UUID workspaceId, String key, long version, UUID actorId) {
}
