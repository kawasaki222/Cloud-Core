package com.cloudcore.protocol;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Reassembles FRAGMENT frames for one direction of one connection.
 *
 * <p>Instances are not thread-safe and are expected to be owned by one connection's
 * network thread.
 */
public final class FragmentAssembler {
    public static final long DEFAULT_TIMEOUT_MILLIS = 5_000L;
    public static final int DEFAULT_MAX_CONCURRENT_GROUPS = 4;
    private static final int COMPRESSED_OVERHEAD_ALLOWANCE = 65_536;

    private final Limits limits;
    private final long timeoutMillis;
    private final int maxConcurrentGroups;
    private final LinkedHashMap<Integer, Group> groups = new LinkedHashMap<>();

    public FragmentAssembler(Limits limits) {
        this(limits, DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_CONCURRENT_GROUPS);
    }

    public FragmentAssembler(Limits limits, long timeoutMillis, int maxConcurrentGroups) {
        this.limits = Objects.requireNonNull(limits, "limits");
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (maxConcurrentGroups <= 0) {
            throw new IllegalArgumentException("maxConcurrentGroups must be positive");
        }
        this.timeoutMillis = timeoutMillis;
        this.maxConcurrentGroups = maxConcurrentGroups;
    }

    public Optional<Frame> accept(Frame frame) {
        return accept(frame, System.currentTimeMillis());
    }

    public Optional<Frame> accept(Frame frame, long nowMillis) {
        Objects.requireNonNull(frame, "frame");
        if ((frame.flags() & FrameFlags.FRAGMENT) == 0) {
            throw new ProtocolException("Frame is not fragmented");
        }
        if ((frame.flags() & ~FrameFlags.KNOWN_MASK) != 0) {
            throw new ProtocolException("Unknown frame flags: " + frame.flags());
        }

        expire(nowMillis);

        ArrayByteSource source = new ArrayByteSource(frame.body());
        int fragmentId = source.readUnsignedShort();
        Group existing = groups.get(fragmentId);
        if (existing != null && existing.isExpired(nowMillis)) {
            groups.remove(fragmentId);
            existing = null;
        }
        try {
            int index = source.readUnsignedByte();
            int count = source.readUnsignedByte();
            int totalBodyLength = source.readVarInt();
            int chunkLength = source.readVarInt();

            if (count < 2) {
                throw fail(fragmentId, "Fragment count must be at least 2: " + count);
            }
            if (index >= count) {
                throw fail(fragmentId, "Fragment index " + index + " outside count " + count);
            }
            if (totalBodyLength < 0 || totalBodyLength > limits.maxDecompressedBytes()) {
                throw fail(fragmentId, "Invalid total body length: " + totalBodyLength);
            }
            if (chunkLength < 0 || chunkLength > limits.maxFragmentPayload()) {
                throw fail(fragmentId, "Invalid chunk length: " + chunkLength);
            }
            if (chunkLength != source.readableBytes()) {
                throw fail(fragmentId, "Chunk length mismatch: header " + chunkLength
                        + ", payload " + source.readableBytes());
            }
            byte[] chunk = source.readBytes(chunkLength);

            boolean compressed = (frame.flags() & FrameFlags.COMPRESSED) != 0;
            Group group = existing;
            if (group == null) {
                if (groups.size() >= maxConcurrentGroups) {
                    throw new ProtocolException("Too many concurrent fragment groups");
                }
                group = new Group(frame.version(), frame.opcode(), count,
                        totalBodyLength, compressed, nowMillis, timeoutMillis);
                groups.put(fragmentId, group);
            } else if (!group.matches(frame.opcode(), count, totalBodyLength, compressed)) {
                throw fail(fragmentId, "Conflicting fragment group metadata");
            }

            byte[] previous = group.chunks[index];
            if (previous != null) {
                if (!Arrays.equals(previous, chunk)) {
                    throw fail(fragmentId, "Duplicate fragment index with different content");
                }
            } else {
                if (!compressed && group.receivedBytes + chunkLength > totalBodyLength) {
                    throw fail(fragmentId, "Uncompressed fragments exceed declared length");
                }
                long wireLimit = compressed
                        ? (long) limits.maxDecompressedBytes() + COMPRESSED_OVERHEAD_ALLOWANCE
                        : limits.maxDecompressedBytes();
                if (group.receivedBytes + chunkLength > wireLimit) {
                    throw fail(fragmentId, "Reassembled payload exceeds limit");
                }
                group.chunks[index] = chunk;
                group.received++;
                group.receivedBytes += chunkLength;
            }

            if (group.received != group.count) {
                return Optional.empty();
            }

            groups.remove(fragmentId);
            byte[] payload = group.join();
            byte[] body = compressed
                    ? Deflate.decompress(payload, limits.maxDecompressedBytes())
                    : payload;
            if (body.length != totalBodyLength) {
                throw new ProtocolException("Reassembled length mismatch: expected "
                        + totalBodyLength + ", got " + body.length);
            }
            int flags = compressed ? FrameFlags.COMPRESSED : 0;
            return Optional.of(new Frame(group.version, flags, group.opcode, body));
        } catch (ProtocolException exception) {
            groups.remove(fragmentId);
            throw exception;
        }
    }

    public void clear() {
        groups.clear();
    }

    public int activeGroupCount() {
        return groups.size();
    }

    private void expire(long nowMillis) {
        Iterator<Map.Entry<Integer, Group>> iterator = groups.entrySet().iterator();
        while (iterator.hasNext()) {
            Group group = iterator.next().getValue();
            if (group.isExpired(nowMillis)) {
                iterator.remove();
            }
        }
    }

    private ProtocolException fail(int fragmentId, String message) {
        groups.remove(fragmentId);
        return new ProtocolException(message);
    }

    private static final class Group {
        private final int version;
        private final int opcode;
        private final int count;
        private final int totalBodyLength;
        private final boolean compressed;
        private final long createdAtMillis;
        private final long timeoutMillis;
        private final byte[][] chunks;
        private int received;
        private long receivedBytes;

        private Group(int version, int opcode, int count,
                int totalBodyLength, boolean compressed, long createdAtMillis, long timeoutMillis) {
            this.version = version;
            this.opcode = opcode;
            this.count = count;
            this.totalBodyLength = totalBodyLength;
            this.compressed = compressed;
            this.createdAtMillis = createdAtMillis;
            this.timeoutMillis = timeoutMillis;
            this.chunks = new byte[count][];
        }

        private boolean matches(int opcode, int count, int totalBodyLength, boolean compressed) {
            return this.opcode == opcode
                    && this.count == count
                    && this.totalBodyLength == totalBodyLength
                    && this.compressed == compressed;
        }

        private boolean isExpired(long nowMillis) {
            return nowMillis - createdAtMillis >= timeoutMillis;
        }

        private byte[] join() {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(receivedBytes, 65_536));
            for (byte[] chunk : chunks) {
                out.write(chunk, 0, chunk.length);
            }
            return out.toByteArray();
        }
    }
}
