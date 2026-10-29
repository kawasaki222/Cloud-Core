package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.document.UiDocument;
import net.minecraft.client.Minecraft;

/**
 * Client-side lifecycle for one surface type.
 */
public interface SurfaceAdapter {

    SurfaceType type();

    boolean canHandle(OpenRequest request, Minecraft minecraft);

    void open(OpenRequest request, UiRuntime runtime);

    /**
     * Replaces the live document in place after an {@code ELEMENT_UPDATE}. Returning false
     * tells the client to keep waiting for a full definition; surfaces whose layout depends on
     * the document (containers, tooltip chrome) do not support this yet.
     */
    default boolean update(String uiId, UiDocument document) {
        return false;
    }

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
