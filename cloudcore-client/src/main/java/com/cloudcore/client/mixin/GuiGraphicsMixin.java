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
 * Diagnostic hook for the single 1.21.11 tooltip entry point. The real adapter will
 * intercept and replace the background/scrollbar while leaving text drawing native.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {

    @Inject(method = "renderTooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;"
            + "IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;"
            + "Lnet/minecraft/resources/Identifier;)V", at = @At("HEAD"), cancellable = true)
    private void cloudcore$observeTooltip(Font font, List<ClientTooltipComponent> lines,
            int x, int y, ClientTooltipPositioner positioner, Identifier texture,
            CallbackInfo callback) {
        GuiGraphics graphics = (GuiGraphics) (Object) this;
        if (TooltipSurfaceAdapter.render(graphics, font, lines, x, y, positioner)) {
            callback.cancel();
            return;
        }
        CloudCoreClient.onTooltip(lines.size());
    }
}
