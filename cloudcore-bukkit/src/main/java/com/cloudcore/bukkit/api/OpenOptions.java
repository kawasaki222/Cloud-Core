package com.cloudcore.bukkit.api;

import com.cloudcore.api.BindMode;
import com.cloudcore.api.SurfaceType;

/**
 * Options for {@link CloudCoreApi#open}. Both fields are optional; a page opened without
 * options keeps the surface declared in its YAML and uses {@link BindMode#NONE}.
 *
 * <p>v1 opens custom screens only. Asking for another surface returns
 * {@link OpenResult#UNSUPPORTED_SURFACE} rather than silently opening the wrong thing.
 */
public record OpenOptions(SurfaceType surface, BindMode bindMode) {

    public static OpenOptions defaults() {
        return new OpenOptions(null, BindMode.NONE);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private SurfaceType surface;
        private BindMode bindMode = BindMode.NONE;

        private Builder() {
        }

        public Builder surface(SurfaceType surface) {
            this.surface = surface;
            return this;
        }

        public Builder bindMode(BindMode bindMode) {
            this.bindMode = bindMode == null ? BindMode.NONE : bindMode;
            return this;
        }

        public OpenOptions build() {
            return new OpenOptions(surface, bindMode);
        }
    }
}
