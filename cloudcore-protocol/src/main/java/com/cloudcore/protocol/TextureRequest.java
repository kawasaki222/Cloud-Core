package com.cloudcore.protocol;

import java.util.Objects;

/** Client -> server {@code TEXTURE_REQUEST}: asks the server to proxy or resend one texture. */
public record TextureRequest(String key) {
    public TextureRequest {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("texture key must not be blank");
        }
    }
}
