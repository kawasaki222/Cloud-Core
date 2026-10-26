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
 * varint bodyLength
 * bytes payload (raw, raw-deflate, or fragment body)
 * </pre>
 *
 * <p>For normal frames {@code bodyLength} is the uncompressed logical body length. For
 * FRAGMENT frames it is the length of the fragment body because the fragment header carries
 * the logical length separately.
 */
public final class FrameCodec {
    public static final int MAGIC = 0x4343;
    public static final int VERSION = ProtocolVersion.MAJOR;

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
        boolean fragmented = (frame.flags() & FrameFlags.FRAGMENT) != 0;
        int bodyLimit = fragmented ? limits.maxFrameBytes() : limits.maxDecompressedBytes();
        if (frame.bodyLength() > bodyLimit) {
            throw new ProtocolException("Body exceeds frame limit: " + frame.bodyLength());
        }

        byte[] payload = frame.body();
        if (!fragmented && (frame.flags() & FrameFlags.COMPRESSED) != 0) {
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
        boolean fragmented = (flags & FrameFlags.FRAGMENT) != 0;
        int bodyLimit = fragmented ? limits.maxFrameBytes() : limits.maxDecompressedBytes();
        if (bodyLength < 0 || bodyLength > bodyLimit) {
            throw new ProtocolException("Invalid body length: " + bodyLength);
        }

        int remaining = source.readableBytes();
        byte[] body;
        if (fragmented) {
            if (remaining != bodyLength) {
                throw new ProtocolException(
                        "Fragment body length mismatch: header " + bodyLength + ", payload " + remaining);
            }
            body = source.readBytes(bodyLength);
        } else if ((flags & FrameFlags.COMPRESSED) != 0) {
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
