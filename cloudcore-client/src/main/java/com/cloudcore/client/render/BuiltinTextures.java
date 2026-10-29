package com.cloudcore.client.render;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Minimal textures shipped inside the mod for {@code builtin:} references and for failure
 * feedback. They are generated instead of loaded from the jar so the jar stays small.
 *
 * <p>Render thread only: {@code TextureManager#register} allocates GL state.
 */
public final class BuiltinTextures {
    public static final String WHITE = "builtin:white";
    public static final String ERROR = "builtin:error";
    /** A generated cross, used by the text field clear button when no art is supplied. */
    public static final String CLEAR = "builtin:clear";

    private static final Map<String, Identifier> CACHE = new HashMap<>();
    private static final Identifier WHITE_ID = Identifier.fromNamespaceAndPath("cloudcore", "builtin/white");
    private static final Identifier ERROR_ID = Identifier.fromNamespaceAndPath("cloudcore", "builtin/error");
    private static final Identifier CLEAR_ID = Identifier.fromNamespaceAndPath("cloudcore", "builtin/clear");

    private BuiltinTextures() {
    }

    public static Identifier white() {
        return register(WHITE, WHITE_ID, () -> "cloudcore-builtin:white", 1,
                (pixels, size) -> pixels.setPixel(0, 0, 0xFFFFFFFF));
    }

    public static Identifier error() {
        return register(ERROR, ERROR_ID, () -> "cloudcore-builtin:error", 2,
                (pixels, size) -> {
                    pixels.setPixel(0, 0, 0xFFFF00FF);
                    pixels.setPixel(1, 0, 0xFF000000);
                    pixels.setPixel(0, 1, 0xFF000000);
                    pixels.setPixel(1, 1, 0xFFFF00FF);
                });
    }

    /** An 8×8 white cross on transparency; a page can use it as a working clear button. */
    public static Identifier clear() {
        return register(CLEAR, CLEAR_ID, () -> "cloudcore-builtin:clear", 8,
                (pixels, size) -> {
                    for (int index = 0; index < size; index++) {
                        pixels.setPixel(index, index, 0xFFE8E8E8);
                        pixels.setPixel(size - 1 - index, index, 0xFFE8E8E8);
                    }
                });
    }

    public static Identifier resolve(String name) {
        return switch (name) {
            case "white" -> white();
            case "error" -> error();
            case "clear" -> clear();
            default -> null;
        };
    }

    private static Identifier register(String key, Identifier identifier, Supplier<String> label,
            int size, PixelPainter painter) {
        Identifier cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        DynamicTexture texture = new DynamicTexture(label, size, size, false);
        painter.paint(texture.getPixels(), size);
        texture.upload();
        Minecraft.getInstance().getTextureManager().register(identifier, texture);
        CACHE.put(key, identifier);
        return identifier;
    }

    @FunctionalInterface
    private interface PixelPainter {
        void paint(com.mojang.blaze3d.platform.NativeImage pixels, int size);
    }
}
