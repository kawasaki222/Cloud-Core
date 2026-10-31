package com.cloudcore.client.mixin;

import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the protected visibility switch of the vanilla recipe book panel. */
@Mixin(RecipeBookComponent.class)
public interface RecipeBookComponentAccessor {

    @Invoker("setVisible")
    void cloudcore$setVisible(boolean visible);
}
