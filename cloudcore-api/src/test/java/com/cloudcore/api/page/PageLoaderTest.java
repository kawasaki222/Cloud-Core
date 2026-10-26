package com.cloudcore.api.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageLoaderTest {

    @TempDir
    Path pages;

    @Test
    void loadsSingleFilePageWithRepeatExpansion() throws Exception {
        Path page = write("shop.yml", """
                schema: 1
                id: shop
                surface: custom_screen
                title: "Shop"
                size: [320, 180]
                state:
                  items:
                    - { id: sword, name: "Sword" }
                    - { id: shield, name: "Shield" }
                elements:
                  - type: panel
                    id: root
                    x: 0
                    y: 0
                    width: fill
                    height: fill
                  - type: repeat
                    id: rows
                    source: "${state.items}"
                    as: item
                    index: i
                    gap: 2
                    children:
                      - type: button
                        id: row
                        x: 8
                        y: 20
                        height: 18
                        text: "${i}: ${item.name}"
                """);

        UiDocument document = PageLoader.loadDocument(pages, page, Map.of());

        assertEquals(SurfaceType.CUSTOM_SCREEN, document.surface());
        assertEquals("shop", document.id());
        assertEquals(3, document.elements().size(), "panel plus one button per item");
        UiElement firstRow = document.elements().get(1);
        assertEquals("rows[0].row", firstRow.id());
        assertEquals("0: Sword", firstRow.attributes().get("text"));
        assertEquals(20, firstRow.y(), "first instance keeps the authored y");
        UiElement secondRow = document.elements().get(2);
        assertEquals("rows[1].row", secondRow.id());
        assertEquals("1: Shield", secondRow.attributes().get("text"));
        assertEquals(20 + 18 + 2, secondRow.y(), "instances stack by height + gap");
    }

    @Test
    void mergesIncludesDepthFirstAndLetsLaterStateWin() throws Exception {
        write("parts/common.yml", """
                state:
                  title: from-common
                  shared: common
                elements:
                  - type: label
                    id: common_label
                    x: 0
                    y: 0
                    text: "${state.title}"
                """);
        write("parts/header.yml", """
                includes:
                  - common.yml
                state:
                  title: from-header
                elements:
                  - type: label
                    id: header_label
                    x: 0
                    y: 10
                    text: header
                """);
        Path page = write("page.yml", """
                schema: 1
                id: composed
                surface: custom_screen
                title: "Composed"
                size: [200, 100]
                state:
                  title: from-page
                includes:
                  - parts/header.yml
                elements:
                  - type: label
                    id: page_label
                    x: 0
                    y: 20
                    text: "${state.title}"
                """);

        UiDocument document = PageLoader.loadDocument(pages, page, Map.of());

        List<String> ids = document.elements().stream().map(UiElement::id).toList();
        assertEquals(List.of("common_label", "header_label", "page_label"), ids);
        assertEquals("from-page",
                document.elements().get(2).attributes().get("text"),
                "page state overrides fragment state");
    }

    @Test
    void fragmentsMayNotDeclareManifestFields() throws Exception {
        write("parts/bad.yml", """
                id: nope
                elements: []
                """);
        Path page = write("page.yml", """
                schema: 1
                id: bad
                surface: custom_screen
                title: "Bad"
                size: [100, 100]
                includes:
                  - parts/bad.yml
                """);

        PageLoadException error = assertThrows(PageLoadException.class,
                () -> PageLoader.loadDocument(pages, page, Map.of()));
        assertTrue(error.getMessage().contains("must not declare 'id'"), error.getMessage());
    }

    @Test
    void includeCycleIsRejected() throws Exception {
        write("a.yml", """
                includes: [b.yml]
                elements: []
                """);
        write("b.yml", """
                includes: [a.yml]
                elements: []
                """);
        Path page = write("page.yml", """
                schema: 1
                id: cycle
                surface: custom_screen
                title: "Cycle"
                size: [100, 100]
                includes:
                  - a.yml
                """);

        PageLoadException error = assertThrows(PageLoadException.class,
                () -> PageLoader.loadDocument(pages, page, Map.of()));
        assertTrue(error.getMessage().contains("cycle"), error.getMessage());
    }

    @Test
    void repeatWithoutListSourceFails() throws Exception {
        Path page = write("bad-repeat.yml", """
                schema: 1
                id: bad_repeat
                surface: custom_screen
                title: "Bad"
                size: [100, 100]
                elements:
                  - type: repeat
                    id: rows
                    source: "${state.missing}"
                    children: []
                """);

        assertThrows(PageLoadException.class,
                () -> PageLoader.loadDocument(pages, page, Map.of()));
    }

    @Test
    void compilesActionsIntoEventIdsAndKeepsBodiesServerSide() throws Exception {
        Path page = write("actions.yml", """
                schema: 1
                id: actions
                surface: custom_screen
                title: "Actions"
                size: [200, 100]
                elements:
                  - type: button
                    id: close_button
                    x: 8
                    y: 8
                    width: 80
                    height: 20
                    text: "Close"
                    on:
                      click:
                        - action: close_ui
                """);

        PageLoader.CompiledPage compiled = PageLoader.load(pages, page, Map.of());

        UiElement button = compiled.document().elements().get(0);
        assertEquals(1, button.events().size());
        int actionId = button.events().get("click");
        assertTrue(actionId > 0);
        java.util.List<PageLoader.PageAction> actions = compiled.actions().get(actionId);
        assertEquals("close_ui", actions.get(0).type());
        assertTrue(compiled.document().elements().get(0).attributes().get("on") == null,
                "action bodies never reach the client");
    }

    @Test
    void actionIdsAreStableAcrossLoads() throws Exception {
        Path page = write("stable.yml", """
                schema: 1
                id: stable
                surface: custom_screen
                title: "Stable"
                size: [200, 100]
                elements:
                  - type: button
                    id: b
                    x: 0
                    y: 0
                    text: "B"
                    on:
                      click: [ { action: close_ui } ]
                """);

        int first = PageLoader.load(pages, page, Map.of()).document().elements().get(0)
                .events().get("click");
        int second = PageLoader.load(pages, page, Map.of()).document().elements().get(0)
                .events().get("click");

        assertEquals(first, second);
    }

    @Test
    void urlTexturesBecomeInlineRefsWithRemoteEntries() throws Exception {
        Path page = write("url.yml", """
                schema: 1
                id: url_page
                surface: custom_screen
                title: "URL"
                size: [200, 100]
                elements:
                  - type: image
                    id: hero
                    x: 0
                    y: 0
                    width: 64
                    height: 64
                    texture: "url:https://cdn.example.com/hero.png"
                """);

        PageLoader.CompiledPage compiled = PageLoader.load(pages, page, Map.of());

        Object texture = compiled.document().elements().get(0).attributes().get("texture");
        assertTrue(String.valueOf(texture).startsWith("inline:"), String.valueOf(texture));
        assertEquals(1, compiled.textures().size());
        String key = compiled.textures().get(0).key();
        assertEquals("inline:" + key, texture);
        assertEquals("https://cdn.example.com/hero.png", compiled.textures().get(0).url());
    }

    @Test
    void includeDepthIsCapped() throws Exception {
        int depth = PageLoader.MAX_INCLUDE_DEPTH + 2;
        for (int index = 0; index < depth; index++) {
            write("deep" + index + ".yml",
                    "includes: [deep" + (index + 1) + ".yml]\nelements: []\n");
        }
        write("deep" + depth + ".yml", "elements: []\n");
        Path page = write("deep-page.yml", """
                schema: 1
                id: deep
                surface: custom_screen
                title: "Deep"
                size: [100, 100]
                includes: [deep0.yml]
                """);

        PageLoadException error = assertThrows(PageLoadException.class,
                () -> PageLoader.loadDocument(pages, page, Map.of()));
        assertTrue(error.getMessage().contains("depth"), error.getMessage());
    }

    @Test
    void oversizedFragmentIsRejected() throws Exception {
        StringBuilder filler = new StringBuilder("elements: []\n#");
        while (filler.length() < PageLoader.MAX_FILE_BYTES + 128) {
            filler.append("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n#");
        }
        write("big.yml", filler.toString());
        Path page = write("big-page.yml", """
                schema: 1
                id: big
                surface: custom_screen
                title: "Big"
                size: [100, 100]
                includes: [big.yml]
                """);

        PageLoadException error = assertThrows(PageLoadException.class,
                () -> PageLoader.loadDocument(pages, page, Map.of()));
        assertTrue(error.getMessage().contains("exceeds"), error.getMessage());
    }

    @Test
    void malformedYamlNamesTheFile() throws Exception {
        Path page = write("broken.yml", "schema: 1\nid: [unclosed\n");

        PageLoadException error = assertThrows(PageLoadException.class,
                () -> PageLoader.loadDocument(pages, page, Map.of()));
        assertTrue(error.getMessage().contains("broken.yml"), error.getMessage());
    }

    @Test
    void missingTemplateVariableBecomesEmpty() throws Exception {
        Path page = write("template.yml", """
                schema: 1
                id: template
                surface: custom_screen
                title: "T"
                size: [100, 100]
                elements:
                  - type: label
                    id: t
                    x: 0
                    y: 0
                    text: "value=[${state.missing}]"
                """);

        UiDocument document = PageLoader.loadDocument(pages, page, Map.of());

        assertEquals("value=[]", document.elements().get(0).attributes().get("text"));
    }

    @Test
    void nestedRepeatComposesInstanceIds() throws Exception {
        Path page = write("nested.yml", """
                schema: 1
                id: nested
                surface: custom_screen
                title: "Nested"
                size: [200, 100]
                state:
                  rows:
                    - { cells: ["a", "b"] }
                elements:
                  - type: repeat
                    id: rows
                    source: "${state.rows}"
                    as: row
                    children:
                      - type: repeat
                        id: cells
                        source: "${row.cells}"
                        as: cell
                        children:
                          - type: label
                            id: cell_label
                            x: 0
                            y: 0
                            text: "${cell}"
                """);

        UiDocument document = PageLoader.loadDocument(pages, page, Map.of());

        java.util.List<String> ids = document.elements().stream()
                .map(UiElement::id).toList();
        assertEquals(java.util.List.of("rows[0].cells[0].cell_label",
                "rows[0].cells[1].cell_label"), ids);
        assertEquals("b", document.elements().get(1).attributes().get("text"));
    }

    @Test
    void elementCountIsCapped() throws Exception {
        Path page = write("many.yml", manyLabels(PageLoader.MAX_ELEMENTS + 20));

        PageLoadException error = assertThrows(PageLoadException.class,
                () -> PageLoader.loadDocument(pages, page, Map.of()));
        assertTrue(error.getMessage().contains("Element count"), error.getMessage());
    }

    @Test
    void pageAtTheElementLimitStillCompiles() throws Exception {
        Path page = write("full.yml", manyLabels(PageLoader.MAX_ELEMENTS));

        UiDocument document = PageLoader.loadDocument(pages, page, Map.of());

        assertEquals(PageLoader.MAX_ELEMENTS, document.elements().size());
    }

    @Test
    void runtimePlaceholdersSurviveActionCompilation() throws Exception {
        Path page = write("runtime.yml", """
                schema: 1
                id: runtime
                surface: custom_screen
                title: "Runtime"
                size: [200, 100]
                state:
                  notify: false
                elements:
                  - type: checkbox
                    id: toggle
                    x: 0
                    y: 0
                    text: "t"
                    checked: "${state.notify}"
                    on:
                      change:
                        - action: set_state
                          key: notify
                          value: "${checked}"
                """);

        PageLoader.CompiledPage compiled = PageLoader.load(pages, page, Map.of());

        UiElement checkbox = compiled.document().elements().get(0);
        assertEquals("false", String.valueOf(checkbox.attributes().get("checked")));
        int actionId = checkbox.events().get("change");
        assertEquals("${checked}", compiled.actions().get(actionId).get(0).params().get("value"));
    }

    @Test
    void callerStateOverridesDocumentDefaults() throws Exception {
        Path page = write("override.yml", """
                schema: 1
                id: override
                surface: custom_screen
                title: "Override"
                size: [100, 100]
                state:
                  notify: false
                elements:
                  - type: checkbox
                    id: toggle
                    x: 0
                    y: 0
                    text: "t"
                    checked: "${state.notify}"
                """);

        UiDocument document = PageLoader.loadDocument(pages, page, Map.of("notify", true));

        assertEquals("true", String.valueOf(document.elements().get(0).attributes()
                .get("checked")));
    }

    @Test
    void attributesKeepTheTypeOfAWholePlaceholder() throws Exception {
        Path page = write("typed.yml", """
                schema: 1
                id: typed
                surface: custom_screen
                title: "Typed"
                size: [100, 100]
                state:
                  volume: 0.4
                  notify: true
                elements:
                  - type: slider
                    id: volume
                    x: 0
                    y: 0
                    width: 100
                    height: 6
                    value: "${state.volume}"
                  - type: checkbox
                    id: toggle
                    x: 0
                    y: 10
                    checked: "${state.notify}"
                  - type: label
                    id: missing
                    x: 0
                    y: 20
                    text: "${state.nope}"
                """);

        UiDocument document = PageLoader.loadDocument(pages, page, Map.of());

        assertEquals(0.4, document.elements().get(0).attributes().get("value"));
        assertEquals(true, document.elements().get(1).attributes().get("checked"));
        assertEquals("", document.elements().get(2).attributes().get("text"),
                "an unresolved placeholder must not leak into the document");
    }

    @Test
    void nestedTemplatesResolveInsideOut() throws Exception {
        Path page = write("nested.yml", """
                schema: 1
                id: nested
                surface: custom_screen
                title: "Nested"
                size: [200, 100]
                state:
                  balance: 300
                  entries:
                    - { name: "Iron", price: 120 }
                elements:
                  - type: repeat
                    id: rows
                    source: "${state.entries}"
                    as: entry
                    children:
                      - type: button
                        id: buy
                        x: 0
                        y: 0
                        width: 80
                        height: 18
                        text: "buy"
                        on:
                          click:
                            - action: set_state
                              key: balance
                              value: "${state.balance - ${entry.price}}"
                            - action: message
                              value: "bought ${entry.name} for ${entry.price}"
                """);

        PageLoader.CompiledPage compiled = PageLoader.load(pages, page, Map.of());
        UiElement row = compiled.document().elements().get(0);
        int actionId = row.events().get("click");
        List<PageLoader.PageAction> actions = compiled.actions().get(actionId);

        assertEquals("${state.balance - 120}", actions.get(0).params().get("value"),
                "the inner price is resolved, the outer expression is left for the runtime");
        assertEquals("bought Iron for 120", actions.get(1).params().get("value"));
    }

    @Test
    void attributesAndLabelsAcceptExpressions() throws Exception {
        Path page = write("expr.yml", """
                schema: 1
                id: expr
                surface: custom_screen
                title: "Expr"
                size: [200, 100]
                state:
                  balance: 300
                  clicks: 2
                elements:
                  - type: progress
                    id: bar
                    x: 0
                    y: 0
                    width: 100
                    height: 8
                    value: "${state.balance / 300}"
                  - type: label
                    id: text
                    x: 0
                    y: 20
                    text: "已点击 ${state.clicks + 1} 次"
                """);

        UiDocument document = PageLoader.loadDocument(pages, page, Map.of());

        assertEquals(1.0, document.elements().get(0).attributes().get("value"));
        assertEquals("已点击 3 次", document.elements().get(1).attributes().get("text"));
    }

    private static String manyLabels(int count) {
        StringBuilder yaml = new StringBuilder("schema: 1\nid: many\n"
                + "surface: custom_screen\ntitle: \"Many\"\nsize: [200, 100]\nelements:\n");
        for (int index = 0; index < count; index++) {
            yaml.append("  - type: label\n    id: l").append(index)
                    .append("\n    x: 0\n    y: 0\n    text: t\n");
        }
        return yaml.toString();
    }


    private Path write(String relative, String content) throws IOException {
        Path file = pages.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
