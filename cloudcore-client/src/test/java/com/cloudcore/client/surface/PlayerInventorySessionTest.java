package com.cloudcore.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the persistence rules for the player-inventory skin and the screen pass-through that
 * protects every other screen from the mixin hook.
 */
class PlayerInventorySessionTest {

    @Test
    void persistentDocumentSurvivesRepeatedOpens() {
        PlayerInventorySession session = new PlayerInventorySession();
        session.open(document("skin"), true);

        assertEquals("skin", session.documentForNextOpen().id());
        assertEquals("skin", session.documentForNextOpen().id());
        assertTrue(session.active());
    }

    @Test
    void oneShotBindingIsConsumedAfterTheNextOpen() {
        PlayerInventorySession session = new PlayerInventorySession();
        session.open(document("one-shot"), false);

        assertEquals("one-shot", session.documentForNextOpen().id());
        assertNull(session.documentForNextOpen());
        assertFalse(session.active());
    }

    @Test
    void closeOnlyClearsTheMatchingDocument() {
        PlayerInventorySession session = new PlayerInventorySession();
        session.open(document("skin"), true);

        session.close("other");
        assertTrue(session.active());

        session.close("skin");
        assertFalse(session.active());
        assertNull(session.documentForNextOpen());
    }

    @Test
    void clearDropsDocumentAndBinding() {
        PlayerInventorySession session = new PlayerInventorySession();
        session.open(document("skin"), true);

        session.clear();

        assertFalse(session.active());
        assertFalse(session.persistent());
        assertNull(session.activeUiId());
        assertNull(session.documentForNextOpen());
    }

    @Test
    void nullScreenIsPassedThrough() {
        // Minecraft#setScreen(null) closes the current screen; the mixin must not wrap it.
        assertNull(PlayerInventoryAdapter.replace(null));
    }

    private static UiDocument document(String id) {
        return new UiDocument(1, id, SurfaceType.PLAYER_INVENTORY, TextRuns.plain("title"),
                "default", 176, 166, 1.0f, null, Map.of(), Map.of(), List.of());
    }
}
