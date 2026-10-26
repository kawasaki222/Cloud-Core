package com.cloudcore.server.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaceholderTokenTest {

    @Test
    void splitsNamespaceAndParameters() {
        assertEquals(new PlaceholderToken("player", "name"), PlaceholderToken.parse("player_name"));
        assertEquals(new PlaceholderToken("rpg", "quest_progress"),
                PlaceholderToken.parse("rpg_quest_progress"));
        assertEquals(new PlaceholderToken("server", ""), PlaceholderToken.parse("server"));
        assertEquals(new PlaceholderToken("rpg", "level"),
                PlaceholderToken.parse("  RPG_Level  "));
    }

    @Test
    void rejectsMalformedTokens() {
        assertNull(PlaceholderToken.parse(null));
        assertNull(PlaceholderToken.parse(""));
        assertNull(PlaceholderToken.parse("has space"));
        assertNull(PlaceholderToken.parse("bad-dash"));
        assertNull(PlaceholderToken.parse("a".repeat(65)));
        assertNull(PlaceholderToken.parse("_leading_underscore"));
    }

    @Test
    void validatesNamespaces() {
        assertTrue(PlaceholderToken.isValidNamespace("rpg"));
        assertTrue(PlaceholderToken.isValidNamespace("rpg2"));
        assertTrue(PlaceholderToken.isValidNamespace("a".repeat(16)));
        assertFalse(PlaceholderToken.isValidNamespace("a".repeat(17)));
        assertFalse(PlaceholderToken.isValidNamespace("RPG"));
        assertFalse(PlaceholderToken.isValidNamespace("rpg.level"));
        assertFalse(PlaceholderToken.isValidNamespace(null));
    }
}
