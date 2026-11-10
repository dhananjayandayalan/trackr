package dev.trakr.collab.store;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * collab_update and collab_snapshot (02-data-model §5) through JdbcClient: four statements, no entities. The bytes
 * are opaque to us, so there is nothing an ORM could map.
 */
@Component
public class CollabStore {

    /** One stored Yjs update and its place in the issue's order. */
    public record StoredUpdate(long id, byte[] update) {
    }

    /** The whole document as one update, and the last update id it includes. */
    public record Snapshot(byte[] state, long uptoUpdateId) {
    }

    private final JdbcClient jdbc;

    CollabStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // BLOCK 1: append — the identity column numbers the update; RETURNING hands the number back in one round trip
    public long appendUpdate(UUID issueId, byte[] update, @Nullable Long clientId) {
        return jdbc.sql("""
                INSERT INTO collab_update (issue_id, update, client_id)
                VALUES (:issueId, :update, :clientId)
                RETURNING id
                """)
                .param("issueId", issueId)
                .param("update", update)
                .param("clientId", clientId, Types.BIGINT)
                .query(Long.class)
                .single();
    }

    // BLOCK 2: what a newcomer needs — the snapshot, then every update after it, in order
    public Optional<Snapshot> loadSnapshot(UUID issueId) {
        return jdbc.sql("SELECT state, upto_update_id FROM collab_snapshot WHERE issue_id = :issueId")
                .param("issueId", issueId)
                .query((rs, row) -> new Snapshot(rs.getBytes("state"), rs.getLong("upto_update_id")))
                .optional();
    }

    public List<StoredUpdate> loadUpdatesAfter(UUID issueId, long afterId) {
        return jdbc.sql("""
                SELECT id, update FROM collab_update
                WHERE issue_id = :issueId AND id > :afterId
                ORDER BY id
                """)
                .param("issueId", issueId)
                .param("afterId", afterId)
                .query((rs, row) -> new StoredUpdate(rs.getLong("id"), rs.getBytes("update")))
                .list();
    }

    /**
     * BLOCK 3: compaction (06-collab §4 step 4), one transaction: the new snapshot replaces the old one, then the
     * updates it covers go. Either both happen or neither — a crash in between can't lose an update or keep one the
     * snapshot already contains (that would be harmless — Yjs ignores what it has — but wasteful).
     *
     * <p>The {@code WHERE} on the upsert ignores a late or duplicated snapshot: one that isn't newer than the stored
     * one changes nothing. Returns false then, and nothing is deleted.
     */
    @Transactional
    public boolean saveSnapshotAndCompact(UUID issueId, byte[] state, long uptoUpdateId) {
        int saved = jdbc.sql("""
                INSERT INTO collab_snapshot (issue_id, state, upto_update_id, updated_at)
                VALUES (:issueId, :state, :upto, now())
                ON CONFLICT (issue_id) DO UPDATE
                  SET state = EXCLUDED.state, upto_update_id = EXCLUDED.upto_update_id, updated_at = EXCLUDED.updated_at
                  WHERE collab_snapshot.upto_update_id < EXCLUDED.upto_update_id
                """)
                .param("issueId", issueId)
                .param("state", state)
                .param("upto", uptoUpdateId)
                .update();
        if (saved == 0) {
            return false;
        }
        jdbc.sql("DELETE FROM collab_update WHERE issue_id = :issueId AND id <= :upto")
                .param("issueId", issueId)
                .param("upto", uptoUpdateId)
                .update();
        return true;
    }
}
