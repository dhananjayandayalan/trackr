package dev.trakr.collab.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Reads lib0's binary encoding (06-collab §3), the format every Yjs message uses — the Java twin of lib0's
 * {@code decoding.js}. One decoder reads one frame, front to back; it is not thread-safe and needn't be.
 *
 * <p>Every read checks the bytes are really there. The input comes from a browser we don't control, so "the
 * length says 2 GB" or "the number never ends" must be an error, not an allocation or a loop.
 */
public final class Lib0Decoder {

    /** JavaScript numbers are doubles: integers above 2^53 − 1 can't be represented exactly. */
    public static final long MAX_SAFE_INTEGER = (1L << 53) - 1;

    // BLOCK 1: state — a ByteBuffer is an array plus a read position, which is all a decoder needs
    private final ByteBuffer buffer;

    public Lib0Decoder(byte[] bytes) {
        this.buffer = ByteBuffer.wrap(bytes);
    }

    public boolean hasRemaining() {
        return buffer.hasRemaining();
    }

    public int remaining() {
        return buffer.remaining();
    }

    // BLOCK 2: readVarUint — 7 bits per byte, least significant group first; a set high bit (0x80) means "more
    // bytes follow". 300 = 0b1_0010_1100 → 0xAC (low 7 bits 0101100 + the "more" bit), 0x02 (the remaining 10).
    public long readVarUint() {
        long value = 0;
        int shift = 0;
        while (true) {
            if (!buffer.hasRemaining()) {
                throw new ProtocolException("varUint runs past the end of the frame");
            }
            int b = buffer.get() & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (value > MAX_SAFE_INTEGER) {
                    throw new ProtocolException("varUint is larger than 2^53 - 1");
                }
                return value;
            }
            shift += 7;
            // 8 groups of 7 bits = 56 bits already exceed 53: a 9th byte can only be an attack or garbage
            if (shift > 49) {
                throw new ProtocolException("varUint is longer than 8 bytes");
            }
        }
    }

    /** A varUint that must fit an int, e.g. a length or a count. */
    public int readVarUintAsInt() {
        long value = readVarUint();
        if (value > Integer.MAX_VALUE) {
            throw new ProtocolException("number too large: " + value);
        }
        return (int) value;
    }

    // BLOCK 3: readVarUint8Array — a length, then that many bytes. The length is checked against what is left
    // BEFORE allocating: a 10-byte frame claiming a 2 GB array must not make us allocate 2 GB.
    public byte[] readVarUint8Array() {
        int length = readVarUintAsInt();
        if (length > buffer.remaining()) {
            throw new ProtocolException("byte array of " + length + " bytes, but only " + buffer.remaining() + " left");
        }
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return bytes;
    }

    // BLOCK 4: readVarString — a varUint8Array holding UTF-8
    public String readVarString() {
        return new String(readVarUint8Array(), StandardCharsets.UTF_8);
    }
}
