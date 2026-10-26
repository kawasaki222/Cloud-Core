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
    private final Map<String, ByteBuffer> fontData = new HashMap<>();
    private long context;

    public void attach(long context) {
        this.context = context;
        fontIds.clear();
        fontData.clear();
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
        int id = NanoVG.nvgCreateFontMem(context, "cc:" + path, buffer, true);
        if (id < 0) {
            LOGGER.warn("[CC-FONT-004] NanoVG rejected font: {}", path);
            return -1;
        }
        fontData.put(path, buffer);
        LOGGER.info("Loaded font {} as {} ({} bytes)", path, id, bytes.length);
        return id;
    }
}
