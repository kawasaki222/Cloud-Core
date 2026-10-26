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

    private TextureReferences() {
    }

    public static Identifier resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        if (reference.startsWith("local:")) {
            return LocalTextureRegistry.resolveLocal(reference.substring("local:".length()));
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
            report("CC-ASSET-007", "inline texture requires TEXTURE_PUT: " + reference);
            return null;
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
