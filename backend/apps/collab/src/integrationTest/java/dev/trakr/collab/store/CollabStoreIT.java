package dev.trakr.collab.store;

import dev.trakr.collab.TestcontainersConfiguration.OwnerJdbc;
import static org.assertj.core.api.Assertions.assertThat;

import dev.trakr.collab.CollabIT;
import dev.trakr.collab.Fixtures;
import dev.trakr.collab.store.CollabStore.StoredUpdate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@CollabIT
class CollabStoreIT {

    private final CollabStore store;
    private final JdbcClient jdbc;

    @Autowired
    CollabStoreIT(CollabStore store, OwnerJdbc owner) {
        this.store = store;
        this.jdbc = owner.client();
    }

    @Test
    void appendedUpdatesComeBackInOrderAfterAGivenId() {
        UUID issue = Fixtures.world(jdbc).issueId();
        long first = store.appendUpdate(issue, new byte[] {1}, 1001L);
        long second = store.appendUpdate(issue, new byte[] {2}, null);
        long third = store.appendUpdate(issue, new byte[] {3}, 1001L);

        assertThat(first).isLessThan(second).isLessThan(third);
        assertThat(store.loadUpdatesAfter(issue, 0)).extracting(StoredUpdate::id).containsExactly(first, second, third);
        assertThat(store.loadUpdatesAfter(issue, first)).extracting(StoredUpdate::update)
                .containsExactly(new byte[] {2}, new byte[] {3});
        assertThat(store.loadSnapshot(issue)).isEmpty();
    }

    @Test
    void aSnapshotReplacesTheUpdatesItCoversInOneTransaction() {
        UUID issue = Fixtures.world(jdbc).issueId();
        store.appendUpdate(issue, new byte[] {1}, null);
        long covered = store.appendUpdate(issue, new byte[] {2}, null);
        long later = store.appendUpdate(issue, new byte[] {3}, null);

        assertThat(store.saveSnapshotAndCompact(issue, new byte[] {9, 9}, covered)).isTrue();

        assertThat(store.loadSnapshot(issue)).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.state()).containsExactly(9, 9);
            assertThat(snapshot.uptoUpdateId()).isEqualTo(covered);
        });
        // only the update after the snapshot is left: collab_update shrinks with every checkpoint
        assertThat(store.loadUpdatesAfter(issue, 0)).extracting(StoredUpdate::id).containsExactly(later);
    }

    @Test
    void aLateOrRepeatedSnapshotChangesNothing() {
        UUID issue = Fixtures.world(jdbc).issueId();
        long one = store.appendUpdate(issue, new byte[] {1}, null);
        long two = store.appendUpdate(issue, new byte[] {2}, null);
        store.saveSnapshotAndCompact(issue, new byte[] {2}, two);
        long three = store.appendUpdate(issue, new byte[] {3}, null);

        assertThat(store.saveSnapshotAndCompact(issue, new byte[] {1}, one)).isFalse();
        assertThat(store.saveSnapshotAndCompact(issue, new byte[] {2}, two)).isFalse();

        assertThat(store.loadSnapshot(issue).orElseThrow().state()).containsExactly(2);
        assertThat(store.loadUpdatesAfter(issue, 0)).extracting(StoredUpdate::id).containsExactly(three);
    }

    @Test
    void roomsDontSeeEachOthersUpdates() {
        UUID mine = Fixtures.world(jdbc).issueId();
        UUID theirs = Fixtures.world(jdbc).issueId();
        store.appendUpdate(theirs, new byte[] {7}, null);

        assertThat(store.loadUpdatesAfter(mine, 0)).isEmpty();
        assertThat(List.of(store.saveSnapshotAndCompact(mine, new byte[] {0, 0}, Long.MAX_VALUE / 2))).containsExactly(true);
        assertThat(store.loadUpdatesAfter(theirs, 0)).hasSize(1);
    }
}
