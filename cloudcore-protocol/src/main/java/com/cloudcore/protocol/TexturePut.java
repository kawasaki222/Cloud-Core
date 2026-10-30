package com.cloudcore.protocol;

import java.util.Objects;

/**
 * Server -> client {@code TEXTURE_PUT}: one texture's bytes, already decompressed/decoded by
 * the server when URL proxying is enabled.
 *
 * <p>{@code format} 1 is a static PNG; format 2 is a vertical sprite sheet whose height is
 * {@code height * frameCount}. Frame delays are milliseconds and use the same 50 ms floor as
 * {@code TEXTURE_REF} GIF decoding.
 */
public record TexturePut(String key, int width, int height, int format, int crc32,
        byte[] data, int frameCount, int loop, int[] frameDelaysMs) {
    public static final int FORMAT_STATIC_PNG = 1;
    public static final int FORMAT_ANIMATED_SHEET = 2;
    public static final int MAX_DATA_BYTES = 1_048_576;
    public static final int MAX_FRAMES = 64;
    public static final int MAX_DIMENSION = 512;
    public static final int MIN_DELAY_MS = 50;

    public TexturePut {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("texture key must not be blank");
        }
        if (width < 0 || width > 0xFFFF || height < 0 || height > 0xFFFF) {
            throw new IllegalArgumentException("texture size out of range");
        }
        if (format != FORMAT_STATIC_PNG && format != FORMAT_ANIMATED_SHEET) {
            throw new IllegalArgumentException("unsupported texture format: " + format);
        }
        Objects.requireNonNull(data, "data");
        if (data.length == 0 || data.length > MAX_DATA_BYTES) {
            throw new IllegalArgumentException("texture data size out of range: "
                    + data.length);
        }
        if (frameCount < 1 || frameCount > MAX_FRAMES) {
            throw new IllegalArgumentException("frameCount out of range: " + frameCount);
        }
        Objects.requireNonNull(frameDelaysMs, "frameDelaysMs");
        if (format == FORMAT_STATIC_PNG && frameCount != 1) {
            throw new IllegalArgumentException("static texture must have one frame");
        }
        if (frameDelaysMs.length != 0 && frameDelaysMs.length != frameCount) {
            throw new IllegalArgumentException("frame delays must match frameCount");
        }
        for (int delay : frameDelaysMs) {
            if (delay < 0 || delay > 0xFFFF) {
                throw new IllegalArgumentException("frame delay out of range: " + delay);
            }
        }
        if (loop < 0 || loop > 0xFF) {
            throw new IllegalArgumentException("loop out of range: " + loop);
        }
        data = data.clone();
        frameDelaysMs = frameDelaysMs.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }

    @Override
    public int[] frameDelaysMs() {
        return frameDelaysMs.clone();
    }
}
