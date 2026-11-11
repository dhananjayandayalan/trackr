package dev.trakr.collab.ws;

import dev.trakr.collab.TestcontainersConfiguration.OwnerJdbc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.collab.CollabIT;
import dev.trakr.collab.Fixtures;
import dev.trakr.collab.Fixtures.World;
import dev.trakr.collab.TestClient;
import dev.trakr.collab.protocol.AwarenessCodec;
import dev.trakr.collab.protocol.AwarenessCodec.Entry;
import dev.trakr.collab.protocol.MessageReader;
import dev.trakr.collab.protocol.MessageWriter;
import dev.trakr.collab.protocol.Messages;
import dev.trakr.collab.protocol.Messages.Awareness;
import dev.trakr.collab.protocol.Messages.SyncStep1;
import dev.trakr.collab.protocol.Messages.SyncStep2;
import dev.trakr.collab.protocol.Messages.TrakrCheckpoint;
import dev.trakr.collab.protocol.Messages.TrakrSeed;
import dev.trakr.collab.protocol.Messages.TrakrSnapshot;
import dev.trakr.collab.protocol.Messages.Update;
import dev.trakr.contracts.events.CollabEvent.CollabUpdate;
import dev.trakr.contracts.events.EventEnvelope;
import dev.trakr.contracts.events.Topics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.socket.CloseStatus;

/**
 * Real sockets against the running app (06-collab §3–§5): what a joining client receives, how edits fan out,
 * what viewers and strangers may do, and the checkpoint handshake down to the rows left in collab_update.
 */
@CollabIT
class CollabHandlerIT {

    private final JdbcClient jdbc;
    private final StringRedisTemplate redis;
    private final KafkaTemplate<String, Object> kafka;
    private final KafkaListenerEndpointRegistry listeners;
    private final int port;
    private World w;

    @Autowired
    CollabHandlerIT(OwnerJdbc owner, StringRedisTemplate redis, KafkaTemplate<String, Object> kafka,
            KafkaListenerEndpointRegistry listeners, @LocalServerPort int port) {
        this.jdbc = owner.client();
        this.redis = redis;
        this.kafka = kafka;
        this.listeners = listeners;
        this.port = port;
    }

    @BeforeEach
    void freshIssue() {
        w = Fixtures.world(jdbc);
    }

    private TestClient connect(java.util.UUID user) throws Exception {
        return TestClient.connect(port, w.issueKey(), Fixtures.session(redis, user));
    }

    @Test
    void aJoiningClientGetsSyncStep1ThenTheDocumentThenTheSeedIfNew() throws Exception {
        try (TestClient ada = connect(w.ada())) {
            // 1. an empty state vector: "send me everything you have"
            assertThat(ada.next()).isInstanceOfSatisfying(SyncStep1.class,
                    step1 -> assertThat(step1.stateVector()).isEqualTo(Messages.EMPTY_STATE_VECTOR));
            // 2. nothing stored yet: the empty update
            assertThat(ada.next()).isInstanceOfSatisfying(SyncStep2.class,
                    step2 -> assertThat(step2.update()).isEqualTo(Messages.EMPTY_UPDATE));
            // the first member in a never-edited document fills it from the old description
            assertThat(ada.next()).isInstanceOf(TrakrSeed.class);

            try (TestClient grace = connect(w.grace())) {
                grace.next(SyncStep2.class);
                // only one seeder per document
                grace.receivesNo(TrakrSeed.class, Duration.ofMillis(500));
            }
        }
    }

    @Test
    void anUpdateReachesTheOthersByteForByteAndIsStored() throws Exception {
        byte[] update = Fixtures.fixture("update.bin");
        try (TestClient ada = connect(w.ada()); TestClient grace = connect(w.grace())) {
            grace.next(SyncStep2.class);

            ada.send(update);

            // a syncUpdate frame re-encoded by the server is the same bytes y-websocket sent
            assertThat(grace.nextFrame()).isEqualTo(update);
            Fixtures.await(Duration.ofSeconds(5), () -> storedUpdates() == 1);
        }
    }

