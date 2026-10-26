package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class HandshakeCodecTest {

    @Test
    void helloMatchesProtocolDocumentGoldenVector() {
        Hello hello = new Hello(
                ProtocolVersion.MAJOR,
                ProtocolVersion.MINOR,
                "0.1.0",
                FeatureFlags.INLINE_TEXTURE | FeatureFlags.HUD
                        | FeatureFlags.PLAYER_INVENTORY | FeatureFlags.TOOLTIP,
                "zh_cn",
                2,
                640,
                360);

        byte[] body = HandshakeCodec.encodeHello(hello, Limits.C2S);
        assertEquals(24, body.length);
        assertEquals(
                "0001000105302e312e30000f057a685f636e000202800168",
                HexFormat.of().formatHex(body));

        Frame frame = new Frame(ProtocolVersion.MAJOR, 0, Opcode.HELLO, body);
        byte[] encoded = FrameCodec.encode(frame, Limits.C2S);
        assertEquals(31, encoded.length);
        assertEquals(
                "434301008001180001000105302e312e30000f057a685f636e000202800168",
                HexFormat.of().formatHex(encoded));

        Hello decoded = HandshakeCodec.decodeHello(
                FrameCodec.decode(encoded, Limits.C2S).body(), Limits.C2S);
        assertEquals(hello, decoded);
    }

    @Test
    void helloAckRoundTrips() {
        HelloAck ack = new HelloAck(
                ProtocolVersion.MAJOR,
                ProtocolVersion.MINOR,
                FeatureFlags.INLINE_TEXTURE | FeatureFlags.HUD,
                "lobby-1",
                1_048_576,
                HandshakeCodec.COMPRESSION_THRESHOLD,
                "Cloud Core 0.1.0");
        byte[] body = HandshakeCodec.encodeHelloAck(ack, Limits.S2C);
        HelloAck decoded = HandshakeCodec.decodeHelloAck(body, Limits.S2C);
        assertEquals(ack, decoded);
    }

    @Test
    void pingAndPongRoundTripUnsignedNonce() {
        Ping ping = new Ping(0xFEDCBA98, 1_700_000_000_123L);
        Ping decodedPing = HandshakeCodec.decodePing(HandshakeCodec.encodePing(ping));
        assertEquals(ping, decodedPing);

        Pong pong = new Pong(0xFEDCBA98, 1_700_000_000_456L);
        Pong decodedPong = HandshakeCodec.decodePong(HandshakeCodec.encodePong(pong));
        assertEquals(pong, decodedPong);
    }

    @Test
    void rejectsTrailingBytes() {
        byte[] hello = HandshakeCodec.encodeHello(new Hello(
                ProtocolVersion.MAJOR, ProtocolVersion.MINOR, "0.1.0", 0,
                "en_us", 2, 320, 180), Limits.C2S);
        byte[] withTrailing = new byte[hello.length + 1];
        System.arraycopy(hello, 0, withTrailing, 0, hello.length);
        withTrailing[hello.length] = 1;

        assertThrows(ProtocolException.class,
                () -> HandshakeCodec.decodeHello(withTrailing, Limits.C2S));
    }

    @Test
    void rejectsNegativeCompressionThreshold() {
        ArrayByteSink sink = new ArrayByteSink();
        sink.writeShort(1);
        sink.writeShort(1);
        sink.writeShort(0);
        sink.writeVarInt(1);
        sink.writeByte('s');
        sink.writeInt(1_048_576);
        sink.writeVarInt(-1);
        sink.writeVarInt(0);

        assertThrows(ProtocolException.class,
                () -> HandshakeCodec.decodeHelloAck(sink.toByteArray(), Limits.S2C));
    }

    @Test
    void helloAckGoldenVectorIsStable() {
        HelloAck ack = new HelloAck(1, 1, 0, "s", 1_048_576, 256, "ok");
        assertEquals(
                "0001000100000173001000008002026f6b",
                HexFormat.of().formatHex(HandshakeCodec.encodeHelloAck(ack, Limits.S2C)));
    }
}
