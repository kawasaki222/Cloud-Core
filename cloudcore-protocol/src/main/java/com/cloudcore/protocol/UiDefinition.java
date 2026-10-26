package com.cloudcore.protocol;

import java.util.Objects;

/**
 * One downloaded UI document. Encoding 0 is UTF-8 JSON; encoding 1 is the same JSON
 * carried by a compressed envelope.
 */
public record UiDefinition(String uiId, int revision, int encoding, byte[] data) {

    public UiDefinition {
        Objects.requireNonNull(uiId, "uiId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        if (encoding < 0 || encoding > 0xFF) {
            throw new IllegalArgumentException("encoding out of range: " + encoding);
        }
        Objects.requireNonNull(data, "data");
        data = data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
