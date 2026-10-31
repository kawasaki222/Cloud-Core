package com.cloudcore.client.render;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pixel sizes for {@code fit: contain|cover} without registering a texture.
 *
 * <p>Every caller runs on the render thread and the same few references are asked for every
 * frame, so results are memoised by identity: the byte arrays come from the local texture and
 * remote caches, which keep one array per texture for the life of the session.
 *
 * <p>Local and remote caches are dropped on {@code F9}/reconnect, so the table is cleared
 * together with them by {@link #clear()}.
 */
final class ImageSizes {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final Map<IdentityKey, int[]> CACHE = new HashMap<>();
    private static final int[] FAILED = new int[0];

    private ImageSizes() {
    }

    /** @return {@code [width, height]}, or null when the bytes are not a readable image */
    static int[] of(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        IdentityKey key = new IdentityKey(bytes);
        synchronized (CACHE) {
            int[] cached = CACHE.get(key);
            if (cached != null) {
                return cached == FAILED ? null : cached;
            }
        }
        int[] size = read(bytes);
        synchronized (CACHE) {
            CACHE.put(key, size == null ? FAILED : size);
        }
        return size;
    }

    static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    /** Reads only the header; an animated GIF reports its first frame. */
    private static int[] read(byte[] bytes) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                return null;
            }
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return width > 0 && height > 0 ? new int[]{width, height} : null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.debug("Cannot read image size: {}", exception.getMessage());
            return null;
        }
    }

    /** Identity wrapper: the same array object means the same texture, and hashing is O(1). */
    private static final class IdentityKey {
        private final byte[] bytes;

        private IdentityKey(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof IdentityKey key && key.bytes == bytes;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(bytes);
        }
    }
}
