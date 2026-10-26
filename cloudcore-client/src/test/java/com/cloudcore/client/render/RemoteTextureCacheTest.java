package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class RemoteTextureCacheTest {

    @Test
    void sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                RemoteTextureCache.sha256("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void unknownKeyHasNoBytes() {
        RemoteTextureCache.clear();
        assertNull(RemoteTextureCache.bytes("missing"));
    }
}
