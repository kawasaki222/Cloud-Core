package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AnimatedTexturesTest {

    @Test
    void detectsGifBytes() {
        assertTrue(AnimatedTextures.isGif("GIF89a".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(AnimatedTextures.isGif("PNG".getBytes(StandardCharsets.US_ASCII)));
        assertFalse(AnimatedTextures.isGif(null));
    }

    @Test
    void identifiersAreSanitisedAndStable() {
        String first = AnimatedTextures.sanitise("local:test/anim.gif");
        String second = AnimatedTextures.sanitise("local:test/anim.gif");
        assertEquals(first, second);
        assertTrue(first.matches("[a-z0-9/._-]+"), first);
        assertFalse(first.contains(":"));
    }

    @Test
    void sanitisesNonAsciiPaths() {
        String identifier = AnimatedTextures.sanitise("\u516c\u5171/banner.GIF");
        assertTrue(identifier.matches("[a-z0-9/._-]+"), identifier);
    }
}
