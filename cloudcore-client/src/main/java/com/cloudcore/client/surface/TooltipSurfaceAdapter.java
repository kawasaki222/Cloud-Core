package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.api.document.UiElement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.joml.Vector2ic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal custom tooltip skin. It draws the configured panel behind the native tooltip
 * text/images and cancels the vanilla background. Scrolling and nine-slice assets follow
 * in the Tooltip milestone.
 */
public final class TooltipSurfaceAdapter implements SurfaceAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final AtomicReference<UiDocument> DOCUMENT = new AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicBoolean FIRST_RENDER =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final int PADDING = 3;

    @Override
    public SurfaceType type() {
        return SurfaceType.TOOLTIP;
    }

    @Override
    public boolean canHandle(OpenRequest request, Minecraft minecraft) {
        return request.surface() == SurfaceType.TOOLTIP;
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

    public static boolean render(GuiGraphics graphics, Font font,
            List<ClientTooltipComponent> lines, int x, int y,
            ClientTooltipPositioner positioner) {
        UiDocument document = DOCUMENT.get();
        if (document == null || lines.isEmpty()) {
            return false;
        }
        int contentWidth = 0;
        int contentHeight = 0;
        for (ClientTooltipComponent line : lines) {
            contentWidth = Math.max(contentWidth, line.getWidth(font));
            contentHeight += line.getHeight(font);
        }
        int width = contentWidth + PADDING * 2;
        int height = contentHeight + PADDING * 2;
        Minecraft minecraft = Minecraft.getInstance();
        Vector2ic position = positioner.positionTooltip(
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight(),
                x, y, width, height);
        int left = position.x();
        int top = position.y();
        int background = background(document);
        graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, 0xE0000000);
        graphics.fill(left, top, left + width, top + height, background);

        int lineY = top + PADDING;
        for (ClientTooltipComponent line : lines) {
            line.renderText(graphics, font, left + PADDING, lineY);
            line.renderImage(font, left + PADDING, lineY,
                    line.getWidth(font), line.getHeight(font), graphics);
            lineY += line.getHeight(font);
        }
        if (FIRST_RENDER.compareAndSet(false, true)) {
            LOGGER.info("Custom tooltip rendered lines={} at={},{} size={}x{}", lines.size(),
                    left, top, width, height);
        } else {
            LOGGER.debug("Custom tooltip rendered lines={} size={}x{}", lines.size(), width, height);
        }
        return true;
    }

    private static int background(UiDocument document) {
        for (UiElement element : document.elements()) {
            Object style = element.attributes().get("style");
            if (style instanceof Map<?, ?> map && map.get("background") instanceof String value
                    && value.startsWith("#")) {
                try {
                    String hex = value.substring(1);
                    if (hex.length() == 8) {
                        return Integer.parseUnsignedInt(hex, 16);
                    }
                    if (hex.length() == 6) {
                        return 0xFF000000 | Integer.parseUnsignedInt(hex, 16);
                    }
                } catch (NumberFormatException ignored) {
                    // Fall through to the default tooltip colour.
                }
            }
        }
        return 0xF0101828;
    }
}
