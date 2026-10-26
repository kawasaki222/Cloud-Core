package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class FrameCodecTest {

    @Test
    void roundTripsUncompressedFrame() {
        byte[] body = new byte[64];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        Frame frame = new Frame(1, 0, Opcode.UI_OPEN, body);

        byte[] encoded = FrameCodec.encode(frame, Limits.S2C);
        Frame decoded = FrameCodec.decode(encoded, Limits.S2C);

        assertEquals(1, decoded.version());
        assertEquals(0, decoded.flags());
        assertEquals(Opcode.UI_OPEN, decoded.opcode());
        assertArrayEquals(body, decoded.body());
    }

    @Test
    void roundTripsCompressedFrame() {
        byte[] body = new byte[1_024];
        Arrays.fill(body, (byte) 0x5A);
        Frame frame = new Frame(1, FrameFlags.COMPRESSED, Opcode.UI_DEFINITION, body);

        byte[] encoded = FrameCodec.encode(frame, Limits.S2C);
        Frame decoded = FrameCodec.decode(encoded, Limits.S2C);

        assertEquals(FrameFlags.COMPRESSED, decoded.flags());
        assertArrayEquals(body, decoded.body());
    }

    @Test
    void rejectsInvalidMagic() {
        byte[] encoded = FrameCodec.encode(new Frame(1, 0, Opcode.PING, new byte[0]), Limits.S2C);
        encoded[0] = 0;

        assertThrows(ProtocolException.class, () -> FrameCodec.decode(encoded, Limits.S2C));
    }

    @Test
    void rejectsTruncatedFrame() {
        byte[] encoded = FrameCodec.encode(new Frame(1, 0, Opcode.PING, new byte[16]), Limits.S2C);
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);

        assertThrows(ProtocolException.class, () -> FrameCodec.decode(truncated, Limits.S2C));
    }

    @Test
    void rejectsOversizedBody() {
        Limits tight = new Limits(64, 64, 32, 64, 4, 16);
        Frame frame = new Frame(1, 0, Opcode.PING, new byte[128]);

        assertThrows(ProtocolException.class, () -> FrameCodec.encode(frame, tight));
    }

    @Test
    void rejectsUnknownFlags() {
        Frame frame = new Frame(1, 0x80, Opcode.PING, new byte[0]);

        assertThrows(ProtocolException.class, () -> FrameCodec.encode(frame, Limits.S2C));
    }

    @Test
    void rejectsDecompressedLengthMismatch() {
        byte[] body = new byte[512];
        Frame frame = new Frame(1, FrameFlags.COMPRESSED, Opcode.PING, body);
        byte[] encoded = FrameCodec.encode(frame, Limits.S2C);

        Limits smaller = new Limits(1_048_576, 128, 24_000, 32_767, 16, 4_096);
        assertThrows(ProtocolException.class, () -> FrameCodec.decode(encoded, smaller));
    }
}
