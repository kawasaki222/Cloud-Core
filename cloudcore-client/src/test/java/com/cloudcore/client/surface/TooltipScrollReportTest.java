package com.cloudcore.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The report decision for {@code tooltip_scroll}: the tooltip style document must opt in with
 * {@code sync_scroll}, an element must declare the event, and reports are throttled.
 */
class TooltipScrollReportTest {

    private static UiDocument document(Map<String, Object> options, Map<String, Integer> events) {
        return new UiDocument(1, "tooltip_theme", SurfaceType.TOOLTIP,
                TextRuns.plain("Tooltip"), null, 200, 100, 1.0f, null, options, Map.of(),
                List.of(new UiElement("frame", "panel", 0, 0, "200", "100", true, true, 0,
                        events, List.of(), Map.of())));
    }

    @Test
    void reportsOnlyWhenSyncScrollIsOn() {
        assertEquals(7, TooltipSurfaceAdapter.scrollActionId(
                document(Map.of("sync_scroll", true), Map.of("tooltip_scroll", 7))));
        assertEquals(0, TooltipSurfaceAdapter.scrollActionId(
                document(Map.of(), Map.of("tooltip_scroll", 7))));
        assertEquals(0, TooltipSurfaceAdapter.scrollActionId(
                document(Map.of("sync_scroll", false), Map.of("tooltip_scroll", 7))));
    }

    @Test
    void needsAnElementThatDeclaredTheEvent() {
        assertEquals(0, TooltipSurfaceAdapter.scrollActionId(
                document(Map.of("sync_scroll", true), Map.of("click", 3))));
    }

    @Test
    void throttleAllowsOneReportPerInterval() {
        assertTrue(TooltipSurfaceAdapter.shouldReport(40, 0, 1_000L, 900L));
        assertFalse(TooltipSurfaceAdapter.shouldReport(40, 40, 1_050L, 1_000L));
        assertFalse(TooltipSurfaceAdapter.shouldReport(41, 40, 1_050L, 1_000L));
        assertTrue(TooltipSurfaceAdapter.shouldReport(41, 40, 1_100L, 1_000L));
    }

    @Test
    void anUnchangedOffsetIsNeverReported() {
        assertFalse(TooltipSurfaceAdapter.shouldReport(10, 10, 5_000L, 0L));
    }
}
