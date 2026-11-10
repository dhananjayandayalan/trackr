package dev.trakr.collab.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/** Our decoder and encoder against bytes lib0 (JavaScript) produced: same numbers and strings, same bytes. */
class Lib0CodecTest {

    @ParameterizedTest
    @ValueSource(longs = {0, 127, 128, 300, 4_294_967_296L})
    void decodesTheVarUintsLib0Wrote(long value) {
        Lib0Decoder decoder = new Lib0Decoder(Fixtures.bytes("varuint-" + value + ".bin"));

        assertThat(decoder.readVarUint()).isEqualTo(value);
        assertThat(decoder.hasRemaining()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 127, 128, 300, 4_294_967_296L})
    void encodesVarUintsToTheSameBytesAsLib0(long value) {
        assertThat(new Lib0Encoder().writeVarUint(value).toByteArray())
                .isEqualTo(Fixtures.bytes("varuint-" + value + ".bin"));
    }

    @Test
    void threeHundredIsAcThenTwo() {
        // 06-collab §3: low 7 bits first with the "more" bit set (0x2C | 0x80 = 0xAC), then 300 >> 7 = 2
        assertThat(new Lib0Encoder().writeVarUint(300).toByteArray()).containsExactly(0xAC, 0x02);
    }

    @Test
    void varStringsAreUtf8BothWays() {
        String expected = Fixtures.manifest().path("varString").asString();
        byte[] fixture = Fixtures.bytes("varstring.bin");

        assertThat(new Lib0Decoder(fixture).readVarString()).isEqualTo(expected);
        assertThat(new Lib0Encoder().writeVarString(expected).toByteArray()).isEqualTo(fixture);
    }

    @Test
    void aLengthLongerThanTheFrameIsRejectedBeforeAllocating() {
        // claims 2^31 - 1 bytes, has 1
        byte[] frame = new Lib0Encoder().writeVarUint(Integer.MAX_VALUE).toByteArray();
        byte[] withOneByte = java.util.Arrays.copyOf(frame, frame.length + 1);

        assertThatThrownBy(() -> new Lib0Decoder(withOneByte).readVarUint8Array())
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("only 1 left");
    }

    @Test
    void aVarUintThatNeverEndsIsRejected() {
        byte[] endless = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, (byte) 0x80, 0x01};

        assertThatThrownBy(() -> new Lib0Decoder(endless).readVarUint()).isInstanceOf(ProtocolException.class);
    }

    @Test
    void aVarUintCutOffMidwayIsRejected() {
        assertThatThrownBy(() -> new Lib0Decoder(new byte[] {(byte) 0xAC}).readVarUint())
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("past the end");
    }

    @Test
    void numbersBeyondJavaScriptsSafeRangeAreRejected() {
        // 2^53 needs the 8th byte's 5th bit: 0x80 ×7, then 0x10
        byte[] twoToThe53 = {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                (byte) 0x80, 0x10};

        assertThatThrownBy(() -> new Lib0Decoder(twoToThe53).readVarUint())
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("2^53");
        assertThat(new Lib0Decoder(new Lib0Encoder().writeVarUint(Lib0Decoder.MAX_SAFE_INTEGER).toByteArray())
                .readVarUint()).isEqualTo(Lib0Decoder.MAX_SAFE_INTEGER);
    }
}
