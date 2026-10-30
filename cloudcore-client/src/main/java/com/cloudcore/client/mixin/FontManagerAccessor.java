package com.cloudcore.client.mixin;

import java.util.Map;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only view of the loaded font sets, used to build tooltip section fonts. */
@Mixin(FontManager.class)
public interface FontManagerAccessor {

    @Accessor("fontSets")
    Map<Identifier, FontSet> cloudcore$fontSets();
}
