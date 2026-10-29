package com.cloudcore.server.surface;

import com.cloudcore.api.OpenOptions;
import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.surface.SurfaceProvider;
import com.cloudcore.api.surface.SurfaceSession;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One provider per surface type. Addons register through
 * {@code com.cloudcore.bukkit.api.CloudCoreApi#registerSurfaceProvider}; the plugin consults
 * this registry before opening a page so a provider can bind its backing state and supply the
 * correlation token.
 */
public final class SurfaceProviderRegistry {
    private final Map<SurfaceType, SurfaceProvider> providers = new EnumMap<>(SurfaceType.class);

    public void register(SurfaceProvider provider) {
        Objects.requireNonNull(provider, "provider");
        SurfaceProvider previous = providers.putIfAbsent(provider.type(), provider);
        if (previous != null) {
            throw new IllegalStateException("Surface provider already registered: "
                    + provider.type());
        }
    }

    /** @return true when a provider was removed for that surface */
    public boolean unregister(SurfaceType type) {
        return type != null && providers.remove(type) != null;
    }

    public Optional<SurfaceProvider> find(SurfaceType type) {
        return Optional.ofNullable(providers.get(type));
    }

    public Optional<SurfaceSession> open(UUID playerId, String documentId, OpenOptions options) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(options, "options");
        return find(options.surface())
                .filter(SurfaceProvider::available)
                .flatMap(provider -> provider.open(playerId, documentId, options));
    }

    public void apply(SurfaceSession session) {
        Objects.requireNonNull(session, "session");
        find(session.surface()).ifPresent(provider -> provider.apply(session));
    }

    public void close(UUID playerId, String uiId) {
        for (SurfaceProvider provider : providers.values()) {
            provider.close(playerId, uiId);
        }
    }

    /** Drops every provider, for plugin disable. Does not call {@link SurfaceProvider#close}. */
    public void clear() {
        providers.clear();
    }
}
