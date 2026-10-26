package com.cloudcore.protocol;

import java.util.Objects;

/**
 * Codecs for the four handshake/heartbeat bodies.
 *
 * <p>These bodies are carried by normal frames: {@code HELLO}, {@code HELLO_ACK},
 * {@code PING} and {@code PONG}.
 */
public final class HandshakeCodec {
    public static final int COMPRESSION_THRESHOLD = 256;

    private HandshakeCodec() {
    }

    public static byte[] encodeHello(Hello hello, Limits limits) {
        Objects.requireNonNull(hello, "hello");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        sink.writeShort(hello.protoMajor());
        sink.writeShort(hello.protoMinor());
        ProtocolText.write(sink, hello.modVersion(), limits);
        sink.writeShort(hello.features());
        ProtocolText.write(sink, hello.locale(), limits);
        sink.writeShort(hello.guiScale());
        sink.writeShort(hello.scaledWidth());
        sink.writeShort(hello.scaledHeight());
        return sink.toByteArray();
    }

    public static Hello decodeHello(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        Hello hello = new Hello(
                source.readUnsignedShort(),
                source.readUnsignedShort(),
                ProtocolText.read(source, limits),
                source.readUnsignedShort(),
                ProtocolText.read(source, limits),
                source.readUnsignedShort(),
                source.readUnsignedShort(),
                source.readUnsignedShort());
        requireFullyConsumed(source, "HELLO");
        return hello;
    }

    public static byte[] encodeHelloAck(HelloAck ack, Limits limits) {
        Objects.requireNonNull(ack, "ack");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSink sink = new ArrayByteSink();
        sink.writeShort(ack.protoMajor());
        sink.writeShort(ack.protoMinor());
        sink.writeShort(ack.acceptedFeatures());
        ProtocolText.write(sink, ack.serverId(), limits);
        sink.writeInt(ack.maxFrameBytes());
        sink.writeVarInt(ack.compressionThreshold());
        ProtocolText.write(sink, ack.serverMessage(), limits);
        return sink.toByteArray();
    }

    public static HelloAck decodeHelloAck(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");
        ArrayByteSource source = new ArrayByteSource(data);
        int protoMajor = source.readUnsignedShort();
        int protoMinor = source.readUnsignedShort();
        int acceptedFeatures = source.readUnsignedShort();
        String serverId = ProtocolText.read(source, limits);
        int maxFrameBytes = source.readInt();
        if (maxFrameBytes <= 0) {
            throw new ProtocolException("Invalid maxFrameBytes: " + maxFrameBytes);
        }
        int compressionThreshold = source.readVarInt();
        if (compressionThreshold < 0) {
            throw new ProtocolException("Invalid compressionThreshold: " + compressionThreshold);
        }
        String serverMessage = ProtocolText.read(source, limits);
        requireFullyConsumed(source, "HELLO_ACK");
        return new HelloAck(protoMajor, protoMinor, acceptedFeatures, serverId,
                maxFrameBytes, compressionThreshold, serverMessage);
    }

    public static byte[] encodePing(Ping ping) {
        Objects.requireNonNull(ping, "ping");
        ArrayByteSink sink = new ArrayByteSink();
        sink.writeInt(ping.nonce());
        sink.writeLong(ping.serverTimeMs());
        return sink.toByteArray();
    }

    public static Ping decodePing(byte[] data) {
        Objects.requireNonNull(data, "data");
        ArrayByteSource source = new ArrayByteSource(data);
        Ping ping = new Ping(source.readInt(), source.readLong());
        requireFullyConsumed(source, "PING");
        return ping;
    }

    public static byte[] encodePong(Pong pong) {
        Objects.requireNonNull(pong, "pong");
        ArrayByteSink sink = new ArrayByteSink();
        sink.writeInt(pong.nonce());
        sink.writeLong(pong.clientTimeMs());
        return sink.toByteArray();
    }

    public static Pong decodePong(byte[] data) {
        Objects.requireNonNull(data, "data");
        ArrayByteSource source = new ArrayByteSource(data);
        Pong pong = new Pong(source.readInt(), source.readLong());
        requireFullyConsumed(source, "PONG");
        return pong;
    }

    private static void requireFullyConsumed(ByteSource source, String message) {
        if (source.readableBytes() != 0) {
            throw new ProtocolException("Trailing bytes in " + message + ": " + source.readableBytes());
        }
    }
}
