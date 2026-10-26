package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.client.render.DocumentRenderListBuilder;
import com.cloudcore.client.render.DocumentRenderer;
import com.cloudcore.client.render.FrameContext;
import com.cloudcore.client.render.RenderList;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Persistent HUD surface. The document is rendered through the same native RenderList as
 * screens; it is registered once with Fabric's HUD element registry.
 */
public final class HudSurfaceAdapter implements SurfaceAdapter {
    private static final AtomicReference<UiDocument> DOCUMENT = new AtomicReference<>();
    private static final DocumentRenderer RENDERER = new DocumentRenderer();
    private static RenderList cachedList = RenderList.EMPTY;
    private static UiDocument cachedDocument;
    private static int cachedWidth = -1;
    private static int cachedHeight = -1;

    @Override
    public SurfaceType type() {
        return SurfaceType.HUD;
    }

    @Override
    public boolean canHandle(OpenRequest request, Minecraft minecraft) {
        return request.surface() == SurfaceType.HUD;
    }

    @Override
    public void open(OpenRequest request, UiRuntime runtime) {
        DOCUMENT.set(runtime.document());
    }

    @Override
    public void close(String uiId, CloseReason reason) {
        UiDocument document = DOCUMENT.get();
        if (document != null && document.id().equals(uiId)) {
            DOCUMENT.set(null);
        }
    }

    @Override
    public void reset(CloseReason reason) {
        DOCUMENT.set(null);
    }

    public static void render(GuiGraphics graphics) {
        UiDocument document = DOCUMENT.get();
        if (document == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        if (cachedDocument != document || cachedWidth != width || cachedHeight != height) {
            cachedList = DocumentRenderListBuilder.build(document, width, height);
            cachedDocument = document;
            cachedWidth = width;
            cachedHeight = height;
        }
        RENDERER.render(graphics, cachedList, new FrameContext(graphics, width, height,
                minecraft.getWindow().getGuiScale(), 0.0f));
    }
}
