package com.cloudcore.client.mixin;

import com.cloudcore.client.render.RecipeBookSkin;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Replaces the vanilla recipe book background with the player's Cloud skin when one is
 * active ({@code recipe_book: skinned}). Only the background blit is redirected: the search
 * box, tab buttons, recipe grid and page arrows keep drawing and reacting exactly as vanilla,
 * so search, scrolling and "place recipe" are untouched.
 *
 * <p>The redirect sits on the {@code GuiGraphics.blit} call inside
 * {@code RecipeBookComponent#render}; if the signature changes the mixin fails to apply and
 * the vanilla background is used, which is the safe outcome.
 */
@Mixin(RecipeBookComponent.class)
public abstract class RecipeBookComponentMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;blit("
                            + "Lcom/mojang/blaze3d/pipeline/RenderPipeline;"
                            + "Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
    private void cloudcore$skinBackground(GuiGraphics graphics,
            com.mojang.blaze3d.pipeline.RenderPipeline pipeline,
            net.minecraft.resources.Identifier texture, int x, int y, float u, float v,
            int width, int height, int textureWidth, int textureHeight) {
        if (!RecipeBookSkin.drawSkinnedBackground(graphics, x, y, width, height)) {
            graphics.blit(pipeline, texture, x, y, u, v, width, height, textureWidth,
                    textureHeight);
        }
    }
}
