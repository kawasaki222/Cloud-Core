package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.client.screen.CloudScreen;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Opens an independent page through {@code Minecraft#setScreen}.
 */
public final class CustomScreenAdapter implements SurfaceAdapter {

    @Override
    public SurfaceType type() {
        return SurfaceType.CUSTOM_SCREEN;
    }

    @Override
    public boolean canHandle(OpenRequest request, Minecraft minecraft) {
        return request.surface() == SurfaceType.CUSTOM_SCREEN;
    }

    @Override
    public void open(OpenRequest request, UiRuntime runtime) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(runtime, "runtime");
        Minecraft minecraft = runtime.minecraft();
        minecraft.execute(() -> minecraft.setScreen(new CloudScreen(runtime.document())));
    }

    @Override
    public void close(String uiId, CloseReason reason) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            Screen screen = minecraft.screen;
            if (screen instanceof CloudScreen cloudScreen && cloudScreen.uiId().equals(uiId)) {
                minecraft.setScreen(null);
            }
        });
    }
}
