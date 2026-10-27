package com.cloudcore.client.screen;

import com.cloudcore.api.document.UiDocument;
import com.cloudcore.client.render.DocumentRenderListBuilder;
import com.cloudcore.client.render.DocumentRenderer;
import com.cloudcore.client.render.FrameContext;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import org.joml.Matrix3x2fStack;

/**
 * Player inventory skin. It reuses the vanilla {@code InventoryMenu} (container id 0) so the
 * server still receives ordinary slot clicks; only the background, slot sockets and Cloud
 * document are drawn here. The recipe book is intentionally not part of this screen: v1 hides
 * it (see docs/11-surfaces.md), which keeps the native menu untouched.
 *
 * <p>Thread: render thread only.
 */
public final class CloudInventoryScreen extends AbstractContainerScreen<InventoryMenu> {
    private static final int PANEL_FILL = 0xFF181C24;
    private static final int SLOT_BORDER = 0xFF8A8A8A;
    private static final int SLOT_FILL = 0xFF2B2B2B;

    private final UiDocument document;
    private final DocumentRenderer backend = new DocumentRenderer();

    public CloudInventoryScreen(Player player, UiDocument document) {
        super(player.inventoryMenu, player.getInventory(), Component.empty());
        this.document = document;
        this.imageWidth = Math.max(1, document.width());
        this.imageHeight = Math.max(1, document.height());
    }

    @Override
    protected void init() {
        super.init();
        // Only main inventory (9..35) and hotbar (36..44) are plain InventorySlots; armour,
        // crafting, result and offhand keep their subclasses and vanilla positions.
        SlotLayout.apply(document, menu.slots, index -> index >= 9 && index <= 44);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // The document draws its own labels; the vanilla container title is hidden.
    }

    @Override
    public void removed() {
        super.removed();
        // Each inventory open builds its own renderer, which owns a NanoVG context and an
        // off-screen target; releasing here keeps repeat opens from leaking both.
        backend.close();
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL_FILL);
        for (Slot slot : menu.slots) {
            if (slot.x < 0 || slot.y < 0) {
                continue;
            }
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            graphics.fill(x, y, x + 18, y + 18, SLOT_BORDER);
            graphics.fill(x + 1, y + 1, x + 17, y + 17, SLOT_FILL);
        }

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate((float) leftPos, (float) topPos);
        backend.render(graphics,
                DocumentRenderListBuilder.build(document, imageWidth, imageHeight),
                new FrameContext(graphics, imageWidth, imageHeight,
                        minecraft.getWindow().getGuiScale(), partialTick));
        pose.popMatrix();
    }
}
