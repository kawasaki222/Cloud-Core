package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

/**
 * Minimal BossBar skin for sentinel-matched Paper boss bars. The title remains native;
 * this adapter replaces the background/progress bars.
 */
public final class BossBarSurfaceAdapter implements SurfaceAdapter {
    private static final AtomicReference<UiDocument> DOCUMENT = new AtomicReference<>();
    private static final int BAR_WIDTH = 182;
    private static final int BAR_HEIGHT = 5;

    @Override
    public SurfaceType type() {
        return SurfaceType.BOSS_BAR;
    }

    @Override
    public boolean canHandle(OpenRequest request, Minecraft minecraft) {
        return request.surface() == SurfaceType.BOSS_BAR;
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

    /**
     * True when a boss skin applies to this bar, whether it came from the server or from a
     * client-side page matched by name. Vanilla then draws its own title above the bar in
     * addition to the adapter's in-bar name, which stacks two labels on one bar.
     */
    public static boolean skins(Component title) {
        if (DOCUMENT.get() != null && title.getString().contains(MARKER)) {
            return true;
        }
        return com.cloudcore.client.render.LocalPageRegistry.matches(
                com.cloudcore.api.SurfaceType.BOSS_BAR, stripSentinel(title).getString());
    }

    /**
     * Sentinel header: {@code \0CCB:<token>\0} for a plain bar, or
     * {@code \0CCB:<token>:<p1>,<p2>,...\0} for a multi-layer bar, where each {@code p} is a
     * per-mille value of that layer's remaining strength.
     *
     * <p>Every layer's value travels in the title at once because the layers are painted
     * <em>over</em> each other: the inner layer is already on screen behind the outer one, so
     * depleting the outer layer uncovers what is underneath instead of switching bars.
     */
    static final String MARKER = "\u0000CCB:";

    /**
     * @param token  opaque per-page id the server chose
     * @param values per-mille remaining strength of each layer, outermost first;
     *               a single entry for a plain bar
     */
    record Sentinel(String token, int[] values) {
        int layerCount() {
            return values.length;
        }

        /** Remaining strength of a layer in {@code 0..1}. */
        float value(int index) {
            return Math.max(0.0f, Math.min(1.0f, values[index] / 1000.0f));
        }
    }

    private static final Sentinel PLAIN = new Sentinel("", new int[]{1000});

    /** @return the parsed header, or {@code null} when the title carries no sentinel */
    static Sentinel sentinel(Component title) {
        String raw = title.getString();
        int start = raw.indexOf(MARKER);
        if (start < 0) {
            return null;
        }
        int end = raw.indexOf('\u0000', start + 1);
        if (end < 0) {
            return null;
        }
        String header = raw.substring(start + MARKER.length(), end);
        int colon = header.indexOf(':');
        if (colon < 0) {
            return new Sentinel(header, new int[]{1000});
        }
        String token = header.substring(0, colon);
        String encoded = header.substring(colon + 1);
        String[] parts = encoded.split(",");
        int[] values = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            try {
                values[index] = Math.max(0, Math.min(1000,
                        Integer.parseInt(parts[index].trim())));
            } catch (NumberFormatException ignored) {
                // A malformed layer must not hide the bar: treat it as full.
                values[index] = 1000;
            }
        }
        return values.length == 0 ? new Sentinel(token, new int[]{1000})
                : new Sentinel(token, values);
    }

    public static Component stripSentinel(Component title) {
        String raw = title.getString();
        int start = raw.indexOf(MARKER);
        if (start < 0) {
            return title;
        }
        int end = raw.indexOf('\u0000', start + 1);
        if (end < 0) {
            return title;
        }
        return Component.literal(raw.substring(end + 1)).setStyle(title.getStyle());
    }

    @Override
    public void reset(CloseReason reason) {
        DOCUMENT.set(null);
    }

