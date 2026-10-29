package com.cloudcore.client.render;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.nanovg.NanoVG;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads TTF/OTF fonts from the CloudCore resource folder into a NanoVG context. Fonts live
 * on the client (resource pack or CloudCore folder); the server only sends the relative path.
 *
 * <p>Render thread only. The byte buffers are retained because NanoVG keeps pointers into
 * them for the lifetime of the font.
 */
public final class FontRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    /** Unicode escapes keep the default path independent of the build's source encoding. */
    private static final String DEFAULT_FONT = "公共/字体/body.ttf";
    private static final int MAX_FONT_BYTES = 8 * 1024 * 1024;

    private final Map<String, Integer> fontIds = new HashMap<>();
    /**
     * Every buffer ever handed to NanoVG, in load order. Nothing is ever removed: NanoVG
     * keeps a raw pointer into each font's memory until its context is deleted, so dropping
     * a reference here would let LWJGL free the block while NanoVG still reads it. The cost
     * is bounded by manual {@code F9} reloads and is documented in docs/10.
     */
    private final java.util.List<ByteBuffer> retained = new java.util.ArrayList<>();
    private long context;

    public void attach(long context) {
        this.context = context;
        // Font ids point into the old context's table; drop them so the next resolve loads
        // into the new context. The buffers themselves stay retained (see above).
        fontIds.clear();
    }

    public boolean hasFont() {
        return resolve(null) >= 0;
    }

    /**
     * @param path CloudCore-relative font path, or {@code null} for the default font
     * @return NanoVG font id, or -1 when the font cannot be loaded
     */
    public int resolve(String path) {
        if (context == 0L) {
            return -1;
        }
        String key = path == null || path.isBlank() ? DEFAULT_FONT : path;
        Integer cached = fontIds.get(key);
        if (cached != null) {
            return cached;
        }
        int id = load(key);
        fontIds.put(key, id);
        return id;
    }

    private int load(String path) {
        if (path.startsWith("resource:")) {
            LOGGER.warn("[CC-FONT-001] resource-pack fonts are not supported yet: {}", path);
            return -1;
        }
        byte[] bytes = LocalTextureRegistry.readBytes(path);
        if (bytes == null || bytes.length == 0) {
            LOGGER.warn("[CC-FONT-002] font missing: {}", path);
            return -1;
        }
        if (bytes.length > MAX_FONT_BYTES) {
            LOGGER.warn("[CC-FONT-003] font exceeds {} bytes: {}", MAX_FONT_BYTES, path);
            return -1;
        }
        ByteBuffer buffer = org.lwjgl.BufferUtils.createByteBuffer(bytes.length);
        buffer.put(bytes).flip();
        // freeData must stay false: NanoVG would release the buffer with its own free(),
        // but BufferUtils allocates through LWJGL's jemalloc, and freeing across the two
        // allocators corrupted the heap. That mismatch crashed the JVM inside
        // ByteBufferBuilder/ParticleFeatureRenderer and, deterministically, on
        // nvgDelete when the inventory closed. `retained` keeps the buffer alive instead.
        int id = NanoVG.nvgCreateFontMem(context, "cc:" + path, buffer, false);
        if (id < 0) {
            LOGGER.warn("[CC-FONT-004] NanoVG rejected font: {}", path);
            return -1;
        }
        retained.add(buffer);
        LOGGER.info("Loaded font {} as {} ({} bytes)", path, id, bytes.length);
        return id;
    }
}
