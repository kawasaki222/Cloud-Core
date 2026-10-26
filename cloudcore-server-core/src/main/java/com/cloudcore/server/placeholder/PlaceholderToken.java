package com.cloudcore.server.placeholder;

import java.util.regex.Pattern;

/**
 * One {@code %namespace_params%} token. The namespace is the part before the first underscore
 * (or the whole token when there is none), which is what an addon registers a resolver under;
 * the rest is handed to the resolver unchanged.
 *
 * <p>Pure parsing: no Bukkit types, so the rules are unit-tested and the Bukkit side only
 * looks the namespace up.
 */
public record PlaceholderToken(String namespace, String params) {
    private static final Pattern INNER = Pattern.compile("[a-z0-9_]{1,64}");
    public static final int MAX_NAMESPACE = 16;

    /** @return the parsed token, or {@code null} when it is not a well-formed placeholder */
    public static PlaceholderToken parse(String inner) {
        if (inner == null) {
            return null;
        }
        String normalised = inner.trim().toLowerCase(java.util.Locale.ROOT);
        if (!INNER.matcher(normalised).matches()) {
            return null;
        }
        int split = normalised.indexOf('_');
        String namespace = split < 0 ? normalised : normalised.substring(0, split);
        String params = split < 0 ? "" : normalised.substring(split + 1);
        if (namespace.isEmpty() || namespace.length() > MAX_NAMESPACE) {
            return null;
        }
        return new PlaceholderToken(namespace, params);
    }

    /** @return true when {@code namespace} may be registered by an addon */
    public static boolean isValidNamespace(String namespace) {
        return namespace != null && namespace.matches("[a-z0-9_]{1,16}");
    }
}
