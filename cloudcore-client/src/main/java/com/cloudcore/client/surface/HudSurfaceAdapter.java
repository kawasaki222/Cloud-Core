package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.client.render.DocumentRenderListBuilder;
import com.cloudcore.client.render.DocumentRenderer;
import com.cloudcore.client.render.FrameContext;
import com.cloudcore.client.render.RenderList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Persistent HUD surface. Each {@code UI_OPEN} names a layer in {@code surfaceKey}
 * ({@code background}, {@code foreground} or {@code overlay}); the document is drawn at the
 * anchor in {@code surface_options.anchor} (default {@code top_left}) plus {@code offset}
 * ({@code offset_x}, {@code offset_y}).
 *
 * <p>Render thread only: documents are written on the client thread and only read here.
 */
public final class HudSurfaceAdapter implements SurfaceAdapter {
    public static final String LAYER_BACKGROUND = "background";
    public static final String LAYER_FOREGROUND = "foreground";
    public static final String LAYER_OVERLAY = "overlay";

    private static final DocumentRenderer RENDERER = new DocumentRenderer();
    private static final Map<String, Layer> LAYERS = new LinkedHashMap<>();

    /** One layer holds its document plus the cached render list for the current viewport. */
    private static final class Layer {
        private volatile UiDocument document;
        private RenderList cachedList = RenderList.EMPTY;
        private UiDocument cachedDocument;
        private int cachedWidth = -1;
        private int cachedHeight = -1;
    }

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
        String layer = layerOf(request.surfaceKey());
        LAYERS.computeIfAbsent(layer, ignored -> new Layer()).document = runtime.document();
    }

    @Override
    public void close(String uiId, CloseReason reason) {
        LAYERS.values().forEach(layer -> {
            UiDocument document = layer.document;
            if (document != null && document.id().equals(uiId)) {
                layer.document = null;
            }
        });
    }

    @Override
    public void reset(CloseReason reason) {
        LAYERS.clear();
    }

    private static String layerOf(String key) {
        if (key == null) {
            return LAYER_FOREGROUND;
        }
        String normalised = key.toLowerCase(Locale.ROOT);
        return switch (normalised) {
            case LAYER_BACKGROUND, LAYER_FOREGROUND, LAYER_OVERLAY -> normalised;
            default -> LAYER_FOREGROUND;
        };
    }

    /** Draws one layer; called from the Fabric HUD element for that layer. */
    public static void render(GuiGraphics graphics, String layerName) {
        Layer layer = LAYERS.get(layerName);
        if (layer == null) {
            return;
        }
        UiDocument document = layer.document;
        if (document == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        if (layer.cachedDocument != document || layer.cachedWidth != width
                || layer.cachedHeight != height) {
            int documentWidth = Math.round(document.width() * document.scale());
            int documentHeight = Math.round(document.height() * document.scale());
            String anchor = anchorOf(document);
            int originX = anchorX(anchor, width, documentWidth) + offset(document, "offset_x");
            int originY = anchorY(anchor, height, documentHeight) + offset(document, "offset_y");
            layer.cachedList = DocumentRenderListBuilder.buildAt(document, width, height,
                    originX, originY);
            layer.cachedDocument = document;
            layer.cachedWidth = width;
            layer.cachedHeight = height;
        }
        RENDERER.render(graphics, layer.cachedList, new FrameContext(graphics, width, height,
                minecraft.getWindow().getGuiScale(), 0.0f));
    }

    private static String anchorOf(UiDocument document) {
        Object anchor = document.surfaceOptions().get("anchor");
        return anchor instanceof String text ? text.toLowerCase(Locale.ROOT) : "top_left";
    }

    private static int offset(UiDocument document, String key) {
        Object value = document.surfaceOptions().get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    static int anchorX(String anchor, int screenWidth, int documentWidth) {
        return switch (anchor) {
            case "top_right", "center_right", "bottom_right" -> screenWidth - documentWidth;
            case "top_center", "center", "bottom_center" -> (screenWidth - documentWidth) / 2;
            default -> 0;
        };
    }

    static int anchorY(String anchor, int screenHeight, int documentHeight) {
        return switch (anchor) {
            case "bottom_left", "bottom_center", "bottom_right" -> screenHeight - documentHeight;
            case "center_left", "center", "center_right" -> (screenHeight - documentHeight) / 2;
            default -> 0;
        };
    }
}
