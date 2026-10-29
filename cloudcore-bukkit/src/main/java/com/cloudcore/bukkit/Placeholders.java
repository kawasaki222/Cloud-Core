package com.cloudcore.bukkit;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.bukkit.api.PlaceholderResolver;
import com.cloudcore.server.placeholder.DocumentText;
import com.cloudcore.server.placeholder.PlaceholderExpressions;
import com.cloudcore.server.placeholder.PlaceholderToken;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Expands {@code %token%} in page text.
 *
 * <p>Order: addon resolvers registered through {@code CloudCoreApi} first (they may shadow a
 * PlaceholderAPI namespace on purpose), then PlaceholderAPI itself when installed.
 * PlaceholderAPI is a soft dependency, so it is reached reflectively: the plugin still loads —
 * and pages still work — on a server without it, with {@code %tokens%} left as written.
 * Expansion runs on the main thread once per send; the client never sees a token.
 */
final class Placeholders {
    private static final Pattern TOKEN = Pattern.compile("%([A-Za-z0-9_]{1,64})%");

    private final JavaPlugin plugin;
    private final Map<String, PlaceholderResolver> resolvers;
    private Method setPlaceholders;
    private boolean probed;
    private boolean brokenReported;
    private boolean resolverFailureReported;

    Placeholders(JavaPlugin plugin, Map<String, PlaceholderResolver> resolvers) {
        this.plugin = plugin;
        this.resolvers = resolvers;
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

    /**
     * Expands every visible text of the document.
     *
     * @param papiEnabled whether PlaceholderAPI tokens are expanded (addon resolvers always are)
     */
    UiDocument expand(Player player, UiDocument document, boolean papiEnabled) {
        if (resolvers.isEmpty() && !(papiEnabled && available())) {
            return document;
        }
        return DocumentText.expand(document, text -> apply(player, text, papiEnabled));
    }

    private String apply(Player player, String text, boolean papiEnabled) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        boolean hasPercent = text.indexOf('%') >= 0;
        boolean hasExplicit = text.indexOf("${") >= 0
                && text.toLowerCase(java.util.Locale.ROOT).contains("${placeholder:");
        if (!hasPercent && !hasExplicit) {
            return text;
        }
        if (hasExplicit) {
            return PlaceholderExpressions.expand(text,
                    chunk -> applyPercent(player, chunk, papiEnabled));
        }
        return applyPercent(player, text, papiEnabled);
    }

    private String applyPercent(Player player, String text, boolean papiEnabled) {
        String resolved = applyCustom(player, text);
        return papiEnabled && available() ? applyPapi(player, resolved) : resolved;
    }

    /** Replaces tokens whose namespace an addon registered; everything else is left alone. */
    private String applyCustom(Player player, String text) {
        if (resolvers.isEmpty()) {
            return text;
        }
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String replacement = matcher.group();
            PlaceholderToken token = PlaceholderToken.parse(matcher.group(1));
            PlaceholderResolver resolver = token == null ? null : resolvers.get(token.namespace());
            if (resolver != null) {
                try {
                    String value = resolver.resolve(player, token.params());
                    if (value != null) {
                        replacement = value;
                    }
                } catch (Exception exception) {
                    if (!resolverFailureReported) {
                        resolverFailureReported = true;
                        plugin.getLogger().log(Level.WARNING, "Placeholder resolver '"
                                + token.namespace() + "' failed; the token is sent as written",
                                exception);
                    }
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String applyPapi(Player player, String text) {
        if (text.indexOf('%') < 0) {
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