    @Test
    void aNewcomerReceivesTheStoredUpdatesAfterTheSnapshot() throws Exception {
        try (TestClient ada = connect(w.ada())) {
            ada.next(SyncStep2.class);
            ada.send(Fixtures.fixture("update.bin"));
            Fixtures.await(Duration.ofSeconds(5), () -> storedUpdates() == 1);

            try (TestClient grace = connect(w.grace())) {
                grace.next(SyncStep2.class);
                assertThat(grace.next()).isInstanceOfSatisfying(Update.class, stored ->
                        assertThat(stored.update()).isEqualTo(Fixtures.fixture("update-payload.bin")));
                // something is stored now, so nobody seeds
                grace.receivesNo(TrakrSeed.class, Duration.ofMillis(300));
            }
        }
    }

    @Test
    void aViewersUpdatesAreDropped() throws Exception {
        try (TestClient ada = connect(w.ada()); TestClient vic = connect(w.vic())) {
            vic.next(SyncStep2.class);
            // a viewer is never the seeder
            vic.receivesNo(TrakrSeed.class, Duration.ofMillis(300));

            vic.send(Fixtures.fixture("update.bin"));

            ada.receivesNo(Update.class, Duration.ofSeconds(1));
            assertThat(storedUpdates()).isZero();
        }
    }

    @Test
    void awarenessIsRelayedAndALeavingClientIsAnnouncedGone() throws Exception {
        byte[] awareness = Fixtures.fixture("awareness-two-clients.bin");
        try (TestClient grace = connect(w.grace())) {
            grace.next(SyncStep2.class);
            TestClient ada = connect(w.ada());
            ada.send(awareness);
            // relayed unchanged (records compare arrays by reference, so compare the bytes)
            assertThat(grace.next(Awareness.class).update())
                    .isEqualTo(((Awareness) MessageReader.read(awareness)).update());

            // a newcomer gets everyone's current state at once
            try (TestClient vic = connect(w.vic())) {
                Awareness current = vic.next(Awareness.class);
                assertThat(AwarenessCodec.decode(current.update())).extracting(Entry::clientId)
                        .containsExactly(1001L, 2002L);
            }

            ada.close();
            Awareness gone = grace.next(Awareness.class);
            assertThat(AwarenessCodec.decode(gone.update())).containsExactly(
                    new Entry(1001, 2, AwarenessCodec.LEFT), new Entry(2002, 2, AwarenessCodec.LEFT));
        }
    }

    @Test
    void theCheckpointHandshakeStoresASnapshotAndShrinksCollabUpdate() throws Exception {
        try (TestClient ada = connect(w.ada())) {
            ada.next(SyncStep2.class);
            ada.send(Fixtures.fixture("update.bin"));
            ada.send(Fixtures.fixture("update.bin"));
            Fixtures.await(Duration.ofSeconds(5), () -> storedUpdates() == 2);
            long last = jdbc.sql("SELECT max(id) FROM collab_update WHERE issue_id = ?").params(w.issueId())
                    .query(Long.class).single();

            // 2 s after the last edit the server asks the (only) member for a snapshot up to the last update
            TrakrCheckpoint checkpoint = ada.next(TrakrCheckpoint.class);
            assertThat(checkpoint.uptoUpdateId()).isEqualTo(last);

            ada.send(MessageWriter.write(new TrakrSnapshot(last, Fixtures.fixture("doc-state.bin"), "Hello",
                    "<p>Hello</p>")));

            Fixtures.await(Duration.ofSeconds(5), () -> storedUpdates() == 0);
            assertThat(jdbc.sql("SELECT upto_update_id FROM collab_snapshot WHERE issue_id = ?").params(w.issueId())
                    .query(Long.class).single()).isEqualTo(last);

            // and a newcomer now starts from the snapshot
            try (TestClient grace = connect(w.grace())) {
                assertThat(grace.next(SyncStep2.class).update()).isEqualTo(Fixtures.fixture("doc-state.bin"));
            }
        }
    }

