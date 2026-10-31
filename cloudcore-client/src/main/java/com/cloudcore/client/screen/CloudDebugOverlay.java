package com.cloudcore.client.screen;

import com.cloudcore.client.CloudCoreClient;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** F3 debug column: the Cloud Core session at a glance, without opening a log file. */
public final class CloudDebugOverlay {
    private CloudDebugOverlay() {
    }

    /** Draws on the right edge of the F3 screen, mirroring vanilla's own columns. */
    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getDebugOverlay() == null || !minecraft.getDebugOverlay().showDebugScreen()) {
            return;
        }
        List<String> lines = CloudCoreClient.debugLines();
        if (lines.isEmpty()) {
            return;
        }
        var font = minecraft.font;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font.width(line));
        }
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int x = Math.max(2, screenWidth - width - 2);
        // Vanilla's right F3 column owns the top; the bottom is HUD and hotbar territory.
        int y = Math.max(2, screenHeight / 2);
        for (String line : lines) {
            graphics.drawString(font, line, x, y, 0xFF55FFFF, true);
            y += 9;
        }
    }
}
