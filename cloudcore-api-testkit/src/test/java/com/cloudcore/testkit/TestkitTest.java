package com.cloudcore.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.OpenOptions;
import com.cloudcore.api.OpenResult;
import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.PageBuilder;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.event.SessionOpenEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TestkitTest {

    @Test
    void fakeApiRecordsOpensAndKeepsStatePerUi() {
        FakeCloudCoreApi api = new FakeCloudCoreApi();
        UUID player = UUID.randomUUID();

        OpenResult result = api.open(player, "rpg_shop",
                OpenOptions.defaults(SurfaceType.CUSTOM_SCREEN));

        assertEquals(OpenResult.OPENED, result);
        assertEquals("rpg_shop",
                api.activeDocument(player, SurfaceType.CUSTOM_SCREEN).orElseThrow());
        assertEquals(1, api.opens().size());

        api.setState(player, "rpg_shop", "balance", 120);
        assertEquals(120, api.getState(player, "rpg_shop", "balance").orElseThrow());
        assertTrue(api.getState(UUID.randomUUID(), "rpg_shop", "balance").isEmpty(),
                "state is scoped per player");

        assertTrue(api.close(player, SurfaceType.CUSTOM_SCREEN));
        assertFalse(api.close(player, SurfaceType.CUSTOM_SCREEN));
        api.reload();
        assertEquals(1, api.reloadCount());
    }

    @Test
    void documentAssertionsWalkTheBuiltPage() {
        UiDocument document = PageBuilder
                .create("rpg_shop", SurfaceType.CUSTOM_SCREEN, 240, 120)
                .panel("root", 0, 0, 240, 120, "#CC202830")
                .label("title", 12, 10, "RPG Shop")
                .build()
                .document();

        DocumentAssert.assertValid(document);
        DocumentAssert.assertElement(document, "title", "label");
        DocumentAssert.assertText(document, "title", "RPG Shop");
        DocumentAssert.assertElementCount(document, 2);
        assertThrows(AssertionError.class,
                () -> DocumentAssert.assertElement(document, "missing", "label"));
    }

    @Test
    void invalidDocumentFailsLoudly() {
        UiDocument document = new UiDocument(1, "bad", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("Bad"), null, 0, 0, 1.0f, null, Map.of(), Map.of(), List.of());

        assertThrows(AssertionError.class, () -> DocumentAssert.assertValid(document));
    }

    @Test
    void eventCaptureCollectsAndClears() {
        EventCapture<SessionOpenEvent> capture = new EventCapture<>();
        UUID player = UUID.randomUUID();

        capture.accept(new SessionOpenEvent(player, "shop", SurfaceType.CUSTOM_SCREEN,
                "shop", 2));

        assertEquals(1, capture.size());
        assertEquals(2, capture.last().revision());
        capture.clear();
        assertThrows(AssertionError.class, capture::last);
    }
}
