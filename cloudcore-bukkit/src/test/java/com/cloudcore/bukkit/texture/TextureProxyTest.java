package com.cloudcore.bukkit.texture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class TextureProxyTest {

    @Test
    void allowlistRequiresHttpsAndPrefixBoundary() {
        List<String> allow = List.of("https://cdn.example.com/ui/");

        assertTrue(TextureProxy.isAllowed("https://cdn.example.com/ui/hero.png", allow));
        assertFalse(TextureProxy.isAllowed("https://cdn.example.com/other.png", allow));
        assertFalse(TextureProxy.isAllowed("https://cdn.example.com.evil/ui/x.png", allow));
        assertFalse(TextureProxy.isAllowed("http://cdn.example.com/ui/x.png", allow));
        assertFalse(TextureProxy.isAllowed("https://cdn.example.com:8443/ui/x.png", allow));
        assertFalse(TextureProxy.isAllowed("https://cdn.example.com/ui/x.png", List.of()));
    }

    @Test
    void staticImageBecomesPngWithDimensionsAndCrc() throws Exception {
        BufferedImage image = new BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(1, 1, 0xFF00FF00);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(image, "png", encoded);

        var put = TextureProxy.decode("key", encoded.toByteArray());

        assertEquals("key", put.key());
        assertEquals(4, put.width());
        assertEquals(3, put.height());
        assertEquals(1, put.frameCount());
        assertEquals(com.cloudcore.protocol.TexturePut.FORMAT_STATIC_PNG, put.format());
        byte[] png = put.data();
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        assertEquals((int) crc(png), put.crc32());
    }

    @Test
    void animatedGifBecomesAVerticalSpriteSheet() throws Exception {
        byte[] gif = java.util.Base64.getDecoder().decode(
                "R0lGODlhBAADAIEAAP8AAAAAAAAAAAAAACH/C05FVFNDQVBFMi4wAwEAAAAh+QQACgAAACwAAAAABAAD"
                + "AAAICAABCBxIUGBAACH5BAEKAAEALAAAAAAEAAMAgQD/AAAAAAAAAAAAAAgIAAEIHEhQYEAAOw==");

        var put = TextureProxy.decode("gif", gif);

        assertEquals(4, put.width());
        assertEquals(6, put.height());
        assertEquals(2, put.frameCount());
        assertEquals(com.cloudcore.protocol.TexturePut.FORMAT_ANIMATED_SHEET, put.format());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {100, 100},
                put.frameDelaysMs());
    }

    private static long crc(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
