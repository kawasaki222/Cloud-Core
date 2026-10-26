package com.cloudcore.api;

import java.util.Objects;

public record OpenOptions(
        SurfaceType surface,
        BindMode bindMode,
        RenderMode renderMode,
        String theme,
        boolean closeExisting) {

    public OpenOptions {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(bindMode, "bindMode");
        Objects.requireNonNull(renderMode, "renderMode");
    }

    public static OpenOptions defaults(SurfaceType surface) {
        return new OpenOptions(surface, BindMode.NONE, RenderMode.SCREEN, null, true);
    }
}
