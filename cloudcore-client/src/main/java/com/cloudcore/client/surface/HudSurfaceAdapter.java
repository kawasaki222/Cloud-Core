package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import com.cloudcore.client.render.DocumentRenderListBuilder;
import com.cloudcore.client.render.DocumentRenderer;
import com.cloudcore.client.render.FrameContext;
import com.cloudcore.client.render.HudValues;
import com.cloudcore.client.render.NativeBackend;
import com.cloudcore.client.render.RenderList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent HUD surface. Each {@code UI_OPEN} names a layer in {@code surfaceKey}
 * ({@code background}, {@code foreground} or {@code overlay}); the document is drawn at the
 * anchor in {@code surface_options.anchor} (default {@code top_left}) plus {@code offset}
 * ({@code offset_x}, {@code offset_y}).
 *
 * <p>A document may also replace vanilla HUD parts: {@code surface_options.replace: ["health",
 * "hunger", "xp", "hotbar"]} makes {@link GuiMixin} cancel the vanilla bars, and the document
 * draws its own. Elements with {@code value_from}/{@code text_from} read live player values
 * ({@link HudValues}); a {@code hotbar} element is drawn here with the player's items.
 *
 * <p>Render thread only: documents are written on the client thread and only read here.
 */
public final class HudSurfaceAdapter implements SurfaceAdapter {
    public static final String LAYER_BACKGROUND = "background";
    public static final String LAYER_FOREGROUND = "foreground";
    public static final String LAYER_OVERLAY = "overlay";

    private static final DocumentRenderer RENDERER = new DocumentRenderer();
    private static final Map<String, Layer> LAYERS = new LinkedHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");

    /**
     * Ordering against an open screen. {@link #VANILLA} follows the vanilla HUD, which is
     * drawn before the screen and therefore sits below it, so the layer yields while any
     * screen is open. {@link #OVERLAY} is for CloudCore's own pages that stay on top.
     */
    public enum Priority {
        VANILLA,
        OVERLAY
    }

    /**
     * Reads {@code surface_options.priority}. Documents that replace vanilla parts default to
     * {@link Priority#VANILLA} — they stand in for the HUD, so they inherit its ordering —
     * and everything else defaults to {@link Priority#OVERLAY} to keep its current behaviour.
     */
    private static Priority priorityOf(UiDocument document) {
        Object value = document.surfaceOptions().get("priority");
        if (value instanceof String text) {
            String normalised = text.trim().toLowerCase(Locale.ROOT);
            if ("vanilla".equals(normalised) || "below_screen".equals(normalised)) {
                return Priority.VANILLA;
            }
            if ("overlay".equals(normalised) || "above_screen".equals(normalised)) {
                return Priority.OVERLAY;
            }
        }
        return replacesIn(document, "health") || replacesIn(document, "food")
                || replacesIn(document, "hotbar") || replacesIn(document, "xp")
                || replacesIn(document, "armor") || replacesIn(document, "air")
                ? Priority.VANILLA
                : Priority.OVERLAY;
    }

    /** One layer holds its document plus the cached render list for the current viewport. */
    private static final class Layer {
        private volatile UiDocument document;
        private volatile Priority priority = Priority.OVERLAY;
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
        Layer target = LAYERS.computeIfAbsent(layer, ignored -> new Layer());
        UiDocument document = runtime.document();
        target.priority = priorityOf(document);
        target.document = document;
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

    /**
     * @return true when an open HUD document asks to replace that vanilla part
     *         ({@code health}, {@code hunger}, {@code xp}, {@code hotbar})
     */
    public static boolean replaces(String part) {
        for (Layer layer : LAYERS.values()) {
            UiDocument document = layer.document;
            if (document == null) {
                continue;
            }
            if (replacesIn(document, part)) {
                return true;
            }
        }
        return false;
    }

    /** Single-document form used when deciding a layer's priority before it is registered. */
    private static boolean replacesIn(UiDocument document, String part) {
        Object value = document.surfaceOptions().get("replace");
        if (value instanceof Iterable<?> parts) {
            for (Object entry : parts) {
                if (entry instanceof String text && text.equalsIgnoreCase(part)) {
                    return true;
                }
            }
            return false;
        }
        return value instanceof String text && text.equalsIgnoreCase(part);
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
        // Live values change every frame; a rewritten document compares structurally, so the
        // list is rebuilt only when an actual value changed.
        UiDocument live = HudValues.apply(document);
        if (layer.cachedDocument != live || layer.cachedWidth != width
                || layer.cachedHeight != height) {
            String anchor = anchorOf(document);
            int originX = originX(document, anchor, width);
            int originY = originY(document, anchor, height);
            layer.cachedList = DocumentRenderListBuilder.buildAt(live, width, height,
                    originX, originY);
            layer.cachedDocument = live;
            layer.cachedWidth = width;
            layer.cachedHeight = height;
            LOGGER.debug("HUD layer {} ui={} origin={},{} viewport={}x{} commands={}", layerName,
                    document.id(), originX, originY, width, height,
                    layer.cachedList.commands().size());
            if (Boolean.getBoolean("cloudcore.hud.debug")) {
                for (var command : layer.cachedList.commands()) {
                    LOGGER.info("HUD cmd {}", command);
                }
            }
        }
        RENDERER.render(graphics, layer.cachedList, new FrameContext(graphics, width, height,
                minecraft.getWindow().getGuiScale(), 0.0f));

        UiDocument rendered = layer.cachedDocument;
        if (rendered != null) {
            int originX = originX(rendered, anchorOf(rendered), width);
            int originY = originY(rendered, anchorOf(rendered), height);
            renderHotbars(graphics, rendered, originX, originY);
        }
    }

