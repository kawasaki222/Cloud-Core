package com.cloudcore.client.mixin;

import com.cloudcore.client.CloudCoreClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Diagnostic hook for the private per-bar draw path. This is the probe used before the
 * BossBarSurfaceAdapter replaces the draw call.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;"
                            + "drawBar(Lnet/minecraft/client/gui/GuiGraphics;II"
                            + "Lnet/minecraft/world/BossEvent;)V"))
    private void cloudcore$observeBar(BossHealthOverlay overlay, GuiGraphics graphics,
            int x, int y, BossEvent event, Operation<Void> original) {
        CloudCoreClient.onBossBar(event.getName().getString());
        original.call(overlay, graphics, x, y, event);
    }
}
