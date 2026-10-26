package com.cloudcore.protocol;

import java.util.Objects;

/**
 * Encodes and decodes the Cloud Core envelope.
 *
 * <pre>
 * u16 magic 0x4343
 * u8  version
 * u8  flags
 * varint opcode
 * varint uncompressedBodyLength
 * bytes payload (raw or raw-deflate)
 * </pre>
 */
public final class FrameCodec {
    public static final int MAGIC = 0x4343;
    public static final int VERSION = 1;

    private FrameCodec() {
    }

    public static byte[] encode(Frame frame, Limits limits) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(limits, "limits");

        if (frame.version() != VERSION) {
            throw new ProtocolException("Unsupported frame version: " + frame.version());
        }
        if ((frame.flags() & ~FrameFlags.KNOWN_MASK) != 0) {
            throw new ProtocolException("Unknown frame flags: " + frame.flags());
        }
        if (frame.bodyLength() > limits.maxDecompressedBytes()) {
            throw new ProtocolException("Body exceeds decompressed limit: " + frame.bodyLength());
        }

        byte[] payload = frame.body();
        if ((frame.flags() & FrameFlags.COMPRESSED) != 0) {
            payload = Deflate.compress(payload);
        }

        ArrayByteSink sink = new ArrayByteSink();
        sink.writeShort(MAGIC);
        sink.writeByte(VERSION);
        sink.writeByte(frame.flags());
        sink.writeVarInt(frame.opcode());
        sink.writeVarInt(frame.bodyLength());
        sink.writeBytes(payload);

        byte[] encoded = sink.toByteArray();
        if (encoded.length > limits.maxFrameBytes()) {
            throw new ProtocolException("Encoded frame exceeds limit: " + encoded.length);
        }
        return encoded;
    }

    public static Frame decode(byte[] data, Limits limits) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(limits, "limits");

        if (data.length > limits.maxFrameBytes()) {
            throw new ProtocolException("Frame exceeds limit: " + data.length);
        }

        ArrayByteSource source = new ArrayByteSource(data);
        int magic = source.readUnsignedShort();
        if (magic != MAGIC) {
            throw new ProtocolException("Invalid magic: 0x" + Integer.toHexString(magic));
        }

        int version = source.readUnsignedByte();
        if (version != VERSION) {
            throw new ProtocolException("Unsupported frame version: " + version);
        }

        int flags = source.readUnsignedByte();
        if ((flags & ~FrameFlags.KNOWN_MASK) != 0) {
            throw new ProtocolException("Unknown frame flags: " + flags);
        }

        int opcode = source.readVarInt();
        int bodyLength = source.readVarInt();
        if (bodyLength < 0 || bodyLength > limits.maxDecompressedBytes()) {
            throw new ProtocolException("Invalid body length: " + bodyLength);
        }

        int remaining = source.readableBytes();
        byte[] body;
        if ((flags & FrameFlags.COMPRESSED) != 0) {
            byte[] compressed = source.readBytes(remaining);
            body = Deflate.decompress(compressed, limits.maxDecompressedBytes());
            if (body.length != bodyLength) {
                throw new ProtocolException(
                        "Decompressed length mismatch: expected " + bodyLength + ", got " + body.length);
            }
        } else {
            if (remaining != bodyLength) {
                throw new ProtocolException(
                        "Body length mismatch: header " + bodyLength + ", payload " + remaining);
            }
            body = source.readBytes(bodyLength);
        }

        return new Frame(version, flags, opcode, body);
    }
}
