package dev.trakr.collab.security;

import dev.trakr.collab.security.IssueAccess.Grant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Who is on a socket and what they may do there, decided once at the handshake and kept in the WebSocket session's
 * attributes (the handshake's {@code attributes} map becomes {@code WebSocketSession.getAttributes()}).
 */
public record Connection(UUID userId, Grant grant) {

    static final String ATTRIBUTE = Connection.class.getName();

    public UUID issueId() {
        return grant.issueId();
    }

    public boolean canEdit() {
        return grant.role().canEdit();
    }

    public static @Nullable Connection from(Map<String, Object> attributes) {
        return attributes.get(ATTRIBUTE) instanceof Connection connection ? connection : null;
    }
}
