package com.cloudcore.server.surface;

import com.cloudcore.api.OpenOptions;
import com.cloudcore.api.SurfaceType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class SurfaceProviderRegistry {
    private final Map<SurfaceType, SurfaceProvider> providers = new EnumMap<>(SurfaceType.class);

    public void register(SurfaceProvider provider) {
        Objects.requireNonNull(provider, "provider");
        SurfaceProvider previous = providers.put(provider.type(), provider);
        if (previous != null) {
            throw new IllegalStateException("Surface provider already registered: " + provider.type());
        }
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
}
