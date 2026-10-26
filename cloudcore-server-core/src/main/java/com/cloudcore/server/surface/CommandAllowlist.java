package com.cloudcore.server.surface;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Gate for the page {@code command} action: disabled unless explicitly enabled and the
 * command's first word is allowlisted. Kept free of Bukkit types so the rule is unit-tested.
 */
public final class CommandAllowlist {
    private CommandAllowlist() {
    }

    /**
     * @param enabled   {@code actions.commands.enabled}
     * @param allowlist {@code actions.commands.allowlist} (verbs without a leading slash)
     * @param command   raw command from the page, with or without a leading slash
     */
    public static boolean allows(boolean enabled, List<String> allowlist, String command) {
        Objects.requireNonNull(allowlist, "allowlist");
        if (!enabled || command == null || command.isBlank()) {
            return false;
        }
        String line = command.startsWith("/") ? command.substring(1) : command;
        String verb = line.trim().split("\s+")[0].toLowerCase(Locale.ROOT);
        for (String allowed : allowlist) {
            if (allowed != null && allowed.trim().toLowerCase(Locale.ROOT).equals(verb)) {
                return true;
            }
        }
        return false;
    }
}
