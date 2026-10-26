package com.cloudcore.client.mixin;

import com.cloudcore.client.CloudCoreClient;
import com.cloudcore.client.surface.BossBarSurfaceAdapter;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

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
        if (BossBarSurfaceAdapter.render(graphics, x, y, event)) {
            return;
        }
        original.call(overlay, graphics, x, y, event);
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;"
                            + "getName()Lnet/minecraft/network/chat/Component;"))
    private Component cloudcore$stripBossTitle(LerpingBossEvent event) {
        return BossBarSurfaceAdapter.stripSentinel(event.getName());
    }
}
