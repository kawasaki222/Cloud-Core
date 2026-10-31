package com.cloudcore.client.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the recipe book component so a Cloud inventory document can hide it. */
@Mixin(AbstractRecipeBookScreen.class)
public interface RecipeBookAccessor {

    @Accessor("recipeBookComponent")
    RecipeBookComponent<?> cloudcore$recipeBook();
}
