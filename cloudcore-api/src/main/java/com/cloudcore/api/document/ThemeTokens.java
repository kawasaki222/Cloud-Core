package com.cloudcore.api.document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The theme tokens a server's pages may reference.
 *
 * <p>A document keeps {@code tx:<name>} references and the client resolves them, so a typo
 * would silently fall back to a default colour. This table is what {@code /cloudcore validate}
 * checks against: a reference to a name that is not declared is reported as a warning with the
 * page path, instead of showing up as an unexplained grey box in game.
 *
 * <p>Names are case-insensitive and stored lower-cased. Pure Java.
 */
public final class ThemeTokens {

    /** One declared token: a colour, a texture reference or a font id. */
    public record Token(String name, String kind, String value) {
        public Token {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
        }
    }

    private final Map<String, Token> tokens = new LinkedHashMap<>();

    public ThemeTokens() {
    }

    /** Builds a table from a {@code theme.yml} {@code tokens:} map. */
    public static ThemeTokens from(Map<?, ?> raw) {
        ThemeTokens theme = new ThemeTokens();
        if (raw == null) {
            return theme;
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            theme.declare(name, kindOf(name, value), String.valueOf(value));
        }
        return theme;
    }

    public void declare(String name, String kind, String value) {
        String key = normalise(name);
        tokens.put(key, new Token(key, kind, value));
    }

    /** @return the token, or null when the theme does not declare it */
    public Token find(String name) {
        return tokens.get(normalise(name));
    }

    public boolean contains(String name) {
        return tokens.containsKey(normalise(name));
    }

    public Set<String> names() {
        return Set.copyOf(tokens.keySet());
    }

    public int size() {
        return tokens.size();
    }

    /**
     * Names referenced by a value: {@code "tx:panel"} is one reference; a list of style
     * values yields each. Textures and colours are both {@code tx:} strings, so the scan is
     * purely lexical.
     */
    public static List<String> references(Object value) {
        List<String> out = new ArrayList<>();
        collect(value, out);
        return out;
    }

    private static void collect(Object value, List<String> out) {
        if (value instanceof String text) {
            if (text.startsWith("tx:")) {
                String name = text.substring("tx:".length()).trim();
                if (!name.isEmpty()) {
                    out.add(name);
                }
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                collect(entry.getValue(), out);
            }
        } else if (value instanceof List<?> list) {
            for (Object entry : list) {
                collect(entry, out);
            }
        }
    }

    /**
     * Checks every {@code tx:} reference in the document against this table.
     *
     * @return one warning per undeclared reference, with the document path
     */
    public List<String> missingReferences(UiDocument document) {
        List<String> missing = new ArrayList<>();
        if (document.theme() == null || tokens.isEmpty()) {
            // No theme loaded: nothing can be verified, so do not invent failures.
            return missing;
        }
        check(document.surfaceOptions(), "surface_options", missing);
        for (UiElement element : document.elements()) {
            check(element, "elements", missing);
        }
        return missing;
    }

    private void check(UiElement element, String path, List<String> missing) {
        String elementPath = path + "." + element.id();
        check(element.attributes(), elementPath, missing);
        for (UiElement child : element.children()) {
            check(child, elementPath + ".children", missing);
        }
    }

    private void check(Object value, String path, List<String> missing) {
        for (String reference : references(value)) {
            if (!contains(reference)) {
                String warning = "unknown theme token '" + reference + "' at " + path;
                if (!missing.contains(warning)) {
                    missing.add(warning);
                }
            }
        }
    }

    /** Colour-like names carry a hex value; {@code font.*} and {@code tex.*} say otherwise. */
    private static String kindOf(String name, Object value) {
        String key = normalise(name);
        if (key.startsWith("font.")) {
            return "font";
        }
        if (key.startsWith("tex.") || key.startsWith("texture.")) {
            return "texture";
        }
        if (value instanceof String text && text.startsWith("#")) {
            return "color";
        }
        if (value instanceof Number) {
            return "number";
        }
        return "text";
    }

    private static String normalise(String name) {
        return String.valueOf(name).trim().toLowerCase(Locale.ROOT);
    }
}
