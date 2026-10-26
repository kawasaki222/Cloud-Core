package com.cloudcore.bukkit;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.server.placeholder.DocumentText;
import java.lang.reflect.Method;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Expands page text with PlaceholderAPI when that plugin is installed.
 *
 * <p>PlaceholderAPI is a soft dependency, so the class is reached reflectively: the plugin
 * still loads — and pages still work — on a server without it, with {@code %tokens%} left as
 * written. Expansion runs on the main thread once per send; the client never sees a token.
 */
final class Placeholders {
    private final JavaPlugin plugin;
    private Method setPlaceholders;
    private boolean probed;
    private boolean brokenReported;

    Placeholders(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** @return true when PlaceholderAPI is present and usable */
    boolean available() {
        if (!probed) {
            probed = true;
            try {
                Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                setPlaceholders = api.getMethod("setPlaceholders",
                        org.bukkit.OfflinePlayer.class, String.class);
                plugin.getLogger().info("PlaceholderAPI found; page text is expanded on send");
            } catch (ClassNotFoundException | NoSuchMethodException exception) {
                plugin.getLogger().info("PlaceholderAPI not installed; %placeholders% stay as text");
            }
        }
        return setPlaceholders != null;
    }

    /** Expands every visible text of the document; a no-op without PlaceholderAPI. */
    UiDocument expand(Player player, UiDocument document) {
        if (!available()) {
            return document;
        }
        return DocumentText.expand(document, text -> apply(player, text));
    }

    private String apply(Player player, String text) {
        if (text == null || text.indexOf('%') < 0) {
            return text;
        }
        try {
            Object result = setPlaceholders.invoke(null, player, text);
            return result instanceof String replaced ? replaced : text;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (!brokenReported) {
                brokenReported = true;
                plugin.getLogger().log(Level.WARNING,
                        "PlaceholderAPI expansion failed; text is sent unexpanded", exception);
            }
            return text;
        }
    }
}