    @Test
    void updatesLeftWithoutASnapshotAreCheckpointedByTheNextMemberToJoin() throws Exception {
        // Ada edits and leaves before the 2 s checkpoint: the edit exists only as a collab_update row
        try (TestClient ada = connect(w.ada())) {
            ada.next(SyncStep2.class);
            ada.send(Fixtures.fixture("update.bin"));
            Fixtures.await(Duration.ofSeconds(5), () -> storedUpdates() == 1);
        }
        // a viewer can't provide a snapshot; the next member to join is asked, without editing anything
        try (TestClient vic = connect(w.vic()); TestClient grace = connect(w.grace())) {
            vic.receivesNo(TrakrCheckpoint.class, Duration.ofMillis(300));
            assertThat(grace.next(TrakrCheckpoint.class).uptoUpdateId()).isPositive();
        }
    }

    @Test
    void aSnapshotNobodyAskedForIsIgnored() throws Exception {
        try (TestClient ada = connect(w.ada())) {
            ada.next(SyncStep2.class);
            ada.send(MessageWriter.write(new TrakrSnapshot(999, new byte[] {0, 0}, "", "")));

            ada.receivesNo(TrakrCheckpoint.class, Duration.ofMillis(500));
            assertThat(jdbc.sql("SELECT count(*) FROM collab_snapshot WHERE issue_id = ?").params(w.issueId())
                    .query(Long.class).single()).isZero();
        }
    }

    @Test
    void anUpdateFromAnotherInstanceIsRelayedToLocalSockets() throws Exception {
        Fixtures.await(Duration.ofSeconds(30), () -> listeners.getListenerContainer("collab-relay") != null
                && !listeners.getListenerContainer("collab-relay").getAssignedPartitions().isEmpty());
        byte[] payload = Fixtures.fixture("update-payload.bin");
        try (TestClient ada = connect(w.ada())) {
            ada.next(SyncStep2.class);

            CollabUpdate remote = CollabUpdate.of(Instant.now(), w.grace(), w.workspace(), w.project(), w.issueId(),
                    payload, 1, 2002, "another-instance");
            // as the other instance's CollabRelay sends it: the envelope, plus the ce_type header the relay routes on
            ProducerRecord<String, Object> record = new ProducerRecord<>(Topics.COLLAB, w.issueId().toString(),
                    EventEnvelope.wrap(remote, "trakr/collab"));
            record.headers().add("ce_type", remote.type().getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get();

            assertThat(ada.next(Update.class).update()).isEqualTo(payload);
        }
    }

    @Test
    void handshakesWithoutAValidSessionFromAnotherSiteOrOutsideTheProjectAreRefused() throws Exception {
        assertRefused(() -> TestClient.connect(port, w.issueKey(), null), "401");
        assertRefused(() -> TestClient.connect(port, w.issueKey(), "bm90LWEtc2Vzc2lvbg=="), "401");
        String expired = Fixtures.session(redis, w.ada());
        Fixtures.expire(redis, expired);
        assertRefused(() -> TestClient.connect(port, w.issueKey(), expired), "401");
        assertRefused(() -> TestClient.connect(port, w.issueKey(), Fixtures.session(redis, w.ada()),
                "https://evil.example"), "403");
        // Eve is in the workspace but the project is private: the same answer as a key that doesn't exist
        assertRefused(() -> TestClient.connect(port, w.issueKey(), Fixtures.session(redis, w.eve())), "404");
        assertRefused(() -> TestClient.connect(port, w.key() + "-999", Fixtures.session(redis, w.ada())), "404");
    }

    @Test
    void aMalformedFrameClosesTheSocketWith1003() throws Exception {
        try (TestClient ada = connect(w.ada())) {
            ada.next(SyncStep2.class);
            ada.send(new byte[] {7, 7, 7});

            assertThat(ada.awaitClose().getCode()).isEqualTo(CloseStatus.NOT_ACCEPTABLE.getCode());
        }
    }

    private long storedUpdates() {
        return jdbc.sql("SELECT count(*) FROM collab_update WHERE issue_id = ?").params(w.issueId())
                .query(Long.class).single();
    }

    private interface Connect {
        TestClient open() throws Exception;
    }

    private static void assertRefused(Connect connect, String status) {
        assertThatThrownBy(connect::open)
                .isInstanceOf(ExecutionException.class)
                .hasStackTraceContaining(status);
    }
}
