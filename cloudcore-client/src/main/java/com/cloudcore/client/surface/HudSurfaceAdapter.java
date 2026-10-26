package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.client.render.DocumentRenderListBuilder;
import com.cloudcore.client.render.FrameContext;
import com.cloudcore.client.render.Layer;
import com.cloudcore.client.render.NativeBackend;
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
        RenderList list = DocumentRenderListBuilder.build(document, width, height);
        NativeBackend backend = new NativeBackend();
        backend.beginFrame(new FrameContext(graphics, width, height,
                minecraft.getWindow().getGuiScale(), 0.0f));
        try {
            backend.draw(list, Layer.BACKGROUND);
        } finally {
            backend.endFrame();
        }
    }
}
