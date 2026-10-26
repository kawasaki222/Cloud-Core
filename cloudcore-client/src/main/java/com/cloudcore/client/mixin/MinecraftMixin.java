package com.cloudcore.client.mixin;

import com.cloudcore.client.CloudCoreClient;
import com.cloudcore.client.surface.ContainerSurfaceAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen cloudcore$replaceContainerScreen(Screen screen) {
        return ContainerSurfaceAdapter.replace(screen);
    }

    @Inject(method = "setScreen", at = @At("HEAD"))
    private void cloudcore$logScreenChange(Screen screen, CallbackInfo callback) {
        CloudCoreClient.onScreenChanged(screen);
    }
}
