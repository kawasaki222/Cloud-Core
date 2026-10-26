package com.cloudcore.client.screen;

import com.cloudcore.api.document.UiDocument;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Temporary minimal custom screen. It proves the lifecycle and input ownership before the
 * NanoVG render list lands; it deliberately does not pretend to draw document elements.
 */
public final class CloudScreen extends Screen {
    private static final int BACKGROUND = 0xB0000000;
    private static final int TITLE_COLOR = 0xFFFFFFFF;

    private final UiDocument document;

    public CloudScreen(UiDocument document) {
        super(Component.literal(Objects.requireNonNull(document, "document").title().plainText()));
        this.document = document;
    }

    public String uiId() {
        return document.id();
    }

    public UiDocument document() {
        return document;
    }

    @Override
    protected void init() {
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        graphics.drawString(font, title, 8, 8, TITLE_COLOR, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    @Override
    public void onClose() {
        minecraft.setScreen(null);
    }
}
