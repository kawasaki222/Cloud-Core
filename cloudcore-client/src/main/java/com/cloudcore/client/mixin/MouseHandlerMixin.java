package com.cloudcore.client.mixin;

import com.cloudcore.client.CloudCoreClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Global scroll entry. Tooltip scrolling must be captured here rather than on
 * {@code Screen} because subclasses may override the screen-level method.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onScroll", at = @At("HEAD"))
    private void cloudcore$observeScroll(long window, double xOffset, double yOffset,
            CallbackInfo callback) {
        CloudCoreClient.onScroll(xOffset, yOffset);
    }
}
