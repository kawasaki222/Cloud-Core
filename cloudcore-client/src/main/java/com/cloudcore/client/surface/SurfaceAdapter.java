package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import net.minecraft.client.Minecraft;

/**
 * Client-side lifecycle for one surface type.
 */
public interface SurfaceAdapter {

    SurfaceType type();

    boolean canHandle(OpenRequest request, Minecraft minecraft);

    void open(OpenRequest request, UiRuntime runtime);

    void close(String uiId, CloseReason reason);

    default void render(RenderContext context) {
    }
}
