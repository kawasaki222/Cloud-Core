package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TextureRefCodecTest {

    @Test
    void roundTripsUrlReference() {
        TextureRef ref = new TextureRef("abc123", "https://cdn.example.com/ui/panel.png",
                "", 512, 256, TextureRef.FLAG_ANIMATED, 4L * 1024L * 1024L, 3600);

        TextureRef decoded = TextureRefCodec.decode(
                TextureRefCodec.encode(ref, Limits.S2C), Limits.S2C);

        assertEquals(ref, decoded);
    }

    @Test
    void rejectsNonHttpsUrlOnDecode() {
        byte[] body = TextureRefCodec.encode(new TextureRef("k",
                "https://example.com/a.png", "", 1, 1, 0, 1024, 0), Limits.S2C);
        byte[] https = "https".getBytes(StandardCharsets.US_ASCII);
        int index = indexOf(body, https);
        assertEquals(true, index >= 0, "encoded url must contain its scheme");
        body[index + 4] = 'x'; // https -> httpx

        assertThrows(ProtocolException.class, () -> TextureRefCodec.decode(body, Limits.S2C));
    }

    @Test
    void decodeRejectsTrailingBytes() {
        byte[] body = TextureRefCodec.encode(new TextureRef("k", "https://e.com/a.png", "",
                1, 1, 0, 1024, 0), Limits.S2C);
        byte[] longer = new byte[body.length + 1];
        System.arraycopy(body, 0, longer, 0, body.length);
        assertThrows(ProtocolException.class, () -> TextureRefCodec.decode(longer, Limits.S2C));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
