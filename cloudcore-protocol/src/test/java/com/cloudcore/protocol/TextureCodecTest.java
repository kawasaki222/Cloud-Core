package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class TextureCodecTest {

    @Test
    void requestRoundTrip() {
        TextureRequest request = new TextureRequest("abc123");
        TextureRequest decoded = TextureRequestCodec.decode(
                TextureRequestCodec.encode(request, Limits.C2S), Limits.C2S);
        assertEquals("abc123", decoded.key());
    }

    @Test
    void staticPutRoundTrip() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        TexturePut put = new TexturePut("k", 16, 8, TexturePut.FORMAT_STATIC_PNG,
                0x12345678, png, 1, 0, new int[0]);

        TexturePut decoded = TexturePutCodec.decode(
                TexturePutCodec.encode(put, Limits.S2C), Limits.S2C);

        assertEquals("k", decoded.key());
        assertEquals(16, decoded.width());
        assertEquals(8, decoded.height());
        assertEquals(TexturePut.FORMAT_STATIC_PNG, decoded.format());
        assertEquals(0x12345678, decoded.crc32());
        assertArrayEquals(png, decoded.data());
        assertEquals(1, decoded.frameCount());
        assertEquals(0, decoded.loop());
        assertArrayEquals(new int[0], decoded.frameDelaysMs());
    }

    @Test
    void animatedPutRoundTrip() {
        TexturePut put = new TexturePut("gif", 32, 64, TexturePut.FORMAT_ANIMATED_SHEET,
                1, new byte[] {1, 2, 3, 4}, 2, 0, new int[] {50, 120});

        TexturePut decoded = TexturePutCodec.decode(
                TexturePutCodec.encode(put, Limits.S2C), Limits.S2C);

        assertEquals(2, decoded.frameCount());
        assertArrayEquals(new int[] {50, 120}, decoded.frameDelaysMs());
    }

    @Test
    void rejectsOversizedData() {
        byte[] tooLarge = new byte[TexturePut.MAX_DATA_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> new TexturePut("k", 1, 1,
                TexturePut.FORMAT_STATIC_PNG, 0, tooLarge, 1, 0, new int[0]));
    }

    @Test
    void rejectsUnknownFormat() {
        assertThrows(IllegalArgumentException.class, () -> new TexturePut("k", 1, 1, 9, 0,
                new byte[] {1}, 1, 0, new int[0]));
    }
}
