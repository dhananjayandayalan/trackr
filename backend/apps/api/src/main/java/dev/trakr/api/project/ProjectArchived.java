package dev.trakr.api.project;

import java.util.UUID;

public record ProjectArchived(UUID projectId, UUID workspaceId, String key, UUID actorId) {
}
