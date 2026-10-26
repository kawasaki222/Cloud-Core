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

    private static final Map<String, Identifier> CACHE = new HashMap<>();
    private static final Identifier WHITE_ID = Identifier.fromNamespaceAndPath("cloudcore", "builtin/white");
    private static final Identifier ERROR_ID = Identifier.fromNamespaceAndPath("cloudcore", "builtin/error");

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

    public static Identifier resolve(String name) {
        return switch (name) {
            case "white" -> white();
            case "error" -> error();
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
