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

    public static Component stripSentinel(Component title) {
        String raw = title.getString();
        int start = raw.indexOf("\u0000CCB:");
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
        UiDocument document = DOCUMENT.get();
        if (document == null
                || !event.getName().getString().contains("\u0000CCB:")) {
            return false;
        }
        java.util.Map<String, Object> bar = barOptions(document);
        int width = intOption(bar, "width", BAR_WIDTH);
        int height = intOption(bar, "height", BAR_HEIGHT);

        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1,
                com.cloudcore.client.render.Colors.parse(bar.get("border"), 0xE0000000));
        Object background = bar.get("background");
        if (!(background instanceof String backgroundRef
                && com.cloudcore.client.render.NativeBackend.drawTexture(graphics, backgroundRef,
                x, y, width, height, nineSlice(bar.get("nine_slice"))))) {
            graphics.fill(x, y, x + width, y + height,
                    com.cloudcore.client.render.Colors.parse(background, 0xFF1A2028));
        }

        float value = Math.max(0.0f, Math.min(1.0f, event.getProgress()));
        int filled = Math.round(width * value);
        if (filled <= 0) {
            return true;
        }
        Object fill = bar.get("fill");
        if (fill instanceof String fillRef && !fillRef.startsWith("#")) {
            // A texture fill keeps its own size and is clipped to the progress.
            graphics.enableScissor(x, y, x + filled, y + height);
            boolean drawn = com.cloudcore.client.render.NativeBackend.drawTexture(graphics,
                    fillRef, x, y, width, height, null);
            graphics.disableScissor();
            if (drawn) {
                return true;
            }
        }
        boolean tintFromEvent = !Boolean.FALSE.equals(bar.get("tint_from_event"));
        int fillColor = fill instanceof String colour && colour.startsWith("#")
                ? com.cloudcore.client.render.Colors.parse(colour, eventColor(event))
                : tintFromEvent ? eventColor(event) : 0xFF4FA3E8;
        graphics.fill(x, y, x + filled, y + height, fillColor);
        return true;
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
