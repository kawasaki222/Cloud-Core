package com.cloudcore.protocol;

import java.util.Objects;

/**
 * Server -> client {@code EVENT_RESULT}. {@code message} is a fixed enum name (for example
 * {@code OK}), never free text, so it cannot leak server internals.
 */
public record EventResult(int requestId, int status, String message) {

    public static final int OK = 0;
    public static final int REJECTED = 1;
    public static final int INTERNAL_ERROR = 2;
    public static final int RATE_LIMITED = 3;
    public static final int STALE_REVISION = 4;
    public static final int UNKNOWN_ELEMENT = 5;
    public static final int NO_PERMISSION = 6;
    public static final int COOLDOWN = 7;
    public static final int BUSY = 8;
    public static final int INVALID_PAYLOAD = 9;

    public EventResult {
        Objects.requireNonNull(message, "message");
        if (requestId < 0) {
            throw new IllegalArgumentException("requestId must be non-negative");
        }
        if (status < 0 || status > 0xFF) {
            throw new IllegalArgumentException("status out of range: " + status);
        }
    }

    public static String nameOf(int status) {
        return switch (status) {
            case OK -> "OK";
            case REJECTED -> "REJECTED";
            case INTERNAL_ERROR -> "INTERNAL_ERROR";
            case RATE_LIMITED -> "RATE_LIMITED";
            case STALE_REVISION -> "STALE_REVISION";
            case UNKNOWN_ELEMENT -> "UNKNOWN_ELEMENT";
            case NO_PERMISSION -> "NO_PERMISSION";
            case COOLDOWN -> "COOLDOWN";
            case BUSY -> "BUSY";
            case INVALID_PAYLOAD -> "INVALID_PAYLOAD";
            default -> "UNKNOWN";
        };
    }
}
