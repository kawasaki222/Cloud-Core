package com.cloudcore.client.mixin;

import com.cloudcore.client.CloudCoreClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Global mouse entry points for the tooltip scrollbar: the wheel, the cursor position and the
 * button press/release. Tooltip scrolling must be captured here rather than on {@code Screen}
 * because subclasses may override the screen-level methods, and the tooltip is drawn outside
 * any screen's widget tree.
 *
 * <p>1.21.11 names them {@code onMove(long, double, double)} and
 * {@code onButton(long, MouseButtonInfo, int)}; the cursor coordinates are window pixels and
 * are scaled here, exactly as the vanilla handler does.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onScroll", at = @At("HEAD"))
    private void cloudcore$observeScroll(long window, double xOffset, double yOffset,
            CallbackInfo callback) {
        CloudCoreClient.onScroll(xOffset, yOffset);
    }

    @Inject(method = "onMove", at = @At("HEAD"))
    private void cloudcore$observeMove(long window, double x, double y, CallbackInfo callback) {
        net.minecraft.client.MouseHandler handler =
                (net.minecraft.client.MouseHandler) (Object) this;
        CloudCoreClient.onMouseMove(handler.getScaledXPos(
                net.minecraft.client.Minecraft.getInstance().getWindow()),
                handler.getScaledYPos(
                        net.minecraft.client.Minecraft.getInstance().getWindow()));
    }

    @Inject(method = "onButton", at = @At("HEAD"))
    private void cloudcore$observePress(long window,
            net.minecraft.client.input.MouseButtonInfo button, int action,
            CallbackInfo callback) {
        CloudCoreClient.onMouseButton(button.button(), action);
    }
}
