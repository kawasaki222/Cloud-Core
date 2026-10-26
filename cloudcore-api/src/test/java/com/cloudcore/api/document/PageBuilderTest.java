package com.cloudcore.api.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.page.PageLoader.CompiledPage;
import com.cloudcore.api.page.PageLoader.PageAction;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PageBuilderTest {

    @Test
    void buildsAValidatedDocumentWithElements() {
        CompiledPage page = PageBuilder.create("shop", SurfaceType.CUSTOM_SCREEN, 200, 100)
                .theme("dark")
                .panel("root", 0, 0, 200, 100, "#CC202830")
                .label("title", 10, 8, "Shop", "#FFFFFFFF")
                .button("buy", 10, 30, 80, 18, "Buy", "rpg_buy")
                .itemIcon("icon", 120, 30, "minecraft:diamond", 3)
                .build();

        UiDocument document = page.document();
        assertEquals("shop", document.id());
        assertEquals(SurfaceType.CUSTOM_SCREEN, document.surface());
        assertEquals("dark", document.theme());
        assertEquals(4, document.elements().size());
        assertEquals("Buy", document.elements().get(2).attributes().get("text"));
        assertTrue(UiDocumentValidator.validate(document).valid());
        assertEquals("#FFFFFFFF", document.elements().get(1).attributes().get("color"));
    }

    @Test
    void bindsActionsAndKeepsTheirParameters() {
        Map<String, Object> params = Map.of("item", "minecraft:diamond");
        CompiledPage page = PageBuilder.create("p", SurfaceType.CUSTOM_SCREEN, 100, 100)
                .panel("root", 0, 0, 100, 100, null)
                .button("buy", 10, 10, 60, 18, "Buy", "rpg_buy", params)
                .on("buy", "right_click", "rpg_preview")
                .build();

        UiElement buy = page.document().elements().get(1);
        int clickId = buy.events().get("click");
        int rightId = buy.events().get("right_click");
        assertTrue(clickId > 0 && rightId > 0 && clickId != rightId);
        PageAction click = page.actions().get(clickId).get(0);
        assertEquals("rpg_buy", click.type());
        assertEquals("minecraft:diamond", click.params().get("item"));
        assertEquals("rpg_preview", page.actions().get(rightId).get(0).type());
    }

    @Test
    void bindingTheSameEventTwiceAppendsAnAction() {
        CompiledPage page = PageBuilder.create("p", SurfaceType.CUSTOM_SCREEN, 100, 100)
                .panel("root", 0, 0, 100, 100, null)
                .button("buy", 0, 0, 40, 18, "Buy", "first")
                .on("buy", "click", "second")
                .build();

        int clickId = page.document().elements().get(1).events().get("click");
        List<PageAction> actions = page.actions().get(clickId);
        assertEquals(2, actions.size());
        assertEquals("first", actions.get(0).type());
        assertEquals("second", actions.get(1).type());
    }

    @Test
    void stateIsCarriedIntoTheCompiledPage() {
        CompiledPage page = PageBuilder.create("p", SurfaceType.CUSTOM_SCREEN, 100, 100)
                .state("clicks", 0)
                .panel("root", 0, 0, 100, 100, null)
                .build();
        assertEquals(0, page.state().get("clicks"));
    }

    @Test
    void attributesCanSetAnySupportedField() {
        CompiledPage page = PageBuilder.create("p", SurfaceType.CUSTOM_SCREEN, 100, 100)
                .panel("root", 0, 0, 100, 100, "#CC202830")
                .attribute("root", "nine_slice", List.of(2, 2, 2, 2))
                .build();
        assertNotNull(page.document().elements().get(0).attributes().get("nine_slice"));
    }

    @Test
    void rejectsDuplicateIdsUnknownElementsAndBadSizes() {
        PageBuilder builder = PageBuilder.create("p", SurfaceType.CUSTOM_SCREEN, 100, 100)
                .panel("root", 0, 0, 100, 100, null);
        assertThrows(IllegalArgumentException.class,
                () -> builder.label("root", 0, 0, "dupe"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.on("missing", "click", "x"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.attribute("missing", "color", "#FFFFFFFF"));
        assertThrows(IllegalArgumentException.class,
                () -> PageBuilder.create("p", SurfaceType.CUSTOM_SCREEN, 0, 100));
    }
}
