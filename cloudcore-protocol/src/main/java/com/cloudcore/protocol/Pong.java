package com.cloudcore.protocol;

/**
 * Client response to {@link Ping}. The nonce is an unsigned 32-bit value on the wire.
 */
public record Pong(int nonce, long clientTimeMs) {
}
