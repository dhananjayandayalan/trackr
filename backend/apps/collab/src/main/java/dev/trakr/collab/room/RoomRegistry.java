package dev.trakr.collab.room;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * The rooms open on this instance, by issue id. A room exists while at least one socket is in it: the first
 * join creates it, the last leave drops it, so an issue nobody is editing costs no memory.
 *
 * <p>{@code compute}/{@code computeIfPresent} run atomically per key: a join and the last leave of the same issue
 * can't interleave, so a socket is never added to a room that is being thrown away.
 */
@Component
public class RoomRegistry {

    private final ConcurrentMap<UUID, Room> rooms = new ConcurrentHashMap<>();

    public Room join(UUID issueId, Peer peer) {
        return rooms.compute(issueId, (id, room) -> {
            Room joined = room == null ? new Room(id) : room;
            joined.add(peer);
            return joined;
        });
    }

    public void leave(Room room, Peer peer) {
        rooms.computeIfPresent(room.issueId(), (id, current) -> {
            current.remove(peer);
            return current.isEmpty() ? null : current;
        });
    }

    public Optional<Room> find(UUID issueId) {
        return Optional.ofNullable(rooms.get(issueId));
    }

    public Collection<Room> all() {
        return List.copyOf(rooms.values());
    }
}
