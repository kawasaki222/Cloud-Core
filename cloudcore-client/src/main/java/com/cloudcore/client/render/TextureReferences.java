package com.cloudcore.client.render;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves document texture references. Order of support:
 * {@code local:} (CloudCore resource folder) → {@code pack:} (resource manager) →
 * {@code builtin:} (generated textures). {@code inline:} needs {@code TEXTURE_PUT} and is
 * rejected until that path exists; {@code url:} never reaches the client as a raw URL.
 */
public final class TextureReferences {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final Set<String> REPORTED = new HashSet<>();
    private static final java.util.Map<String, Identifier> INLINE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    private TextureReferences() {
    }

    public static Identifier resolve(String reference) {
        return resolve(reference, RenderCommand.Image.Animation.DEFAULT);
    }

    public static Identifier resolve(String reference, RenderCommand.Image.Animation animation) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        if (reference.startsWith("local:")) {
            return LocalTextureRegistry.resolveLocal(reference.substring("local:".length()),
                    animation);
        }
        if (reference.startsWith("pack:")) {
            return resolvePack(reference.substring("pack:".length()));
        }
        if (reference.startsWith("builtin:")) {
            Identifier builtin = BuiltinTextures.resolve(reference.substring("builtin:".length()));
            if (builtin == null) {
                report("CC-ASSET-008", "unknown builtin texture: " + reference);
            }
            return builtin;
        }
        if (reference.startsWith("inline:")) {
            return resolveInline(reference.substring("inline:".length()), animation);
        }
        report("CC-ASSET-005", "unsupported texture reference: " + reference);
        return null;
    }

    /** Raw image bytes for vector backends that decode themselves; {@code null} if absent. */
    public static byte[] readBytes(String reference) {
        if (reference == null) {
            return null;
        }
        if (reference.startsWith("local:")) {
            return LocalTextureRegistry.readBytes(reference.substring("local:".length()));
        }
        if (reference.startsWith("pack:")) {
            Identifier identifier = packIdentifier(reference.substring("pack:".length()));
            if (identifier == null) {
                return null;
            }
            var stack = Minecraft.getInstance().getResourceManager().getResourceStack(identifier);
            if (stack.isEmpty()) {
                report("CC-ASSET-006", "pack texture missing: " + identifier);
                return null;
            }
            try (var input = stack.get(0).open()) {
                return input.readAllBytes();
            } catch (Exception exception) {
                report("CC-ASSET-010", "cannot read pack texture " + identifier + ": "
                        + exception.getMessage());
                return null;
            }
        }
        return null;
    }

    /**
     * URL images arrive as {@code inline:<key>} plus a {@code TEXTURE_REF}; the bytes are
     * downloaded on a background thread and registered here on the render thread.
     */
    private static Identifier resolveInline(String key,
            RenderCommand.Image.Animation animation) {
        java.util.Map<String, Identifier> cache = INLINE_CACHE;
        Identifier cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = RemoteTextureCache.bytes(key);
        if (bytes == null) {
            // Pending or failed: draw nothing this frame; the download logs its own failure.
            return null;
        }
        if (AnimatedTextures.isGif(bytes)) {
            // Frames are chosen per draw; the render list keeps only the reference string.
            return AnimatedTextures.frame(key, bytes, System.currentTimeMillis(), animation);
        }
        try {
            com.mojang.blaze3d.platform.NativeImage image =
                    com.mojang.blaze3d.platform.NativeImage.read(
                            new java.io.ByteArrayInputStream(bytes));
            Identifier identifier = Identifier.fromNamespaceAndPath("cloudcore", "inline/" + key);
            net.minecraft.client.renderer.texture.DynamicTexture texture =
                    new net.minecraft.client.renderer.texture.DynamicTexture(
                            () -> "cloudcore-inline:" + key, image);
            Minecraft.getInstance().getTextureManager().register(identifier, texture);
            cache.put(key, identifier);
            LOGGER.info("Registered inline texture {} ({} bytes)", key, bytes.length);
            return identifier;
        } catch (java.io.IOException | RuntimeException exception) {
            report("CC-ASSET-012", "cannot decode inline texture " + key + ": "
                    + exception.getMessage());
            return null;
        }
    }

    private static Identifier packIdentifier(String path) {
        String namespace = "minecraft";
        String location = path;
        int separator = path.indexOf(':');
        if (separator > 0) {
            namespace = path.substring(0, separator);
            location = path.substring(separator + 1);
        }
        if (!location.startsWith("textures/")) {
            location = "textures/" + location;
        }
        if (!location.endsWith(".png")) {
            location = location + ".png";
        }
        try {
            return Identifier.fromNamespaceAndPath(namespace, location);
        } catch (RuntimeException exception) {
            report("CC-ASSET-009", "invalid pack texture " + path + ": " + exception.getMessage());
            return null;
        }
    }

    private static Identifier resolvePack(String path) {
        Identifier identifier = packIdentifier(path);
        if (identifier == null) {
            return null;
        }
        if (Minecraft.getInstance().getResourceManager().getResourceStack(identifier).isEmpty()) {
            report("CC-ASSET-006", "pack texture missing: " + identifier);
            return null;
        }
        return identifier;
    }

    /** One log line per distinct reference keeps a broken page from flooding the log. */
    private static void report(String code, String message) {
        if (REPORTED.add(message)) {
            LOGGER.warn("[{}] {}", code, message);
        }
    }

    static void resetReported() {
        REPORTED.clear();
    }
}
