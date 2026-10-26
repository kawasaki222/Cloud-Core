package com.cloudcore.client.mixin;

import com.cloudcore.client.CloudCoreClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void cloudcore$logScreenChange(Screen screen, CallbackInfo callback) {
        CloudCoreClient.onScreenChanged(screen);
    }
}
