package com.cloudcore.client.mixin;

import com.cloudcore.client.CloudCoreClient;
import com.cloudcore.client.surface.TooltipSurfaceAdapter;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tooltip hook for 1.21.11. Container screens call
 * {@code setTooltipForNextFrame}, which stores a lambda; the frame flush runs the
 * private {@code method_71277}, not the public {@code renderTooltip}. Both entries
 * are hooked so direct and deferred tooltips share one adapter.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {

    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;"
            + "IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;"
            + "Lnet/minecraft/resources/Identifier;)V", at = @At("HEAD"), cancellable = true)
    private void cloudcore$observeTooltip(Font font, List<ClientTooltipComponent> lines,
            int x, int y, ClientTooltipPositioner positioner, Identifier texture,
            CallbackInfo callback) {
        cloudcore$render(font, lines, x, y, positioner, callback);
    }

    @Inject(method = "method_71277(Lnet/minecraft/client/gui/Font;Ljava/util/List;"
            + "IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;"
            + "Lnet/minecraft/resources/Identifier;)V", at = @At("HEAD"), cancellable = true)
    private void cloudcore$observeDeferredTooltip(Font font, List<ClientTooltipComponent> lines,
            int x, int y, ClientTooltipPositioner positioner, Identifier texture,
            CallbackInfo callback) {
        cloudcore$render(font, lines, x, y, positioner, callback);
    }

    private void cloudcore$render(Font font, List<ClientTooltipComponent> lines,
            int x, int y, ClientTooltipPositioner positioner, CallbackInfo callback) {
        GuiGraphics graphics = (GuiGraphics) (Object) this;
        if (TooltipSurfaceAdapter.render(graphics, font, lines, x, y, positioner)) {
            callback.cancel();
            return;
        }
        CloudCoreClient.onTooltip(lines.size());
    }
}
