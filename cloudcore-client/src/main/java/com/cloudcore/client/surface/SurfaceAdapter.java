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

    /**
     * Drops every document this surface holds. Called on disconnect/join so a reconnected
     * client never keeps the previous server's skin.
     */
    default void reset(CloseReason reason) {
    }

    default void render(RenderContext context) {
    }
}
