package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import java.util.Objects;
import net.minecraft.client.Minecraft;

/**
 * Placeholder used until a surface implementation lands. It never opens and makes the
 * caller report {@code UI_READY(state=4)} instead of silently dropping the request.
 */
public final class UnsupportedSurfaceAdapter implements SurfaceAdapter {
    private final SurfaceType type;

    public UnsupportedSurfaceAdapter(SurfaceType type) {
        this.type = Objects.requireNonNull(type, "type");
    }

    @Override
    public SurfaceType type() {
        return type;
    }

    @Override
    public boolean canHandle(OpenRequest request, Minecraft minecraft) {
        return false;
    }

    @Override
    public void open(OpenRequest request, UiRuntime runtime) {
        throw new UnsupportedOperationException("Surface not implemented: " + type);
    }

    @Override
    public void close(String uiId, CloseReason reason) {
    }
}
