package com.cloudcore.server.action;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Names of server-side actions. Addon actions share the namespace with the built-ins
 * ({@code message}, {@code set_state}, ...), so a name is lower-case and limited to
 * {@code [a-z0-9_.-]} with at most 64 characters — enough for {@code rpg_buy} or
 * {@code quest.accept}, and impossible to confuse with a YAML key.
 */
public final class ActionNames {
    private static final Pattern VALID = Pattern.compile("[a-z0-9_.-]{1,64}");

    private ActionNames() {
    }

    /**
     * @return the canonical (trimmed, lower-case) name
     * @throws IllegalArgumentException when the name is null or does not match the pattern
     */
    public static String normalise(String name) {
        Objects.requireNonNull(name, "name");
        String trimmed = name.trim().toLowerCase(Locale.ROOT);
        if (!VALID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid action name '" + name
                    + "': expected [a-z0-9_.-]{1,64}");
        }
        return trimmed;
    }

    /** @return true when {@link #normalise} would accept the name */
    public static boolean isValid(String name) {
        if (name == null) {
            return false;
        }
        return VALID.matcher(name.trim().toLowerCase(Locale.ROOT)).matches();
    }
}
