package dev.trakr.collab.config;

import dev.trakr.kernel.Ids;
import org.springframework.stereotype.Component;

/**
 * Who this running collab process is, among all of them (06-collab §6). Used three ways: as
 * {@code originInstance} on everything it relays (so it can skip its own messages when Kafka hands them back), in
 * its Kafka group id (every instance must see every message: a group of one), and in the Redis presence set, where
 * the lowest id with a member in a room takes the room's checkpoints.
 *
 * <p>A UUIDv7 made at startup: ids sort by creation time, so "lowest id" means "the oldest instance still alive".
 */
@Component("collabInstance")
public class CollabInstance {

    private final String id = Ids.newId().toString();

    public String id() {
        return id;
    }
}
