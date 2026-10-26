package com.cloudcore.client.mixin;

import com.cloudcore.client.CloudCoreClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic hook for the private per-bar draw path. This is the probe used before the
 * BossBarSurfaceAdapter replaces the draw call.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {

    @Inject(method = "drawBar(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/world/BossEvent;)V",
            at = @At("HEAD"))
    private void cloudcore$observeBar(GuiGraphics graphics, int x, int y,
            BossEvent event, CallbackInfo callback) {
        CloudCoreClient.onBossBar(event.getName().getString());
    }
}
