package dev.trakr.api.issue;

import dev.trakr.api.project.ProjectRole;
import java.util.UUID;

/**
 * Proof that the caller may work with this issue, for modules that hang things off an issue (comments,
 * attachments): which issue, which project and workspace, and the caller's role there. Like
 * ProjectAccess.Grant, only IssueService hands these out, after the check.
 */
public record IssueHandle(UUID id, String key, UUID projectId, UUID workspaceId, ProjectRole role) {
}
