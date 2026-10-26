package com.cloudcore.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads PNG/JPEG files from the client's local CloudCore resource folder.
 * Paths are relative to {@code .minecraft/resourcepacks/CloudCore/} and may not escape it.
 */
public final class LocalTextureRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final Map<String, Identifier> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;

    private LocalTextureRegistry() {
    }

    public static Identifier resolveLocal(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        if (CACHE.containsKey(relativePath)) {
            return CACHE.get(relativePath);
        }
        if (FAILED.contains(relativePath)) {
            return null;
        }
        Identifier identifier = load(relativePath);
        if (identifier == null) {
            FAILED.add(relativePath);
            return null;
        }
        return identifier;
    }

    static void resetFailed() {
        FAILED.clear();
    }

    /**
     * Reads the raw file for backends that decode images themselves (NanoVG). The same root
     * containment and size checks apply.
     */
    static byte[] readBytes(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        Path root = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("resourcepacks").resolve("CloudCore").toAbsolutePath().normalize();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) {
            LOGGER.warn("[CC-ASSET-001] local texture escapes CloudCore root: {}", relativePath);
            return null;
        }
        if (!Files.isRegularFile(file)) {
            LOGGER.warn("[CC-ASSET-002] local texture missing: {}", relativePath);
            return null;
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                LOGGER.warn("[CC-ASSET-004] local texture exceeds {} bytes: {}",
                        MAX_FILE_BYTES, relativePath);
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            LOGGER.warn("[CC-ASSET-003] cannot read local texture " + relativePath, exception);
            return null;
        }
    }

    private static Identifier load(String relativePath) {
        Path root = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("resourcepacks").resolve("CloudCore").toAbsolutePath().normalize();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) {
            LOGGER.warn("[CC-ASSET-001] local texture escapes CloudCore root: {}", relativePath);
            return null;
        }
        if (!Files.isRegularFile(file)) {
            LOGGER.warn("[CC-ASSET-002] local texture missing: {}", relativePath);
            return null;
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                LOGGER.warn("[CC-ASSET-004] local texture exceeds {} bytes: {}",
                        MAX_FILE_BYTES, relativePath);
                return null;
            }
        } catch (IOException exception) {
            LOGGER.warn("[CC-ASSET-003] cannot stat local texture " + relativePath, exception);
            return null;
        }
        String key = Integer.toHexString(file.toString().hashCode());
        Identifier identifier = Identifier.fromNamespaceAndPath("cloudcore", "local/" + key);
        NativeImage image = null;
        try (InputStream input = Files.newInputStream(file)) {
            image = NativeImage.read(input);
            DynamicTexture texture = new DynamicTexture(
                    () -> "cloudcore-local:" + relativePath, image);
            Minecraft.getInstance().getTextureManager().register(identifier, texture);
            CACHE.put(relativePath, identifier);
            image = null; // DynamicTexture owns it now.
            LOGGER.info("Loaded local texture {} -> {}", relativePath, identifier);
            return identifier;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("[CC-ASSET-003] failed to load local texture " + relativePath, exception);
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }
}
