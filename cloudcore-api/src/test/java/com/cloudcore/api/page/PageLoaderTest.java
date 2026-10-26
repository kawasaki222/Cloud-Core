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

    private Path write(String relative, String content) throws IOException {
        Path file = pages.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
