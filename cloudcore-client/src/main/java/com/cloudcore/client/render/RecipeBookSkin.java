package com.cloudcore.client.render;

import com.cloudcore.api.document.UiDocument;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws the Cloud recipe book skin over the vanilla panel when the player's inventory
 * document asks for {@code recipe_book: skinned}.
 *
 * <p>Only the background is replaced: {@link RecipeBookComponentMixin} redirects the vanilla
 * background blit, so the search box, tabs, recipe grid and page arrows stay native and keep
 * their behaviour. The skin document is the same one the inventory uses, rendered into a box
 * the size of the vanilla panel at the position vanilla picked.
 *
 * <p>Render thread only.
 */
public final class RecipeBookSkin {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    /** Size of the vanilla recipe book panel, which is what the elements are laid out for. */
    public static final int PANEL_WIDTH = 147;
    public static final int PANEL_HEIGHT = 166;

    private static final AtomicReference<UiDocument> DOCUMENT = new AtomicReference<>();
    private static final DocumentRenderer RENDERER = new DocumentRenderer();

    private RecipeBookSkin() {
    }

    /**
     * Sets the skin drawn inside the recipe book, or clears it with null. Called when the
     * inventory opens or its document changes.
     */
    public static void set(UiDocument document) {
        DOCUMENT.set(document);
    }

    public static void clear() {
        DOCUMENT.set(null);
    }

    public static boolean active() {
        return DOCUMENT.get() != null;
    }

    /**
     * Draws the skin at the vanilla background rect.
     *
     * @return true when the skin was drawn and the vanilla background must be skipped
     */
    public static boolean drawSkinnedBackground(GuiGraphics graphics, int x, int y,
            int width, int height) {
        UiDocument document = DOCUMENT.get();
        if (document == null || width <= 0 || height <= 0) {
            return false;
        }
        try {
            // The elements are authored for the vanilla panel size; a different rect (a GUI
            // scale change mid-open) still uses the same commands scaled to fit.
            RenderList list = DocumentRenderListBuilder.buildAt(document, width, height, x, y,
                    id -> 0, null);
            FrameContext context = new FrameContext(graphics, width, height,
                    Minecraft.getInstance().getWindow().calculateScale(0, false), 0.0f);
            RENDERER.render(graphics, list, context);
            return true;
        } catch (RuntimeException exception) {
            // A broken skin must never take the recipe book down with it.
            LOGGER.warn("[CC-RENDER-040] recipe book skin failed; using vanilla background",
                    exception);
            DOCUMENT.set(null);
            return false;
        }
    }
}
