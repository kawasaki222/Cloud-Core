package com.cloudcore.client.screen;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.client.render.DocumentRenderListBuilder;
import com.cloudcore.client.render.DocumentRenderer;
import com.cloudcore.client.render.FrameContext;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/**
 * Container screen that keeps the original menu/slots and replaces only the background
 * artwork. The document canvas is centred and the menu uses the same image dimensions.
 */
public final class CloudContainerScreen extends AbstractContainerScreen<AbstractContainerMenu> {
    private static final int BACKGROUND = 0x90000000;

    private final UiDocument document;
    private final DocumentRenderer backend = new DocumentRenderer();

    public CloudContainerScreen(AbstractContainerMenu menu, Inventory inventory,
            Component title, UiDocument document) {
        super(menu, inventory, title);
        this.document = document;
        this.imageWidth = Math.max(1, document.width());
        this.imageHeight = Math.max(1, document.height());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(0, 0, width, height, BACKGROUND);
        backend.render(graphics, DocumentRenderListBuilder.build(document, width, height),
                new FrameContext(graphics, width, height,
                        minecraft.getWindow().getGuiScale(), partialTick));
        for (Slot slot : menu.slots) {
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            graphics.fill(x, y, x + 18, y + 18, 0xFF707070);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF303030);
        }
    }
}
