package dev.trakr.collab.security;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * At most 50 open sockets per user on this instance (06-collab §5): enough for every tab anyone really opens, few
 * enough that one script can't hold thousands of connections. Per instance — a user spread over several instances
 * gets 50 on each, which is fine for a limit whose job is to stop runaway clients.
 */
@Component
public class ConnectionLimits {

    static final int PER_USER = 50;

    private final ConcurrentMap<UUID, Integer> open = new ConcurrentHashMap<>();

    /** Counts the socket in; false (and nothing counted) when the user is at the limit. */
    public boolean tryOpen(UUID userId) {
        boolean[] allowed = {false};
        // compute runs atomically per key, so two sockets of one user can't both take the 50th slot
        open.compute(userId, (id, count) -> {
            int current = count == null ? 0 : count;
            if (current >= PER_USER) {
                return current;
            }
            allowed[0] = true;
            return current + 1;
        });
        return allowed[0];
    }

    public void closed(UUID userId) {
        // returning null removes the key, so the map doesn't keep every user who ever connected
        open.computeIfPresent(userId, (id, count) -> count <= 1 ? null : count - 1);
    }
}
