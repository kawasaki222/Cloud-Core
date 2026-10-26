package com.cloudcore.client.surface;

import com.cloudcore.api.BindMode;
import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import com.cloudcore.client.screen.CloudInventoryScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replaces the vanilla player inventory with the Cloud skin. The {@code InventoryMenu} is the
 * player's own menu (container id 0), so slot clicks keep travelling through the vanilla path.
 *
 * <p>PERSISTENT (the normal player-inventory binding) keeps the skin until UI_CLOSE; other bind
 * modes apply it to the next inventory open only.
 */
public final class PlayerInventoryAdapter implements SurfaceAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("cloudcore");
    private static final PlayerInventorySession SESSION = new PlayerInventorySession();

    @Override
    public SurfaceType type() {
        return SurfaceType.PLAYER_INVENTORY;
    }

    @Override
    public boolean canHandle(OpenRequest request, Minecraft minecraft) {
        return request.surface() == SurfaceType.PLAYER_INVENTORY;
    }

    @Override
    public void open(OpenRequest request, UiRuntime runtime) {
        boolean persistent = request.bindMode() == BindMode.PERSISTENT;
        SESSION.open(runtime.document(), persistent);
        LOGGER.info("Player inventory skin active ui={} persistent={}",
                runtime.document().id(), persistent);
    }

    @Override
    public void close(String uiId, CloseReason reason) {
        String activeId = SESSION.activeUiId();
        SESSION.close(uiId);
        if (uiId.equals(activeId)) {
            LOGGER.info("Player inventory skin cleared ui={} reason={}", uiId, reason);
        }
    }

    public static Screen replace(Screen screen) {
        if (!(screen instanceof InventoryScreen) || screen instanceof CloudInventoryScreen) {
            return screen;
        }
        UiDocument document = SESSION.documentForNextOpen();
        if (document == null) {
            LOGGER.debug("Player inventory not replaced: active={} document={} screen={}",
                    SESSION.active(), SESSION.activeUiId(), screen.getClass().getName());
            return screen;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            LOGGER.debug("Player inventory not replaced: player unavailable");
            return screen;
        }
        LOGGER.debug("Player inventory replaced with Cloud skin ui={} persistent={}",
                document.id(), SESSION.persistent());
        return new CloudInventoryScreen(minecraft.player, document);
    }
}
