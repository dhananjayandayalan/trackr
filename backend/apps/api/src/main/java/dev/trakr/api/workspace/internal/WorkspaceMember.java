package dev.trakr.api.workspace.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workspace_member")
@IdClass(WorkspaceMember.Key.class)
public class WorkspaceMember {

    /** The two-column primary key (workspace_id, user_id). */
    public record Key(UUID workspaceId, UUID userId) implements Serializable {
    }

    @Id
    private UUID workspaceId;
    @Id
    private UUID userId;
    private String role;
    private Instant joinedAt;

    protected WorkspaceMember() {
        // for JPA
    }

    public static WorkspaceMember owner(UUID workspaceId, UUID userId, Clock clock) {
        return join(workspaceId, userId, "owner", clock);
    }

    public static WorkspaceMember member(UUID workspaceId, UUID userId, Clock clock) {
        return join(workspaceId, userId, "member", clock);
    }

    private static WorkspaceMember join(UUID workspaceId, UUID userId, String role, Clock clock) {
        WorkspaceMember member = new WorkspaceMember();
        member.workspaceId = workspaceId;
        member.userId = userId;
        member.role = role;
        member.joinedAt = clock.instant();
        return member;
    }

    public String role() {
        return role;
    }
}
