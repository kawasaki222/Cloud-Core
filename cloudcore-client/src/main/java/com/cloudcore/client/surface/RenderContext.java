package com.cloudcore.client.surface;

import net.minecraft.client.gui.GuiGraphics;

public record RenderContext(
        GuiGraphics graphics,
        int screenWidth,
        int screenHeight,
        int mouseX,
        int mouseY,
        float partialTick) {
}