    public static boolean render(GuiGraphics graphics, int x, int y, BossEvent event) {
        String title = stripSentinel(event.getName()).getString();
        UiDocument document = DOCUMENT.get();
        if (document == null) {
            // Nothing sent by the server: fall back to a client-side page that declared a
            // 'match' for this bar's name. This is the serverless path, so a player's own
            // skin applies on any server without a page ever being pushed.
            document = com.cloudcore.client.render.LocalPageRegistry.find(
                    com.cloudcore.api.SurfaceType.BOSS_BAR, title);
        }
        if (document == null) {
            return false;
        }
        Sentinel sentinel = sentinel(event.getName());
        java.util.Map<String, Object> bar = barOptions(document);
        // The frame comes from the outermost layer's styling; each layer then paints its own
        // fill inside that same frame.
        java.util.Map<String, Object> frame = layerOptions(bar, 0);
        int width = intOption(frame, "width", intOption(bar, "width", BAR_WIDTH));
        int height = intOption(frame, "height", intOption(bar, "height", BAR_HEIGHT));
        // Vanilla hands drawBar the left edge of its 182px bar. A custom width has to be
        // recentred on the screen or a wider bar hangs off to the right.
        int left = (graphics.guiWidth() - width) / 2;
        int top = y;
        int[] slice = nineSlice(frame.get("nine_slice"));

        graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1,
                com.cloudcore.client.render.Colors.parse(frame.get("border"), 0xE0000000));
        Object background = frame.get("background");
        if (!(background instanceof String backgroundRef
                && com.cloudcore.client.render.NativeBackend.isTextureReference(backgroundRef)
                && com.cloudcore.client.render.NativeBackend.drawTexture(graphics, backgroundRef,
                left, top, width, height, slice))) {
            graphics.fill(left, top, left + width, top + height,
                    com.cloudcore.client.render.Colors.parse(background, 0xFF1A2028));
        }

        // The fill sits inside the frame; fill_inset keeps the background's border visible
        // instead of painting the fill over it.
        java.util.List<java.util.Map<String, Object>> layers = layerList(bar);
        int layerCount = Math.max(layers.size(), sentinel == null ? 1 : sentinel.layerCount());
        int[] inset = intArray(frame.get("fill_inset"));
        int innerX = left + inset[0];
        int innerY = top + inset[1];
        int innerWidth = Math.max(1, width - inset[0] - inset[2]);
        int innerHeight = Math.max(1, height - inset[1] - inset[3]);
        // Innermost first: every layer paints over the one before it, so the outer layer
        // hides the rest until it burns down and uncovers what was already underneath.
        for (int index = layerCount - 1; index >= 0; index--) {
            java.util.Map<String, Object> layer = index < layers.size() ? layers.get(index)
                    : index == 0 ? frame : java.util.Map.of();
            float value = layerValue(layer, sentinel, index, event, layerCount);
            int filled = Math.round(innerWidth * value);
            if (filled > 0) {
                paintFill(graphics, layer, event, innerX, innerY, innerWidth, innerHeight, filled);
            }
        }

        // Overlay is drawn over the whole bar, clipped to nothing: use it for gloss or for a
        // frame texture with a transparent centre.
        Object overlay = frame.get("overlay");
        if (overlay instanceof String overlayRef
                && com.cloudcore.client.render.NativeBackend.isTextureReference(overlayRef)) {
            com.cloudcore.client.render.NativeBackend.drawTexture(graphics, overlayRef,
                    left, top, width, height, slice);
        }

