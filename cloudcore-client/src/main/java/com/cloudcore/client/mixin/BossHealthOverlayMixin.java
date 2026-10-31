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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Diagnostic hook for the private per-bar draw path. This is the probe used before the
 * BossBarSurfaceAdapter replaces the draw call.
 */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
    private static int cloudcore$customIndex;
    private static int cloudcore$firstCustomY = Integer.MIN_VALUE;

    @Inject(method = "render", at = @At("HEAD"))
    private void cloudcore$resetStack(GuiGraphics graphics, CallbackInfo callback) {
        cloudcore$customIndex = 0;
        cloudcore$firstCustomY = Integer.MIN_VALUE;
    }

    @WrapOperation(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/BossHealthOverlay;"
                            + "drawBar(Lnet/minecraft/client/gui/GuiGraphics;II"
                            + "Lnet/minecraft/world/BossEvent;)V"))
    private void cloudcore$observeBar(BossHealthOverlay overlay, GuiGraphics graphics,
            int x, int y, BossEvent event, Operation<Void> original) {
        CloudCoreClient.onBossBar(event.getName().getString());
        int adjustedY = y;
        if (BossBarSurfaceAdapter.hasSentinel(event.getName())) {
            int index = cloudcore$customIndex++;
            int maxBars = BossBarSurfaceAdapter.maxBars();
            if (maxBars > 0 && index >= maxBars) {
                return;
            }
            if (cloudcore$firstCustomY == Integer.MIN_VALUE) {
                cloudcore$firstCustomY = y;
            }
            int spacing = BossBarSurfaceAdapter.spacing();
            if (spacing > 0) {
                adjustedY = cloudcore$firstCustomY + index * spacing;
            }
        }
        if (BossBarSurfaceAdapter.render(graphics, x, adjustedY, event)) {
            return;
        }
        original.call(overlay, graphics, x, adjustedY, event);
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;"
                            + "getName()Lnet/minecraft/network/chat/Component;"))
    private Component cloudcore$stripBossTitle(LerpingBossEvent event) {
        if (BossBarSurfaceAdapter.skins(event.getName())) {
            return Component.empty();
        }
        return BossBarSurfaceAdapter.stripSentinel(event.getName());
    }
}
