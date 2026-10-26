package com.cloudcore.protocol;

import java.util.Objects;

public record Frame(int version, int flags, int opcode, byte[] body) {

    public Frame {
        Objects.requireNonNull(body, "body");
        if (version < 0 || version > 0xFF) {
            throw new IllegalArgumentException("version out of range: " + version);
        }
        if (flags < 0 || flags > 0xFF) {
            throw new IllegalArgumentException("flags out of range: " + flags);
        }
        if (opcode < 0) {
            throw new IllegalArgumentException("opcode must be non-negative");
        }
        body = body.clone();
    }

    public int bodyLength() {
        return body.length;
    }
}
