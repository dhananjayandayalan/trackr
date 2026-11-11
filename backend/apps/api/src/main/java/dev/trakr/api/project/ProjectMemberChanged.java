package dev.trakr.api.project;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A member was added or got a new role; {@code role} is null when they were removed, {@code previousRole} null
 * when they were not a member before. In-process only; the audit log (phase 18) records it as before → after.
 */
public record ProjectMemberChanged(UUID projectId, UUID workspaceId, UUID userId, @Nullable ProjectRole role,
        @Nullable ProjectRole previousRole, UUID actorId) {
}
