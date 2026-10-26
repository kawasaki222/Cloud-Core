package com.cloudcore.protocol;

/**
 * Hard bounds for one frame and one typed value tree.
 *
 * <p>S2C frames may be up to 1 MiB; serverbound custom payloads are limited to 32767 bytes
 * by the vanilla protocol, so C2S is much smaller.
 */
public record Limits(
        int maxFrameBytes,
        int maxDecompressedBytes,
        int maxFragmentPayload,
        int maxStringBytes,
        int maxWireDepth,
        int maxWireEntries) {

    public static final Limits S2C = new Limits(1_048_576, 1_048_576, 24_000, 32_767, 16, 4_096);
    public static final Limits C2S = new Limits(32_767, 1_048_576, 24_000, 32_767, 16, 4_096);

    public Limits {
        if (maxFrameBytes <= 0 || maxDecompressedBytes <= 0 || maxFragmentPayload <= 0
                || maxStringBytes <= 0 || maxWireDepth <= 0 || maxWireEntries <= 0) {
            throw new IllegalArgumentException("All limits must be positive");
        }
    }
}
