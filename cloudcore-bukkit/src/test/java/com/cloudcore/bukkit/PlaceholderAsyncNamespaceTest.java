package com.cloudcore.bukkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The async-safety decision only looks at token namespaces; it must never call Bukkit, so it
 * is testable without a server.
 */
class PlaceholderAsyncNamespaceTest {

    private Placeholders placeholders(List<String> whitelist) {
        Placeholders instance = new Placeholders(null, Map.of());
        instance.configureAsyncNamespaces(whitelist);
        return instance;
    }

    @Test
    void textWithoutTokensIsAlwaysSafe() {
        assertTrue(placeholders(List.of()).canExpandAsync("普通文本 without tokens"));
        assertTrue(placeholders(List.of()).canExpandAsync(null));
    }

    @Test
    void whitelistedNamespacesMayExpandOffThread() {
        assertTrue(placeholders(List.of("player")).canExpandAsync("%player_name%"));
        assertTrue(placeholders(List.of("Player", "SERVER"))
                .canExpandAsync("%player_level% and %server_online%"));
    }

    @Test
    void unknownNamespacesAreRefused() {
        assertFalse(placeholders(List.of("player")).canExpandAsync("%vault_eco_balance%"));
        assertFalse(placeholders(List.of()).canExpandAsync("%player_name%"));
    }

    @Test
    void oneRefusedTokenBlocksTheWholeText() {
        assertFalse(placeholders(List.of("player"))
                .canExpandAsync("%player_name% %vault_eco_balance%"));
    }

    @Test
    void blanksInTheConfigAreIgnored() {
        Placeholders instance = placeholders(List.of("", "  ", "player"));
        assertTrue(instance.canExpandAsync("%player_name%"));
        assertFalse(instance.canExpandAsync("%other_thing%"));
    }
}
