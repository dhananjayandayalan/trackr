package dev.trakr.api.project;

import java.util.UUID;

/**
 * Published inside ProjectService.create's transaction. The workflow and board modules seed the new
 * project from it, so project never has to know they exist.
 */
public record ProjectCreated(UUID projectId, UUID workspaceId, String key, Template template, UUID actorId) {
}
