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
        if (DOCUMENT.get() == null
                || !event.getName().getString().contains("\u0000CCB:")) {
            return false;
        }
        int color = switch (event.getColor()) {
            case PINK -> 0xFFF27FA5;
            case BLUE -> 0xFF4A8CFF;
            case RED -> 0xFFE04040;
            case GREEN -> 0xFF3FBF5F;
            case YELLOW -> 0xFFE0D040;
            case PURPLE -> 0xFFA060E0;
            case WHITE -> 0xFFE8E8E8;
        };
        graphics.fill(x - 1, y - 1, x + BAR_WIDTH + 1, y + BAR_HEIGHT + 1, 0xE0000000);
        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, 0xFF1A2028);
        int progress = Math.round(BAR_WIDTH * Math.max(0.0f, Math.min(1.0f, event.getProgress())));
        if (progress > 0) {
            graphics.fill(x, y, x + progress, y + BAR_HEIGHT, color);
        }
        return true;
    }
}
