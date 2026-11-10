package dev.trakr.collab.security;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * May this user open this issue's description, and may they change it? The same answer the api's ProjectAccess
 * gives (09-security §3), computed with one read-only query, because collab can't call into the api's code:
 * <ul>
 * <li>the issue's project must be in a workspace the user belongs to;</li>
 * <li>their role is the stronger of an explicit project_member row and what the project's access level gives every
 * workspace member: open → member, limited → viewer, private → nothing.</li>
 * </ul>
 * Keys are unique per workspace only; if two of the user's workspaces have the same key, the one they joined first
 * wins, like in the api.
 */
@Component
public class IssueAccess {

    /** Weakest first: {@code ordinal()} compares them. */
    public enum Role {
        VIEWER, MEMBER, ADMIN;

        public boolean canEdit() {
            return this != VIEWER;
        }

        static @Nullable Role of(@Nullable String value) {
            return value == null ? null : Role.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /** What the handshake proved: which issue (and its project and workspace), and the user's role there. */
    public record Grant(UUID issueId, String issueKey, UUID projectId, UUID workspaceId, Role role) {
    }

    private record Row(UUID issueId, String key, UUID projectId, UUID workspaceId, String access,
            @Nullable String memberRole) {
    }

    private final JdbcClient jdbc;

    IssueAccess(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Grant> find(UUID userId, String issueKey) {
        return jdbc.sql("""
                SELECT i.id AS issue_id, i.key, i.project_id, i.workspace_id, p.access, pm.role AS member_role
                FROM issue i
                JOIN project p ON p.id = i.project_id
                JOIN workspace_member wm ON wm.workspace_id = i.workspace_id AND wm.user_id = :userId
                LEFT JOIN project_member pm ON pm.project_id = i.project_id AND pm.user_id = :userId
                WHERE i.key = :key AND i.deleted_at IS NULL
                ORDER BY wm.joined_at
                LIMIT 1
                """)
                .param("userId", userId)
                .param("key", issueKey)
                .query(Row.class)
                .optional()
                .flatMap(row -> role(row).map(role ->
                        new Grant(row.issueId(), row.key(), row.projectId(), row.workspaceId(), role)));
    }

    private static Optional<Role> role(Row row) {
        Role explicit = Role.of(row.memberRole());
        Role implied = switch (row.access()) {
            case "open" -> Role.MEMBER;
            case "limited" -> Role.VIEWER;
            default -> null;
        };
        if (explicit == null || implied == null) {
            return Optional.ofNullable(explicit != null ? explicit : implied);
        }
        return Optional.of(explicit.ordinal() >= implied.ordinal() ? explicit : implied);
    }
}
