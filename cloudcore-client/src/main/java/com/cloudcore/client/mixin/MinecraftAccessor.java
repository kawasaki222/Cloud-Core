package com.cloudcore.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the font manager so tooltip sections can use a resource-pack font id. */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {

    @Accessor("fontManager")
    FontManager cloudcore$fontManager();
}
