package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import com.cloudcore.api.document.UiDocumentValidator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentRenderListBuilderTest {

    @Test
    void buildsPanelLabelAndButtonCommands() {
        UiElement label = new UiElement("label", "label", 10, 10, null, null,
                true, true, 0, Map.of(), List.of(),
                Map.of("text", "Hello"));
        UiElement button = new UiElement("button", "button", 10, 25, "80", "20",
                true, true, 0, Map.of(), List.of(),
                Map.of("text", "Click"));
        UiElement panel = new UiElement("root", "panel", 0, 0, "fill", "fill",
                true, true, 0, Map.of(), List.of(label, button), Map.of());
        UiDocument document = new UiDocument(1, "demo", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("Demo"), null, 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(panel));
        assertTrue(UiDocumentValidator.validate(document).valid());

        RenderList list = DocumentRenderListBuilder.build(document, 200, 100);
        assertEquals(4, list.commands().size());
        assertTrue(list.commands().get(0) instanceof RenderCommand.FillRect);
        assertTrue(list.commands().get(1) instanceof RenderCommand.Text);
        assertEquals("Hello", ((RenderCommand.Text) list.commands().get(1)).text());
        assertTrue(list.commands().get(2) instanceof RenderCommand.FillRect);
        assertEquals("Click", ((RenderCommand.Text) list.commands().get(3)).text());
    }

    @Test
    void buildsLocalImageCommand() {
        UiElement image = new UiElement("logo", "image", 4, 4, "32", "24",
                true, true, 0, Map.of(), List.of(),
                Map.of("texture", "local:test/logo.png"));
        UiDocument document = new UiDocument(1, "demo", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("Demo"), null, 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(image));

        RenderCommand command = DocumentRenderListBuilder.build(document, 200, 100)
                .commands().get(0);
        RenderCommand.Image resolved = (RenderCommand.Image) command;
        assertEquals("test/logo.png", resolved.texture());
        assertEquals(32, resolved.width());
        assertEquals(24, resolved.height());
    }

    @Test
    void resolvesPercentAndFillDimensions() {
        UiElement panel = new UiElement("root", "panel", 0, 0, "50%", "fill",
                true, true, 0, Map.of(), List.of(), Map.of());
        UiDocument document = new UiDocument(1, "demo", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("Demo"), null, 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(panel));

        RenderCommand.FillRect fill = (RenderCommand.FillRect)
                DocumentRenderListBuilder.build(document, 200, 100).commands().get(0);
        assertEquals(100, fill.width());
        assertEquals(100, fill.height());
    }
}
