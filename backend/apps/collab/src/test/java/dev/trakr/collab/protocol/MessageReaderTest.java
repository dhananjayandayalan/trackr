package dev.trakr.collab.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.collab.protocol.Messages.Awareness;
import dev.trakr.collab.protocol.Messages.Message;
import dev.trakr.collab.protocol.Messages.QueryAwareness;
import dev.trakr.collab.protocol.Messages.SyncStep1;
import dev.trakr.collab.protocol.Messages.SyncStep2;
import dev.trakr.collab.protocol.Messages.TrakrCheckpoint;
import dev.trakr.collab.protocol.Messages.TrakrSeed;
import dev.trakr.collab.protocol.Messages.TrakrSnapshot;
import dev.trakr.collab.protocol.Messages.Update;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Whole y-websocket frames (from the fixtures) → our records, and our records → the very same bytes. */
class MessageReaderTest {

    @Test
    void syncStep1CarriesTheStateVector() {
        Message message = MessageReader.read(Fixtures.bytes("sync-step1.bin"));

        assertThat(message).isInstanceOfSatisfying(SyncStep1.class, step1 ->
                assertThat(step1.stateVector()).isEqualTo(Fixtures.bytes("doc-state-vector.bin")));
    }

    @Test
    void syncStep2CarriesTheWholeDocumentAsOneUpdate() {
        // byte by byte (see the phase's "Done when"): 0x00 sync · 0x01 step 2 · varUint length · the update, which
        // starts 0x01 (one client) 0x03 (three structs) 0xE9 0x07 (client 1001) …
        Message message = MessageReader.read(Fixtures.bytes("sync-step2.bin"));

        assertThat(message).isInstanceOfSatisfying(SyncStep2.class, step2 ->
                assertThat(step2.update()).isEqualTo(Fixtures.bytes("doc-state.bin")));
    }

    @Test
    void anUpdateCarriesTheEditAsIs() {
        Message message = MessageReader.read(Fixtures.bytes("update.bin"));

        assertThat(message).isInstanceOfSatisfying(Update.class, update ->
                assertThat(update.update()).isEqualTo(Fixtures.bytes("update-payload.bin")));
    }

    @Test
    void awarenessIsPassedThroughUndecoded() {
        assertThat(MessageReader.read(Fixtures.bytes("awareness-two-clients.bin"))).isInstanceOf(Awareness.class);
    }

    @Test
    void ourCheckpointAndSnapshotAsTheBrowserEncodesThem() {
        JsonNode expected = Fixtures.manifest().path("snapshot");

        assertThat(MessageReader.read(Fixtures.bytes("checkpoint.bin"))).isEqualTo(new TrakrCheckpoint(42));
        assertThat(MessageReader.read(Fixtures.bytes("snapshot.bin"))).isInstanceOfSatisfying(TrakrSnapshot.class,
                snapshot -> {
                    assertThat(snapshot.uptoUpdateId()).isEqualTo(expected.path("uptoUpdateId").asLong());
                    assertThat(snapshot.state()).isEqualTo(Fixtures.bytes("snapshot-state.bin"));
                    assertThat(snapshot.text()).isEqualTo(expected.path("text").asString());
                    assertThat(snapshot.html()).isEqualTo(expected.path("html").asString());
                });
    }

    @Test
    void writingGivesBackTheLibrarysBytes() {
        for (String fixture : new String[] {"sync-step1.bin", "sync-step2.bin", "update.bin",
                "awareness-two-clients.bin", "awareness-left.bin", "checkpoint.bin", "snapshot.bin"}) {
            byte[] bytes = Fixtures.bytes(fixture);
            assertThat(MessageWriter.write(MessageReader.read(bytes))).as(fixture).isEqualTo(bytes);
        }
    }

    @Test
    void messagesWithoutPayloadRoundTrip() {
        assertThat(MessageReader.read(MessageWriter.write(new QueryAwareness()))).isEqualTo(new QueryAwareness());
        assertThat(MessageReader.read(MessageWriter.write(new TrakrSeed()))).isEqualTo(new TrakrSeed());
        assertThat(MessageWriter.write(new SyncStep1(Messages.EMPTY_STATE_VECTOR))).containsExactly(0, 0, 1, 0);
    }

    @Test
    void unknownTypesTruncatedFramesAndTrailingBytesAreProtocolErrors() {
        assertThatThrownBy(() -> MessageReader.read(new byte[] {7})).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unknown message type 7");
        assertThatThrownBy(() -> MessageReader.read(new byte[] {0, 9, 0})).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unknown sync step 9");
        assertThatThrownBy(() -> MessageReader.read(new byte[] {0, 2, 5, 1})).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> MessageReader.read(new byte[] {3, 3})).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unexpected bytes");
        assertThatThrownBy(() -> MessageReader.read(new byte[0])).isInstanceOf(ProtocolException.class);
    }
}
