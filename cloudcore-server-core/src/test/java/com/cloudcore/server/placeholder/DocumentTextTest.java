package com.cloudcore.server.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.TextRun;
import com.cloudcore.api.document.TextRuns;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class DocumentTextTest {
    private static final UnaryOperator<String> EXPANDER =
            text -> text.replace("%player_name%", "Ada").replace("%level%", "7");

    @Test
    void expandsLabelsButtonsAndPlaceholders() {
        UiElement label = element("a", "label", Map.of("text", "Hi %player_name%"));
        UiElement button = element("b", "button", Map.of("text", "Lv %level%", "color", "#FF0000"));
        UiElement field = element("c", "text_field",
                Map.of("placeholder", "%player_name% 的名字", "max_length", 16));

        UiDocument expanded = DocumentText.expand(document(List.of(label, button, field)),
                EXPANDER);

        assertEquals("Hi Ada", expanded.elements().get(0).attributes().get("text"));
        assertEquals("Lv 7", expanded.elements().get(1).attributes().get("text"));
        assertEquals("#FF0000", expanded.elements().get(1).attributes().get("color"),
                "colours are not text and stay untouched");
        assertEquals("Ada 的名字", expanded.elements().get(2).attributes().get("placeholder"));
        assertEquals(16, expanded.elements().get(2).attributes().get("max_length"));
    }

    @Test
    void expandsRichTextRunsAndTheTitle() {
        Map<String, Object> runs = Map.of("runs", List.of(
                Map.of("text", "%player_name%", "color", "#FFFFFF"),
                Map.of("text", " 等级 %level%", "color", "#FFD479")));
        UiElement label = element("rich", "label", Map.of("text", runs));
        UiDocument document = new UiDocument(1, "rich", SurfaceType.CUSTOM_SCREEN,
                new TextRuns(List.of(new TextRun("t %level%", "#FFFFFF", false, false, false,
                        false, false, null))),
                "default", 200, 100, 1.0f, null, Map.of(), Map.of(), List.of(label));

        UiDocument expanded = DocumentText.expand(document, EXPANDER);

        @SuppressWarnings("unchecked")
        Map<String, Object> expandedText = (Map<String, Object>) expanded.elements().get(0)
                .attributes().get("text");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> expandedRuns = (List<Map<String, Object>>) expandedText.get("runs");
        assertEquals("Ada", expandedRuns.get(0).get("text"));
        assertEquals("#FFFFFF", expandedRuns.get(0).get("color"));
        assertEquals(" 等级 7", expandedRuns.get(1).get("text"));
        assertEquals("t 7", expanded.title().runs().get(0).text());
    }

    @Test
    void leavesUnknownTokensAndNonTextAttributesAlone() {
        UiElement image = element("img", "image",
                Map.of("texture", "pack:cloudcore:%player_name%"));
        UiElement label = element("l", "label", Map.of("text", "who is %unknown%?"));

        UiDocument expanded = DocumentText.expand(document(List.of(image, label)), EXPANDER);

        assertEquals("pack:cloudcore:%player_name%",
                expanded.elements().get(0).attributes().get("texture"));
        assertEquals("who is %unknown%?", expanded.elements().get(1).attributes().get("text"));
    }

    @Test
    void keepsEventsChildrenAndGeometry() {
        UiElement child = element("child", "label", Map.of("text", "%player_name%"));
        UiElement parent = new UiElement("parent", "panel", 3, 4, "80", "20", true, true, 2,
                Map.of("click", 7), List.of(child), Map.of());
        UiDocument document = document(List.of(parent));

        UiElement expandedParent = DocumentText.expand(document, EXPANDER).elements().get(0);

        assertEquals(3, expandedParent.x());
        assertEquals("80", expandedParent.width());
        assertEquals(7, expandedParent.events().get("click"));
        assertEquals("Ada", expandedParent.children().get(0).attributes().get("text"));
        assertEquals(document.surfaceOptions(), DocumentText.expand(document, EXPANDER)
                .surfaceOptions());
    }

    private static UiElement element(String id, String type, Map<String, Object> attributes) {
        return new UiElement(id, type, 0, 0, null, null, true, true, 0,
                Map.of(), List.of(), attributes);
    }

    private static UiDocument document(List<UiElement> elements) {
        return new UiDocument(1, "demo", SurfaceType.CUSTOM_SCREEN, TextRuns.plain("Demo"),
                "default", 200, 100, 1.0f, null, Map.of(), Map.of(), elements);
    }
}
