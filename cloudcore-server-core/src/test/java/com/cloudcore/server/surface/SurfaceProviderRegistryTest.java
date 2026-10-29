package com.cloudcore.server.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.OpenOptions;
import com.cloudcore.api.SurfaceType;
import com.cloudcore.api.surface.SurfaceProvider;
import com.cloudcore.api.surface.SurfaceSession;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SurfaceProviderRegistryTest {

    @Test
    void dispatchesOpenBySurface() {
        SurfaceProviderRegistry registry = new SurfaceProviderRegistry();
        registry.register(new FakeProvider(SurfaceType.CUSTOM_SCREEN, true));

        UUID player = UUID.randomUUID();
        OpenOptions options = OpenOptions.defaults(SurfaceType.CUSTOM_SCREEN);
        Optional<SurfaceSession> session = registry.open(player, "shop", options);

        assertTrue(session.isPresent());
        assertEquals("shop", session.orElseThrow().documentId());
    }

    @Test
    void unavailableProviderReturnsEmpty() {
        SurfaceProviderRegistry registry = new SurfaceProviderRegistry();
        registry.register(new FakeProvider(SurfaceType.CUSTOM_SCREEN, false));

        assertFalse(registry.open(UUID.randomUUID(), "shop",
                OpenOptions.defaults(SurfaceType.CUSTOM_SCREEN)).isPresent());
    }

    @Test
    void duplicateProviderIsRejected() {
        SurfaceProviderRegistry registry = new SurfaceProviderRegistry();
        registry.register(new FakeProvider(SurfaceType.HUD, true));
        assertThrows(IllegalStateException.class,
                () -> registry.register(new FakeProvider(SurfaceType.HUD, true)));
    }

    @Test
    void unregisterRemovesTheProvider() {
        SurfaceProviderRegistry registry = new SurfaceProviderRegistry();
        registry.register(new FakeProvider(SurfaceType.HUD, true));

        assertTrue(registry.unregister(SurfaceType.HUD));
        assertFalse(registry.unregister(SurfaceType.HUD));
        assertFalse(registry.find(SurfaceType.HUD).isPresent());
    }

    private record FakeProvider(SurfaceType type, boolean available) implements SurfaceProvider {
        @Override
        public Optional<SurfaceSession> open(UUID playerId, String documentId, OpenOptions options) {
            return Optional.of(new SurfaceSession(playerId, "ui-1", type, documentId, 1, 7));
        }

        @Override
        public void close(UUID playerId, String uiId) {
        }
    }
}
