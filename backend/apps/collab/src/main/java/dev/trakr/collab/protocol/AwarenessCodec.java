package dev.trakr.collab.protocol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The payload of an awareness frame (06-collab §3): {@code varUint count}, then per entry {@code varUint clientId},
 * {@code varUint clock}, {@code varString json}. The JSON is the client's whole presence state (its user, its
 * caret); {@code "null"} means "this client left". The clock only goes up: a receiver keeps the entry with the
 * higher clock, so old or repeated updates change nothing.
 *
 * <p>The server reads only ids and clocks — to know which clients a socket speaks for, and to announce them gone
 * when it closes. The JSON is passed along as is, never parsed.
 */
public final class AwarenessCodec {

    public static final String LEFT = "null";

    public record Entry(long clientId, long clock, String json) {

        public boolean left() {
            return LEFT.equals(json);
        }

        /** The same client, gone: one clock tick later, so every receiver accepts it as newer. */
        public Entry gone() {
            return new Entry(clientId, clock + 1, LEFT);
        }
    }

    private AwarenessCodec() {
    }

    public static List<Entry> decode(byte[] update) {
        Lib0Decoder decoder = new Lib0Decoder(update);
        int count = decoder.readVarUintAsInt();
        // each entry takes at least 3 bytes (id, clock, empty string), so a count beyond that is a lie
        if (count > decoder.remaining() / 3) {
            throw new ProtocolException("awareness claims " + count + " entries in " + decoder.remaining() + " bytes");
        }
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new Entry(decoder.readVarUint(), decoder.readVarUint(), decoder.readVarString()));
        }
        if (decoder.hasRemaining()) {
            throw new ProtocolException("unexpected bytes after " + count + " awareness entries");
        }
        return entries;
    }

    public static byte[] encode(Collection<Entry> entries) {
        Lib0Encoder encoder = new Lib0Encoder().writeVarUint(entries.size());
        for (Entry entry : entries) {
            encoder.writeVarUint(entry.clientId()).writeVarUint(entry.clock()).writeVarString(entry.json());
        }
        return encoder.toByteArray();
    }
}