        if (!Boolean.FALSE.equals(frame.get("show_name"))) {
            int topLayer = 0;
            while (topLayer < layerCount - 1
                    && layerValue(topLayer < layers.size() ? layers.get(topLayer) : java.util.Map.of(),
                            sentinel, topLayer, event, layerCount) <= 0.0f) {
                topLayer++;
            }
            java.util.Map<String, Object> labelLayer = topLayer < layers.size()
                    ? layers.get(topLayer) : frame;
            drawName(graphics, labelLayer, event, sentinel, topLayer, layerCount,
                    left, top, width, height);
        }
        return true;
    }

    /**
     * Remaining strength of one layer.
     *
     * <p>Server-driven bars carry every layer's value in the sentinel. A client-side page has
     * no sentinel, so only the boss event's single progress is known: the layer the player is
     * actually fighting tracks it and the layers behind it stay full, which is what makes the
     * stack read as one bar with more underneath. A layer may pin its own {@code value} to
     * override that.
     */
    private static float layerValue(java.util.Map<String, Object> layer, Sentinel sentinel,
            int index, BossEvent event, int layerCount) {
        if (sentinel != null && index < sentinel.layerCount()) {
            return sentinel.value(index);
        }
        float progress = Math.max(0.0f, Math.min(1.0f, event.getProgress()));
        float declared = declaredValue(layer.get("value"));
        if (!Float.isNaN(declared)) {
            return declared;
        }
        if (index == 0 || layerCount <= 1) {
            return progress;
        }
        return 1.0f;
    }

    /** {@code value} may be a 0..1 fraction or a percentage; NaN when absent or unreadable. */
    private static float declaredValue(Object value) {
        double raw;
        if (value instanceof Number number) {
            raw = number.doubleValue();
        } else if (value instanceof String text) {
            String trimmed = text.trim();
            boolean percent = trimmed.endsWith("%");
            if (percent) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }
            try {
                raw = Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
                return Float.NaN;
            }
            if (percent) {
                raw = raw / 100.0;
            }
        } else {
            return Float.NaN;
        }
        return (float) Math.max(0.0, Math.min(1.0, raw));
    }

    /** Paints one layer's fill and tint inside the shared frame. */
    private static void paintFill(GuiGraphics graphics, java.util.Map<String, Object> layer,
            BossEvent event, int innerX, int innerY, int innerWidth, int innerHeight,
            int filled) {
        Object fill = layer.get("fill");
        boolean tintFromEvent = !Boolean.FALSE.equals(layer.get("tint_from_event"));
        boolean textured = false;
        if (fill instanceof String fillRef && !fillRef.startsWith("#")
                && com.cloudcore.client.render.NativeBackend.isTextureReference(fillRef)) {
            // A texture fill keeps its own size and is clipped to the progress.
            graphics.enableScissor(innerX, innerY, innerX + filled, innerY + innerHeight);
            textured = com.cloudcore.client.render.NativeBackend.drawTexture(graphics,
                    fillRef, innerX, innerY, innerWidth, innerHeight, null);
            graphics.disableScissor();
        }
        // A layer's own tint wins over the event colour, which is what makes a blue shield
        // over a red body readable on one bar.
        int layerTint = com.cloudcore.client.render.Colors.parse(layer.get("tint"), 0);
        if (textured) {
            if (tintFromEvent || (layerTint >>> 24) != 0) {
                // One neutral fill texture serves every layer: a translucent tint layer over
                // it keeps the metal streaks readable.
                int alpha = (int) Math.round(255 * doubleOption(layer, "tint_alpha", 0.62));
                int rgb = (layerTint >>> 24) != 0 ? layerTint & 0xFFFFFF
                        : eventColor(event) & 0xFFFFFF;
                int tint = (Math.max(0, Math.min(255, alpha)) << 24) | rgb;
                graphics.fill(innerX, innerY, innerX + filled, innerY + innerHeight, tint);
            }
        } else {
            int fillColor = fill instanceof String colour && colour.startsWith("#")
                    ? com.cloudcore.client.render.Colors.parse(colour, eventColor(event))
                    : (layerTint >>> 24) != 0 ? layerTint
                    : tintFromEvent ? eventColor(event) : 0xFF4FA3E8;
            graphics.fill(innerX, innerY, innerX + filled, innerY + innerHeight, fillColor);
        }
    }

    /**
     * Boss names live inside the bar: with a taller custom bar the vanilla name above it
     * would collide with the next stacked bar.
     */
    private static void drawName(GuiGraphics graphics, java.util.Map<String, Object> layer,
            BossEvent event, Sentinel sentinel, int topLayer, int layerCount,
            int left, int top, int width, int height) {
        // A layer may label itself ("护盾" / "本体"), which reads better than repeating the
        // boss name three times as the stack burns down.
        String name = layer.get("name") instanceof String label && !label.isBlank()
                ? label : stripSentinel(event.getName()).getString();
        if (name.isEmpty()) {
            return;
        }
        java.util.Map<String, Object> text = mapOption(layer.get("text"));
        int color = com.cloudcore.client.render.Colors.parse(text.get("color"), 0xFFFFFFFF);
        boolean shadow = !Boolean.FALSE.equals(text.get("shadow"));
        String align = text.get("align") instanceof String value
                ? value.toLowerCase(java.util.Locale.ROOT) : "center";
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        int textY = top + (height - font.lineHeight) / 2 + 1;
        int textX = switch (align) {
            case "left" -> left + 8;
            case "right" -> left + width - 8 - font.width(name);
            default -> left + (width - font.width(name)) / 2;
        };
        graphics.drawString(font, name, textX, textY, color, shadow);
        // "1/3" on the right edge: with the layers stacked over each other the player
        // otherwise cannot tell how many remain behind the one currently showing.
        if (layerCount > 1 && !Boolean.FALSE.equals(text.get("show_layer_count"))) {
            String counter = (topLayer + 1) + "/" + layerCount;
            int counterColor = com.cloudcore.client.render.Colors.parse(
                    text.get("counter_color"), (color & 0xFFFFFF) | 0xB4000000);
            int counterX = switch (align) {
                case "right" -> left + 8;
                default -> left + width - 8 - font.width(counter);
            };
            graphics.drawString(font, counter, counterX, textY, counterColor, shadow);
        }
    }

    private static int eventColor(BossEvent event) {
        return switch (event.getColor()) {
            case PINK -> 0xFFF27FA5;
            case BLUE -> 0xFF4A8CFF;
            case RED -> 0xFFE04040;
            case GREEN -> 0xFF3FBF5F;
            case YELLOW -> 0xFFE0D040;
            case PURPLE -> 0xFFA060E0;
            case WHITE -> 0xFFE8E8E8;
        };
    }

    /** {@code surface_options.boss_bar.bar}, or {@code boss_bar} itself when flat. */
    private static java.util.Map<String, Object> barOptions(UiDocument document) {
        Object bossBar = document.surfaceOptions().get("boss_bar");
        if (bossBar instanceof java.util.Map<?, ?> map) {
            Object bar = map.get("bar");
            if (bar instanceof java.util.Map<?, ?> nested) {
                return castMap(nested);
            }
            return castMap(map);
        }
        return java.util.Map.of();
    }

    /**
     * The bar's {@code layers} list, or an empty list when it has none. Each entry is a layer
     * of the stack, outermost first, holding only the keys that differ from the shared
     * {@code bar} (tint, fill, label, text).
     */
    private static java.util.List<java.util.Map<String, Object>> layerList(
            java.util.Map<String, Object> bar) {
        if (!(bar.get("layers") instanceof java.util.List<?> list)) {
            return java.util.List.of();
        }
        java.util.List<java.util.Map<String, Object>> result = new java.util.ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof java.util.Map<?, ?> map) {
                java.util.Map<String, Object> layer = castMap(map);
                // Text is the one nested block layers routinely tweak, so merge it rather
                // than letting a layer that only sets counter_color lose the shared alignment.
                if (bar.get("text") instanceof java.util.Map<?, ?> base
                        && layer.get("text") != null) {
                    java.util.Map<String, Object> text = castMap(base);
                    text.putAll(mapOption(layer.get("text")));
                    layer.put("text", text);
                }
                result.add(layer);
            }
        }
        return result;
    }

    /** A layer's options laid over the shared {@code bar} options. */
    private static java.util.Map<String, Object> layerOptions(java.util.Map<String, Object> bar,
            int index) {
        java.util.List<java.util.Map<String, Object>> layers = layerList(bar);
        if (index < 0 || index >= layers.size()) {
            return bar;
        }
        java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>(bar);
        merged.putAll(layers.get(index));
        return merged;
    }

    private static java.util.Map<String, Object> castMap(java.util.Map<?, ?> map) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private static int intOption(java.util.Map<String, Object> options, String key, int fallback) {
        Object value = options.get(key);
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

    private static double doubleOption(java.util.Map<String, Object> options, String key,
            double fallback) {
        Object value = options.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** @return four non-negative insets, zeros when absent or malformed */
    private static int[] intArray(Object value) {
        int[] result = new int[4];
        if (value instanceof java.util.List<?> list && list.size() == 4) {
            for (int index = 0; index < 4; index++) {
                result[index] = list.get(index) instanceof Number number
                        ? Math.max(0, number.intValue()) : 0;
            }
        }
        return result;
    }

    private static java.util.Map<String, Object> mapOption(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            return castMap(map);
        }
        return java.util.Map.of();
    }

    private static int[] nineSlice(Object value) {
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
}
