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
    void textFieldClearButtonDrawsItsTextureInTheRightPadding() {
        UiElement field = new UiElement("search", "text_field", 10, 10, "60", "20",
                true, true, 0, Map.of(), List.of(),
                Map.of("style", Map.of("background", "#FF101010",
                        "clear_button", "local:ui/clear.png")));
        UiDocument document = new UiDocument(1, "demo", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("Demo"), null, 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(field));

        List<RenderCommand> commands = DocumentRenderListBuilder.buildAt(document, 200, 100,
                0, 0).commands();

        RenderCommand.Image clear = commands.stream()
                .filter(RenderCommand.Image.class::isInstance)
                .map(RenderCommand.Image.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("local:ui/clear.png", clear.texture());
        assertEquals(52, clear.x());
        assertEquals(13, clear.y());
        assertEquals(14, clear.width());
        assertEquals(14, clear.height());
    }

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
    void rowAdvancesChildrenHorizontally() {
        UiElement first = new UiElement("a", "panel", 0, 0, "40", "10",
                true, true, 0, Map.of(), List.of(), Map.of());
        UiElement second = new UiElement("b", "panel", 0, 0, "40", "10",
                true, true, 0, Map.of(), List.of(), Map.of());
        UiElement row = new UiElement("row", "row", 10, 10, "100", "10",
                true, true, 0, Map.of(), List.of(first, second), Map.of("gap", 5));
        UiDocument document = new UiDocument(1, "row", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(row));

        java.util.List<RenderCommand> commands =
                DocumentRenderListBuilder.build(document, 200, 100).commands();
        assertEquals(10, ((RenderCommand.FillRect) commands.get(0)).x());
        assertEquals(55, ((RenderCommand.FillRect) commands.get(1)).x());
    }

    @Test
    void columnAdvancesChildrenVertically() {
        UiElement first = new UiElement("a", "panel", 0, 0, "10", "10",
                true, true, 0, Map.of(), List.of(), Map.of());
        UiElement second = new UiElement("b", "panel", 0, 0, "10", "10",
                true, true, 0, Map.of(), List.of(), Map.of());
        UiElement column = new UiElement("col", "column", 5, 5, "10", "40",
                true, true, 0, Map.of(), List.of(first, second), Map.of("gap", 4));
        UiDocument document = new UiDocument(1, "column", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(column));

        java.util.List<RenderCommand> commands =
                DocumentRenderListBuilder.build(document, 200, 100).commands();
        assertEquals(5, ((RenderCommand.FillRect) commands.get(0)).y());
        assertEquals(19, ((RenderCommand.FillRect) commands.get(1)).y());
    }

    @Test
    void gridWrapsEveryColumnsChildren() {
        UiElement child = new UiElement("c", "panel", 0, 0, "10", "10",
                true, true, 0, Map.of(), List.of(), Map.of());
        UiElement first = new UiElement("a", "panel", 0, 0, "10", "10",
                true, true, 0, Map.of(), List.of(), Map.of());
        UiElement second = new UiElement("b", "panel", 0, 0, "10", "10",
                true, true, 0, Map.of(), List.of(), Map.of());
        UiElement grid = new UiElement("g", "grid", 0, 0, "100", "100",
                true, true, 0, Map.of(), List.of(first, second, child),
                Map.of("columns", 2, "cell", List.of(20, 12), "gap", 2));
        UiDocument document = new UiDocument(1, "grid", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(grid));

        java.util.List<RenderCommand> commands =
                DocumentRenderListBuilder.build(document, 200, 100).commands();
        assertEquals(0, ((RenderCommand.FillRect) commands.get(0)).x());
        assertEquals(22, ((RenderCommand.FillRect) commands.get(1)).x());
        assertEquals(0, ((RenderCommand.FillRect) commands.get(2)).x());
        assertEquals(14, ((RenderCommand.FillRect) commands.get(2)).y());
    }

    @Test
    void sliderFillsItsTrackFromNumericAndTextValues() {
        List<RenderCommand> numeric = sliderCommands(0.4);
        List<RenderCommand> text = sliderCommands("0.4");
        List<RenderCommand> empty = sliderCommands(0.0);

        assertEquals(140, ((RenderCommand.FillRect) numeric.get(0)).width(), "track width");
        assertEquals(56, ((RenderCommand.FillRect) numeric.get(1)).width(), "40% of 140px");
        assertEquals(56, ((RenderCommand.FillRect) text.get(1)).width(),
                "a quoted number still fills 40% of 140px");
        assertEquals(52, ((RenderCommand.FillRect) text.get(2)).x(),
                "knob is centred on the fill end (56 - knob/2)");
        assertEquals(2, empty.size(), "value 0 draws only the track and the knob");
    }

    private static List<RenderCommand> sliderCommands(Object value) {
        UiElement slider = new UiElement("volume", "slider", 0, 0, "140", "6",
                true, true, 0, Map.of(), List.of(),
                new java.util.HashMap<>(Map.of("value", value)));
        UiDocument document = new UiDocument(1, "slider", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(slider));
        return DocumentRenderListBuilder.build(document, 200, 100).commands();
    }

    @Test
    void textFieldDrawsItsConfiguredBorderAndTheInvalidOne() {
        UiElement normal = new UiElement("a", "text_field", 10, 10, "100", "20",
                true, true, 0, Map.of(), List.of(),
                Map.of("placeholder", "type",
                        "style", Map.of("background", "#FF202020",
                                "border", Map.of("normal", "#FF3A3A3A", "invalid", "#FFFF5555"))));
        UiElement invalid = new UiElement("b", "text_field", 10, 40, "100", "20",
                true, true, 0, Map.of(), List.of(),
                Map.of("invalid", true,
                        "style", Map.of("border", Map.of("normal", "#FF3A3A3A",
                                "invalid", "#FFFF5555"))));
        UiDocument document = new UiDocument(1, "fields", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(normal, invalid));

        List<RenderCommand> commands = DocumentRenderListBuilder.build(document, 200, 100)
                .commands();
        // Each field is a background plus a four-piece 1px frame.
        assertTrue(commands.get(0) instanceof RenderCommand.FillRect);
        RenderCommand.FillRect normalTop = (RenderCommand.FillRect) commands.get(1);
        RenderCommand.FillRect invalidTop = (RenderCommand.FillRect) commands.get(6);
        assertEquals(0xFF3A3A3A, normalTop.argb());
        assertEquals(10, normalTop.x());
        assertEquals(1, normalTop.height());
        assertEquals(0xFFFF5555, invalidTop.argb(), "invalid: true wins over the normal border");
        assertEquals(40, invalidTop.y());
    }

    @Test
    void iconButtonDrawsATextureOrItemIconCentred() {
        UiElement texture = new UiElement("close", "icon_button", 0, 0, "24", "24",
                true, true, 0, Map.of(), List.of(),
                Map.of("icon", "pack:cloudcore:ui/close"));
        UiElement item = new UiElement("gem", "icon_button", 30, 0, "20", "20",
                true, true, 0, Map.of(), List.of(),
                Map.of("icon", Map.of("item", "minecraft:diamond", "count", 2)));
        UiDocument document = new UiDocument(1, "icons", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(texture, item));

        List<RenderCommand> commands = DocumentRenderListBuilder.build(document, 200, 100)
                .commands();
        RenderCommand.Image image = (RenderCommand.Image) commands.get(1);
        assertEquals("pack:cloudcore:ui/close", image.texture());
        assertEquals(2, image.x(), "icon is inset by 2px");
        assertEquals(20, image.width(), "icon fills the button minus the inset");
        RenderCommand.ItemIcon icon = (RenderCommand.ItemIcon) commands.get(3);
        assertEquals("minecraft:diamond", icon.itemId());
        assertEquals(2, icon.count());
        assertEquals(1.0f, icon.scale(), 0.001f, "a 20x20 button shows the item at 16px");
    }

    @Test
    void buttonWithIconShiftsItsTextRight() {
        UiElement button = new UiElement("go", "button", 0, 0, "80", "20",
                true, true, 0, Map.of(), List.of(),
                Map.of("text", "Go",
                        "icon", Map.of("item", "minecraft:emerald")));
        UiDocument document = new UiDocument(1, "buttons", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(button));

        List<RenderCommand> commands = DocumentRenderListBuilder.build(document, 200, 100)
                .commands();
        assertEquals("minecraft:emerald", ((RenderCommand.ItemIcon) commands.get(1)).itemId());
        RenderCommand.Text text = (RenderCommand.Text) commands.get(2);
        assertEquals("Go", text.text());
        assertEquals(21, text.x(), "text starts after the icon");
    }

    @Test
    void progressFillsFromTheRequestedDirection() {
        UiElement left = progress("a", 0, 0, 100, 10, 0.4, null);
        UiElement right = progress("b", 0, 20, 100, 10, 0.4, "right");
        UiElement up = progress("c", 0, 40, 10, 100, 0.5, "up");
        UiElement down = progress("d", 20, 40, 10, 100, 0.5, "down");
        UiDocument document = new UiDocument(1, "bars", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 200, 1.0f, null,
                Map.of(), Map.of(), List.of(left, right, up, down));

        List<RenderCommand> commands = DocumentRenderListBuilder.build(document, 200, 200)
                .commands();
        assertEquals(40, ((RenderCommand.FillRect) commands.get(1)).width(), "left: 40% wide");
        assertEquals(0, ((RenderCommand.FillRect) commands.get(1)).x());
        RenderCommand.FillRect rightFill = (RenderCommand.FillRect) commands.get(3);
        assertEquals(60, rightFill.x(), "right: fill starts 40% from the right");
        assertEquals(40, rightFill.width());
        RenderCommand.FillRect upFill = (RenderCommand.FillRect) commands.get(5);
        assertEquals(90, upFill.y(), "up: fill hangs from the bottom");
        assertEquals(50, upFill.height());
        RenderCommand.FillRect downFill = (RenderCommand.FillRect) commands.get(7);
        assertEquals(40, downFill.y(), "down: fill starts at the top");
        assertEquals(50, downFill.height());
    }

    @Test
    void progressTextureFillIsClippedInsteadOfSquashed() {
        UiElement bar = new UiElement("hp", "progress", 0, 0, "100", "10",
                true, true, 0, Map.of(), List.of(),
                Map.of("value", 0.5,
                        "texture_bg", "pack:minecraft:block/diamond_block",
                        "texture_fill", "pack:minecraft:block/emerald_block"));
        UiDocument document = new UiDocument(1, "bar", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(bar));

        List<RenderCommand> commands = DocumentRenderListBuilder.build(document, 200, 100)
                .commands();
        assertEquals("pack:minecraft:block/diamond_block",
                ((RenderCommand.Image) commands.get(0)).texture());
        RenderCommand.ClipPush clip = (RenderCommand.ClipPush) commands.get(1);
        assertEquals(50, clip.width(), "half the bar is visible");
        RenderCommand.Image fill = (RenderCommand.Image) commands.get(2);
        assertEquals("pack:minecraft:block/emerald_block", fill.texture());
        assertEquals(100, fill.width(), "the texture keeps its full size and is clipped");
        assertTrue(commands.get(3) instanceof RenderCommand.ClipPop);
    }

    private static UiElement progress(String id, int x, int y, int width, int height,
            double value, String direction) {
        java.util.HashMap<String, Object> attributes = new java.util.HashMap<>();
        attributes.put("value", value);
        if (direction != null) {
            attributes.put("direction", direction);
        }
        return new UiElement(id, "progress", x, y, String.valueOf(width),
                String.valueOf(height), true, true, 0, Map.of(), List.of(), attributes);
    }

    @Test
    void disabledButtonIsDimmedAndIgnoredByHover() {
        Map<String, Object> style = Map.of("background", "#FF111111",
                "hover", Map.of("background", "#FF222222"));
        UiElement enabled = new UiElement("a", "button", 0, 0, "60", "20",
                true, true, 0, Map.of(), List.of(), Map.of("style", style, "text", "A"));
        UiElement disabled = new UiElement("b", "button", 0, 30, "60", "20",
                true, false, 0, Map.of(), List.of(), Map.of("style", style, "text", "B"));
        UiDocument document = new UiDocument(1, "states", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(enabled, disabled));

        List<RenderCommand> commands = DocumentRenderListBuilder
                .buildAt(document, 200, 100, 0, 0, id -> 0, "b").commands();

        assertEquals(0xFF111111, ((RenderCommand.FillRect) commands.get(0)).argb(),
                "the enabled button is not hovered");
        assertEquals(0x7F111111, ((RenderCommand.FillRect) commands.get(2)).argb(),
                "a disabled button is dimmed and never uses its hover style");
    }

    @Test
    void pressedStyleWinsOverHoverAndFallsBackToIt() {
        // Only one element can be hovered per build, so each case gets its own document.
        UiElement declaring = new UiElement("a", "button", 0, 0, "60", "20",
                true, true, 0, Map.of(), List.of(), Map.of("text", "A",
                        "style", Map.of("background", "#FF111111",
                                "hover", Map.of("background", "#FF222222"),
                                "pressed", Map.of("background", "#FF333333"))));
        UiElement silent = new UiElement("a", "button", 0, 0, "60", "20",
                true, true, 0, Map.of(), List.of(), Map.of("text", "A",
                        "style", Map.of("background", "#FF111111",
                                "hover", Map.of("background", "#FF222222"),
                                "pressed", Map.of("text_color", "#FF000000"))));

        assertEquals(0xFF333333, firstFill(declaring, "a", "a"),
                "a pressed button uses style.pressed");
        assertEquals(0xFF222222, firstFill(silent, "a", "a"),
                "a pressed button without a pressed background still shows its hover one");
        assertEquals(0xFF111111, firstFill(silent, null, null),
                "without hover or press the resting background is used");
    }

    @Test
    void pressedIsDroppedWhenTheCursorLeavesTheElement() {
        UiElement button = new UiElement("a", "button", 0, 0, "60", "20",
                true, true, 0, Map.of(), List.of(), Map.of("text", "A",
                        "style", Map.of("background", "#FF111111",
                                "pressed", Map.of("background", "#FF333333"))));

        // Held on 'a' but hovering nothing: dragging off must release the pressed look.
        assertEquals(0xFF111111, firstFill(button, null, "a"));
    }

    /** Background of the single button in a one-element document. */
    private static int firstFill(UiElement button, String hoveredId, String pressedId) {
        UiDocument document = new UiDocument(1, "states", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(button));
        return ((RenderCommand.FillRect) DocumentRenderListBuilder
                .buildAt(document, 200, 100, 0, 0, id -> 0, hoveredId, pressedId)
                .commands().get(0)).argb();
    }

    @Test
    void segmentedProgressFillsOneCellAtATime() {
        UiElement bar = new UiElement("hp", "progress", 0, 0, "100", "10",
                true, true, 0, Map.of(), List.of(),
                Map.of("value", 0.35, "segments", 10, "segment_gap", 0,
                        "style", Map.of("fill_color", "#FF00FF00")));
        UiDocument document = new UiDocument(1, "seg", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(bar));

        // 0.35 of 10 cells: 3 whole cells plus a partly filled fourth.
        List<RenderCommand.FillRect> fills = DocumentRenderListBuilder
                .build(document, 200, 100).commands().stream()
                .filter(RenderCommand.FillRect.class::isInstance)
                .map(RenderCommand.FillRect.class::cast)
                .filter(fill -> fill.argb() == 0xFF00FF00)
                .toList();

        assertEquals(4, fills.size(), "three full cells and one partially filled");
        assertEquals(10, fills.get(0).width());
        assertEquals(5, fills.get(3).width(), "the growing cell is the last one");
        assertEquals(30, fills.get(3).x());
    }

    @Test
    void roundedProgressDrawsCornersRowByRowAndClampsTheRadius() {
        // Tall enough for real caps: 20px with a 6px radius leaves a straight middle section.
        UiElement rounded = new UiElement("hp", "progress", 0, 0, "100", "20",
                true, true, 0, Map.of(), List.of(),
                Map.of("value", 1.0, "radius", 6,
                        "style", Map.of("fill_color", "#FF00FF00")));
        // A radius larger than the bar is clamped to half its shorter side, so it stays a bar.
        UiElement clamped = new UiElement("hp", "progress", 0, 0, "100", "8",
                true, true, 0, Map.of(), List.of(),
                Map.of("value", 1.0, "radius", 64,
                        "style", Map.of("fill_color", "#FF00FF00")));

        List<RenderCommand.FillRect> rows = fillRects(rounded, 0xFF00FF00);
        assertEquals(20, rows.size(), "one rect per pixel row");
        assertEquals(1, rows.get(0).height());
        assertTrue(rows.get(0).x() > 0, "the top row is inset by the cap");
        assertTrue(rows.get(0).width() < 100);
        assertEquals(0, rows.get(10).x(), "the middle of the bar is full width");
        assertEquals(100, rows.get(10).width());
        for (RenderCommand.FillRect row : rows) {
            assertTrue(row.x() >= 0 && row.x() + row.width() <= 100,
                    "every rounded row stays within the bar");
        }

        List<RenderCommand.FillRect> clampedRows = fillRects(clamped, 0xFF00FF00);
        assertEquals(1, clampedRows.size(), "a fully rounded small bar is one rect");
        assertEquals(8, clampedRows.get(0).height());
        assertEquals(100, clampedRows.get(0).width());
    }

    /** Fill rects of the given colour from a one-progress-bar document. */
    private static List<RenderCommand.FillRect> fillRects(UiElement bar, int argb) {
        UiDocument document = new UiDocument(1, "round", SurfaceType.CUSTOM_SCREEN,
                TextRuns.plain("t"), "default", 200, 100, 1.0f, null,
                Map.of(), Map.of(), List.of(bar));
        return DocumentRenderListBuilder.build(document, 200, 100).commands().stream()
                .filter(RenderCommand.FillRect.class::isInstance)
                .map(RenderCommand.FillRect.class::cast)
                .filter(fill -> fill.argb() == argb)
                .toList();
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
