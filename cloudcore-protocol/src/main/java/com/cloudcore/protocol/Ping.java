package com.cloudcore.protocol;

/**
 * Server-to-client heartbeat. The nonce is an unsigned 32-bit value on the wire.
 */
public record Ping(int nonce, long serverTimeMs) {
}
