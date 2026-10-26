package com.cloudcore.client.render;

import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;

public record FrameContext(
        GuiGraphics graphics,
        int width,
        int height,
        int guiScale,
        float partialTick) {

    public FrameContext {
        Objects.requireNonNull(graphics, "graphics");
    }
}
