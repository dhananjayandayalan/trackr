package dev.trakr.collab.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes lib0's binary encoding — the mirror of {@link Lib0Decoder} and of lib0's {@code encoding.js}. Encoding the
 * numbers and strings the JavaScript library encoded must give the very same bytes (Lib0CodecTest checks it).
 */
public final class Lib0Encoder {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    // 7 bits at a time, low group first; every byte but the last gets the "more follows" bit
    public Lib0Encoder writeVarUint(long value) {
        if (value < 0 || value > Lib0Decoder.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException("varUint out of range: " + value);
        }
        long rest = value;
        while (rest > 0x7F) {
            out.write((int) (0x80 | (rest & 0x7F)));
            rest >>>= 7;
        }
        out.write((int) rest);
        return this;
    }

    public Lib0Encoder writeVarUint8Array(byte[] bytes) {
        writeVarUint(bytes.length);
        out.writeBytes(bytes);
        return this;
    }

    public Lib0Encoder writeVarString(String value) {
        return writeVarUint8Array(value.getBytes(StandardCharsets.UTF_8));
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }
}
