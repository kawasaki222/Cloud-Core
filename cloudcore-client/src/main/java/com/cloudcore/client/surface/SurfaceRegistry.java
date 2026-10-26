package com.cloudcore.client.surface;

import com.cloudcore.api.SurfaceType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * Maps every surface type to exactly one adapter. Unimplemented surfaces are registered
 * with {@link UnsupportedSurfaceAdapter} so callers can answer {@code UI_READY(state=4)}
 * instead of silently ignoring a server request.
 */
public final class SurfaceRegistry {
    private final Map<SurfaceType, SurfaceAdapter> adapters = new EnumMap<>(SurfaceType.class);

    public SurfaceAdapter register(SurfaceAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        SurfaceAdapter previous = adapters.put(adapter.type(), adapter);
        if (previous != null) {
            throw new IllegalStateException("Surface already registered: " + adapter.type());
        }
        return adapter;
    }

    public Optional<SurfaceAdapter> find(SurfaceType type) {
        return Optional.ofNullable(adapters.get(type));
    }

    public SurfaceAdapter get(SurfaceType type) {
        SurfaceAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new IllegalStateException("Surface not registered: " + type);
        }
        return adapter;
    }

    public boolean canHandle(OpenRequest request, Minecraft minecraft) {
        return find(request.surface())
                .map(adapter -> adapter.canHandle(request, minecraft))
                .orElse(false);
    }

    public void close(String uiId, CloseReason reason) {
        for (SurfaceAdapter adapter : adapters.values()) {
            adapter.close(uiId, reason);
        }
    }

    public static SurfaceRegistry createDefault() {
        return createDefault(ignored -> {
        });
    }

    public static SurfaceRegistry createDefault(Consumer<String> onPlayerClosed) {
        SurfaceRegistry registry = new SurfaceRegistry();
        registry.register(new CustomScreenAdapter(onPlayerClosed));
        registry.register(new HudSurfaceAdapter());
        for (SurfaceType type : SurfaceType.values()) {
            if (type != SurfaceType.CUSTOM_SCREEN && type != SurfaceType.HUD) {
                registry.register(new UnsupportedSurfaceAdapter(type));
            }
        }
        return registry;
    }
}
