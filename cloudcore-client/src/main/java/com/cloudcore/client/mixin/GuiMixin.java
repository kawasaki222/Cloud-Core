package com.cloudcore.client.mixin;

import com.cloudcore.client.surface.HudSurfaceAdapter;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets a Cloud HUD document replace vanilla HUD parts.
 *
 * <p>{@code surface_options.replace} names what the document draws itself:
 * {@code health} (hearts/armor/air), {@code food} (hunger), {@code xp} (experience bar) and
 * {@code hotbar}. Cancelling {@code renderHotbarAndDecorations} also hides the selected item
 * name and the attack indicator — a skin that replaces the hotbar replaces those too.
 *
 * <p>Without a document (or without {@code replace}) every method runs untouched, so vanilla
 * clients and plain HUD panels are unaffected.
 */
@Mixin(Gui.class)
public abstract class GuiMixin {

    @Inject(method = "renderPlayerHealth", at = @At("HEAD"), cancellable = true)
    private void cloudcore$replaceHealth(GuiGraphics graphics, CallbackInfo info) {
        if (HudSurfaceAdapter.replaces("health") || HudSurfaceAdapter.replaces("food")) {
            info.cancel();
        }
    }

    @Inject(method = "renderHotbarAndDecorations", at = @At("HEAD"), cancellable = true)
    private void cloudcore$replaceHotbar(GuiGraphics graphics, DeltaTracker delta,
            CallbackInfo info) {
        if (HudSurfaceAdapter.replaces("hotbar") || HudSurfaceAdapter.replaces("xp")) {
            info.cancel();
        }
    }
}
