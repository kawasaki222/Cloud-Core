package com.cloudcore.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Splits a logical message into FRAGMENT frames.
 *
 * <p>Compression is applied once to the logical body, before splitting. When compression
 * is requested but does not make the payload smaller, the payload is sent uncompressed.
 * A payload that fits in one fragment is returned as a normal frame and never uses the
 * FRAGMENT flag.
 */
public final class Fragmenter {
    public static final int MAX_FRAGMENT_COUNT = 255;
    private static final int MAX_ENVELOPE_HEADER = 14;

    private Fragmenter() {
    }

    public static List<Frame> fragment(Frame frame, Limits limits, int fragmentId) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(limits, "limits");
        if (fragmentId < 0 || fragmentId > 0xFFFF) {
            throw new ProtocolException("fragmentId out of range: " + fragmentId);
        }
        if ((frame.flags() & FrameFlags.FRAGMENT) != 0) {
            throw new ProtocolException("Cannot fragment an already fragmented frame");
        }
        if ((frame.flags() & ~FrameFlags.KNOWN_MASK) != 0) {
            throw new ProtocolException("Unknown frame flags: " + frame.flags());
        }
        if (frame.bodyLength() > limits.maxDecompressedBytes()) {
            throw new ProtocolException("Body exceeds decompressed limit: " + frame.bodyLength());
        }

        byte[] payload = frame.body();
        boolean compressed = false;
        if ((frame.flags() & FrameFlags.COMPRESSED) != 0
                && payload.length >= HandshakeCodec.COMPRESSION_THRESHOLD) {
            byte[] candidate = Deflate.compress(payload);
            if (candidate.length < payload.length) {
                payload = candidate;
                compressed = true;
            }
        }

        if (payload.length <= limits.maxFragmentPayload()) {
            int flags = compressed ? FrameFlags.COMPRESSED : 0;
            return List.of(new Frame(frame.version(), flags, frame.opcode(), frame.body()));
        }

        int count = (payload.length + limits.maxFragmentPayload() - 1) / limits.maxFragmentPayload();
        if (count > MAX_FRAGMENT_COUNT) {
            throw new ProtocolException("Message requires too many fragments: " + count);
        }

        List<Frame> fragments = new ArrayList<>(count);
        int offset = 0;
        for (int index = 0; index < count; index++) {
            int chunkLength = Math.min(limits.maxFragmentPayload(), payload.length - offset);
            ArrayByteSink body = new ArrayByteSink();
            body.writeShort(fragmentId);
            body.writeByte(index);
            body.writeByte(count);
            body.writeVarInt(frame.bodyLength());
            body.writeVarInt(chunkLength);
            body.writeBytes(java.util.Arrays.copyOfRange(payload, offset, offset + chunkLength));

            byte[] fragmentBody = body.toByteArray();
            if (fragmentBody.length + MAX_ENVELOPE_HEADER > limits.maxFrameBytes()) {
                throw new ProtocolException("Fragment exceeds frame limit: " + fragmentBody.length);
            }
            int flags = FrameFlags.FRAGMENT | (compressed ? FrameFlags.COMPRESSED : 0);
            fragments.add(new Frame(frame.version(), flags, frame.opcode(), fragmentBody));
            offset += chunkLength;
        }
        return List.copyOf(fragments);
    }

    public static int randomFragmentId(RandomGenerator random) {
        Objects.requireNonNull(random, "random");
        return random.nextInt(0x10000);
    }
}
