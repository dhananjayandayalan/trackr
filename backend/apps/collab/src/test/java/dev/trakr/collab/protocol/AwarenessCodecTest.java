package dev.trakr.collab.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.trakr.collab.protocol.AwarenessCodec.Entry;
import dev.trakr.collab.protocol.Messages.Awareness;
import java.util.List;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Awareness entries as y-protocols wrote them: ids, clocks and the untouched JSON. */
class AwarenessCodecTest {

    @Test
    void decodesTwoClients() {
        assertThat(entriesOf("awareness-two-clients.bin")).isEqualTo(expected("awarenessTwoClients"));
    }

    @Test
    void decodesALeftClientAsNull() {
        List<Entry> entries = entriesOf("awareness-left.bin");

        assertThat(entries).isEqualTo(expected("awarenessLeft"));
        assertThat(entries.getFirst().left()).isTrue();
    }

    @Test
    void encodesWhatItDecoded() {
        Awareness frame = (Awareness) MessageReader.read(Fixtures.bytes("awareness-two-clients.bin"));

        assertThat(AwarenessCodec.encode(AwarenessCodec.decode(frame.update()))).isEqualTo(frame.update());
    }

    @Test
    void goneIsOneTickLaterAndNull() {
        Entry ada = new Entry(1001, 4, "{\"user\":{}}");

        assertThat(ada.gone()).isEqualTo(new Entry(1001, 5, "null"));
        // and y-protocols reads what we write: count, id, clock, "null"
        assertThat(AwarenessCodec.decode(AwarenessCodec.encode(List.of(ada.gone())))).containsExactly(ada.gone());
    }

    @Test
    void aCountTheBytesCantHoldIsRejected() {
        byte[] claimsAThousand = new Lib0Encoder().writeVarUint(1000).writeVarUint(1).toByteArray();

        assertThatThrownBy(() -> AwarenessCodec.decode(claimsAThousand)).isInstanceOf(ProtocolException.class);
    }

    private static List<Entry> entriesOf(String fixture) {
        Awareness frame = (Awareness) MessageReader.read(Fixtures.bytes(fixture));
        return AwarenessCodec.decode(frame.update());
    }

    private static List<Entry> expected(String name) {
        JsonNode entries = Fixtures.manifest().path(name);
        return StreamSupport.stream(entries.spliterator(), false)
                .map(e -> new Entry(e.path("clientId").asLong(), e.path("clock").asLong(), e.path("json").asString()))
                .toList();
    }
}