    private static int originX(UiDocument document, String anchor, int screenWidth) {
        int documentWidth = Math.round(document.width() * document.scale());
        return anchorX(anchor, screenWidth, documentWidth) + offset(document, "offset_x");
    }

    private static int originY(UiDocument document, String anchor, int screenHeight) {
        int documentHeight = Math.round(document.height() * document.scale());
        return anchorY(anchor, screenHeight, documentHeight) + offset(document, "offset_y");
    }

    /** A {@code hotbar} element: frame texture, live item icons, counts and the selected slot. */
    private static void renderHotbars(GuiGraphics graphics, UiDocument document, int originX,
            int originY) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        for (UiElement element : document.elements()) {
            if (!element.visible() || !"hotbar".equals(element.type())) {
                continue;
            }
            int slots = intAttribute(element, "slots", 9);
            int cell = intAttribute(element, "cell", 20);
            int gap = intAttribute(element, "gap", 0);
            int width = slots * cell + Math.max(0, slots - 1) * gap;
            int x = originX + element.x();
            int y = originY + element.y();

            Object texture = element.attributes().get("texture");
            if (texture instanceof String reference && NativeBackend.isTextureReference(reference)) {
                NativeBackend.drawTexture(graphics, reference, x, y, width, cell,
                        intArray(element.attributes().get("nine_slice")));
            } else {
                graphics.fill(x, y, x + width, y + cell, 0xA0101317);
            }

            int slotFill = com.cloudcore.client.render.Colors.parse(
                    styleColor(element, "slot"), 0xC0181C22);
            int slotBorder = com.cloudcore.client.render.Colors.parse(
                    styleColor(element, "slot_border"), 0xFF8E7434);
            int selectedColor = com.cloudcore.client.render.Colors.parse(
                    styleColor(element, "selected"), 0xFFFFD479);
            int selected = player.getInventory().getSelectedSlot();
            boolean showCounts = !Boolean.FALSE.equals(element.attributes().get("show_counts"));
            for (int slot = 0; slot < slots; slot++) {
                int slotX = x + slot * (cell + gap);
                graphics.fill(slotX + 1, y + 1, slotX + cell - 1, y + cell - 1, slotFill);
                if (slot == selected) {
                    // A bright double ring marks the held slot.
                    graphics.fill(slotX, y, slotX + cell, y + 2, selectedColor);
                    graphics.fill(slotX, y + cell - 2, slotX + cell, y + cell, selectedColor);
                    graphics.fill(slotX, y + 2, slotX + 2, y + cell - 2, selectedColor);
                    graphics.fill(slotX + cell - 2, y + 2, slotX + cell, y + cell - 2,
                            selectedColor);
                } else {
                    graphics.fill(slotX, y, slotX + cell, y + 1, slotBorder);
                    graphics.fill(slotX, y + cell - 1, slotX + cell, y + cell, slotBorder);
                    graphics.fill(slotX, y + 1, slotX + 1, y + cell - 1, slotBorder);
                    graphics.fill(slotX + cell - 1, y + 1, slotX + cell, y + cell - 1,
                            slotBorder);
                }
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                float scale = Math.max(0.5f, (cell - 4) / 16.0f);
                var pose = graphics.pose();
                pose.pushMatrix();
                pose.translate(slotX + 2.0f, y + 2.0f);
                pose.scale(scale, scale);
                graphics.renderItem(stack, 0, 0);
                pose.popMatrix();
                if (showCounts && stack.getCount() > 1) {
                    String count = String.valueOf(stack.getCount());
                    graphics.drawString(Minecraft.getInstance().font, count,
                            slotX + cell - 2 - Minecraft.getInstance().font.width(count),
                            y + cell - 9, 0xFFFFFFFF, true);
                }
            }
        }
    }

    private static int intAttribute(UiElement element, String key, int fallback) {
        Object value = element.attributes().get(key);
        if (value instanceof Number number) {
            return Math.max(1, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(1, Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static Object styleColor(UiElement element, String key) {
        Object style = element.attributes().get("style");
        if (style instanceof Map<?, ?> map) {
            return map.get(key);
        }
        return null;
    }

    private static int[] intArray(Object value) {
        if (!(value instanceof java.util.List<?> list) || list.size() != 4) {
            return null;
        }
        int[] borders = new int[4];
        for (int index = 0; index < 4; index++) {
            borders[index] = list.get(index) instanceof Number number
                    ? Math.max(0, number.intValue()) : 0;
        }
        return borders;
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
