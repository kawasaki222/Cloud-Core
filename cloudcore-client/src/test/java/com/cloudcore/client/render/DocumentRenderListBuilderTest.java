package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertEquals("local:test/logo.png", resolved.texture());
        assertEquals(32, resolved.width());
        assertEquals(24, resolved.height());
    }

    @Test
    void acceptsInlineAndPackButRejectsRawUrls() {
        UiElement inline = new UiElement("a", "image", 0, 0, "10", "10", true, true, 0,
                Map.of(), List.of(), Map.of("texture", "inline:panel"));
        UiElement url = new UiElement("b", "image", 20, 0, "10", "10", true, true, 0,
                Map.of(), List.of(), Map.of("texture", "url:https://example.com/a.png"));
        UiElement pack = new UiElement("c", "image", 40, 0, "10", "10", true, true, 0,
                Map.of(), List.of(), Map.of("texture", "pack:cloudcore:ui/frame"));
        UiDocument document = new UiDocument(1, "demo", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("Demo"), null, 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(inline, url, pack));

        List<RenderCommand> commands = DocumentRenderListBuilder.build(document, 200, 100)
                .commands();
        assertEquals(2, commands.size(), "a raw url: never reaches the client builder");
        assertEquals("inline:panel", ((RenderCommand.Image) commands.get(0)).texture());
        assertEquals("pack:cloudcore:ui/frame", ((RenderCommand.Image) commands.get(1)).texture());
    }

    @Test
    void buildsItemIconCommandFromDescriptor() {
        UiElement icon = new UiElement("coin", "item_icon", 8, 40, "16", "16",
                true, true, 0, Map.of(), List.of(),
                Map.of("item", Map.of("id", "minecraft:diamond", "count", 5),
                        "show_count", true, "scale", 1.5));
        UiDocument document = new UiDocument(1, "demo", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("Demo"), null, 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(icon));

        RenderCommand.ItemIcon command = (RenderCommand.ItemIcon)
                DocumentRenderListBuilder.build(document, 200, 100).commands().get(0);
        assertEquals("minecraft:diamond", command.itemId());
        assertEquals(5, command.count());
        assertTrue(command.showCount());
        assertEquals(1.5f, command.scale());
    }

    @Test
    void resolvesDocumentFontTokenToLocalPath() {
        String fontPath = "公共/字体/body.ttf";
        UiElement label = new UiElement("label", "label", 0, 0, null, null,
                true, true, 0, Map.of(), List.of(),
                Map.of("text", "Hi", "font", "body"));
        UiDocument document = new UiDocument(1, "fonts", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null, Map.of(),
                Map.of("body", new com.cloudcore.api.document.FontSpec(
                        "nanovg_ttf", "local", fontPath, null, 0, false)),
                List.of(label));

        RenderCommand.Text command = (RenderCommand.Text)
                DocumentRenderListBuilder.build(document, 200, 100).commands().get(0);
        assertEquals(fontPath, command.font());
    }

    @Test
    void labelWithoutFontLeavesFontNullForTheNativeDefault() {
        UiElement label = new UiElement("label", "label", 0, 0, null, null,
                true, true, 0, Map.of(), List.of(), Map.of("text", "Hi"));
        UiDocument document = new UiDocument(1, "plain", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(label));

        RenderCommand.Text command = (RenderCommand.Text)
                DocumentRenderListBuilder.build(document, 200, 100).commands().get(0);
        org.junit.jupiter.api.Assertions.assertNull(command.font());
    }

    @Test
    void scrollClipsItsChildrenAndAppliesTheOffset() {
        UiElement child = new UiElement("row", "panel", 0, 10, "100", "10",
                true, true, 0, Map.of(), List.of(),
                Map.of("style", Map.of("background", "#FF00FF00")));
        UiElement scroll = new UiElement("scroller", "scroll", 5, 5, "100", "40",
                true, true, 0, Map.of(), List.of(child),
                Map.of("scroll_y", 15));
        UiDocument document = new UiDocument(1, "scroll", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(scroll));

        List<RenderCommand> commands = DocumentRenderListBuilder.build(document, 200, 100)
                .commands();
        RenderCommand.ClipPush clip = (RenderCommand.ClipPush) commands.get(1);
        assertEquals(5, clip.x());
        assertEquals(5, clip.y());
        assertEquals(100, clip.width());
        assertEquals(40, clip.height());
        RenderCommand.FillRect childFill = (RenderCommand.FillRect) commands.get(2);
        assertEquals(0, childFill.y(), "child is scrolled up by 15px");
        assertTrue(commands.get(commands.size() - 1) instanceof RenderCommand.ClipPop);
    }

    @Test
    void imageCarriesNineSliceBorders() {
        UiElement image = new UiElement("frame", "image", 0, 0, "80", "40",
                true, true, 0, Map.of(), List.of(),
                Map.of("texture", "pack:minecraft:block/diamond_block",
                        "nine_slice", List.of(2, 2, 2, 2)));
        UiDocument document = new UiDocument(1, "nine", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(image));

        RenderCommand.Image command = (RenderCommand.Image)
                DocumentRenderListBuilder.build(document, 200, 100).commands().get(0);
        int[] borders = command.nineSlice();
        org.junit.jupiter.api.Assertions.assertNotNull(borders);
        assertEquals(2, borders[0]);
        assertEquals(2, borders[3]);
    }

    @Test
    void panelBackgroundMayBeATextureWithNineSlice() {
        UiElement panel = new UiElement("frame", "panel", 0, 0, "80", "40",
                true, true, 0, Map.of(), List.of(),
                Map.of("style", Map.of("background", "pack:cloudcore:ui/frame",
                        "nine_slice", List.of(4, 4, 4, 4))));
        UiDocument document = new UiDocument(1, "tex", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(panel));

        RenderCommand.Image image = (RenderCommand.Image)
                DocumentRenderListBuilder.build(document, 200, 100).commands().get(0);
        assertEquals("pack:cloudcore:ui/frame", image.texture());
        org.junit.jupiter.api.Assertions.assertNotNull(image.nineSlice());
    }

    @Test
    void labelTextRunsBecomeRichText() {
        UiElement label = new UiElement("rich", "label", 4, 4, null, null,
                true, true, 0, Map.of(), List.of(),
                Map.of("text", Map.of("runs", List.of(
                        Map.of("text", "a", "color", "#FFFFFFFF"),
                        Map.of("text", "b", "color", "#FFFF0000")))));
        UiDocument document = new UiDocument(1, "rich", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(label));

        RenderCommand.RichText rich = (RenderCommand.RichText)
                DocumentRenderListBuilder.build(document, 200, 100).commands().get(0);
        assertEquals(2, rich.runs().size());
        assertEquals(0xFFFF0000, rich.runs().get(1).argb());
    }

    @Test
    void hoveredButtonUsesTheHoverBackground() {
        UiElement button = new UiElement("b", "button", 0, 0, "60", "20",
                true, true, 0, Map.of(), List.of(),
                Map.of("text", "Go",
                        "style", Map.of("background", "#FF111111",
                                "hover", Map.of("background", "#FF222222"))));
        UiDocument document = new UiDocument(1, "hover", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(button));

        RenderCommand.FillRect normal = (RenderCommand.FillRect)
                DocumentRenderListBuilder.buildAt(document, 200, 100, 0, 0, id -> 0, null)
                        .commands().get(0);
        RenderCommand.FillRect hovered = (RenderCommand.FillRect)
                DocumentRenderListBuilder.buildAt(document, 200, 100, 0, 0, id -> 0, "b")
                        .commands().get(0);

        assertEquals(0xFF111111, normal.argb());
        assertEquals(0xFF222222, hovered.argb());
    }

    @Test
    void imageCarriesAnimationOptions() {
        UiElement image = new UiElement("banner", "image", 0, 0, "32", "32",
                true, true, 0, Map.of(), List.of(),
                Map.of("texture", "local:test/anim.gif", "animated", "off",
                        "loop", false, "speed", 2.0));
        UiDocument document = new UiDocument(1, "anim", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(image));

        RenderCommand.Image command = (RenderCommand.Image)
                DocumentRenderListBuilder.build(document, 200, 100).commands().get(0);
        assertFalse(command.animation().enabled());
        assertFalse(command.animation().loop());
        assertEquals(2.0f, command.animation().speed());
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
