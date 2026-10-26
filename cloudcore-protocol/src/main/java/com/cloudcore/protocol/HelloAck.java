package com.cloudcore.protocol;

import java.util.Objects;

/**
 * Server response to {@link Hello}.
 */
public record HelloAck(
        int protoMajor,
        int protoMinor,
        int acceptedFeatures,
        String serverId,
        int maxFrameBytes,
        int compressionThreshold,
        String serverMessage) {

    public HelloAck {
        checkRange(protoMajor, 0, 0xFFFF, "protoMajor");
        checkRange(protoMinor, 0, 0xFFFF, "protoMinor");
        checkRange(acceptedFeatures, 0, 0xFFFF, "acceptedFeatures");
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        }
        if (compressionThreshold < 0) {
            throw new IllegalArgumentException("compressionThreshold must be non-negative");
        }
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(serverMessage, "serverMessage");
    }

    private static void checkRange(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " out of range: " + value);
        }
    }
}
