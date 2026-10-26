package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.client.screen.CloudContainerScreen;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * Replaces the next container screen with a CloudContainerScreen while preserving the
 * original menu, slot positions and click handling.
 */
public final class ContainerSurfaceAdapter implements SurfaceAdapter {
    private static final AtomicReference<UiDocument> DOCUMENT = new AtomicReference<>();
    private static volatile boolean armed;

    @Override
    public SurfaceType type() {
        return SurfaceType.CONTAINER;
    }

    @Override
    public boolean canHandle(OpenRequest request, Minecraft minecraft) {
        return request.surface() == SurfaceType.CONTAINER;
    }

    @Override
    public void open(OpenRequest request, UiRuntime runtime) {
        DOCUMENT.set(runtime.document());
        armed = true;
    }

    @Override
    public void close(String uiId, CloseReason reason) {
        UiDocument document = DOCUMENT.get();
        if (document != null && document.id().equals(uiId)) {
            DOCUMENT.set(null);
            armed = false;
        }
    }

    @Override
    public void reset(CloseReason reason) {
        DOCUMENT.set(null);
        armed = false;
    }

    public static Screen replace(Screen screen) {
        UiDocument document = DOCUMENT.get();
        if (!armed || document == null || !(screen instanceof AbstractContainerScreen<?> container)) {
            return screen;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return screen;
        }
        armed = false;
        Inventory inventory = minecraft.player.getInventory();
        AbstractContainerMenu menu = container.getMenu();
        return new CloudContainerScreen(menu, inventory, container.getTitle(), document);
    }
}
