package com.cloudcore.protocol;

import java.util.Objects;

/**
 * Server -> client {@code TEXTURE_REF}: a URL image reference. The server sends the reference
 * only; the client fetches the bytes directly when the URL is https, or asks the server to
 * proxy. {@code sha256} may be empty when the server never saw the bytes.
 *
 * @param flags bit0 animated, bit1 sha256 present
 */
public record TextureRef(String key, String url, String sha256, int width, int height,
        int flags, long maxBytes, int ttlSeconds) {

    public static final int FLAG_ANIMATED = 0x01;
    public static final int FLAG_SHA256 = 0x02;

    public TextureRef {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(sha256, "sha256");
        if (key.isBlank()) {
            throw new IllegalArgumentException("texture key must not be blank");
        }
        if (!url.startsWith("https://")) {
            throw new IllegalArgumentException("TEXTURE_REF url must be https: " + url);
        }
        if (width < 0 || width > 0xFFFF || height < 0 || height > 0xFFFF) {
            throw new IllegalArgumentException("texture size out of range");
        }
        if (maxBytes <= 0 || maxBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes out of range: " + maxBytes);
        }
        if (ttlSeconds < 0 || ttlSeconds > 0xFFFF) {
            throw new IllegalArgumentException("ttlSeconds out of range: " + ttlSeconds);
        }
    }
}
