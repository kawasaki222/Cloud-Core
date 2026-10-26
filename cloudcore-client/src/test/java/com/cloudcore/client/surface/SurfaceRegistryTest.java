package com.cloudcore.client.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudcore.api.BindMode;
import com.cloudcore.api.RenderMode;
import com.cloudcore.api.SurfaceType;
import org.junit.jupiter.api.Test;

class SurfaceRegistryTest {

    @Test
    void defaultRegistryCoversEverySurface() {
        SurfaceRegistry registry = SurfaceRegistry.createDefault();
        for (SurfaceType type : SurfaceType.values()) {
            assertTrue(registry.find(type).isPresent(), type.toString());
        }
        assertInstanceOf(CustomScreenAdapter.class,
                registry.get(SurfaceType.CUSTOM_SCREEN));
        for (SurfaceType type : SurfaceType.values()) {
            if (type != SurfaceType.CUSTOM_SCREEN) {
                assertInstanceOf(UnsupportedSurfaceAdapter.class, registry.get(type));
            }
        }
    }

    @Test
    void supportsCustomScreenAndRejectsUnimplementedSurfaces() {
        SurfaceRegistry registry = SurfaceRegistry.createDefault();
        OpenRequest custom = request(SurfaceType.CUSTOM_SCREEN);
        OpenRequest hud = request(SurfaceType.HUD);

        assertTrue(registry.canHandle(custom, null));
        assertFalse(registry.canHandle(hud, null));
    }

    @Test
    void duplicateRegistrationIsRejected() {
        SurfaceRegistry registry = new SurfaceRegistry();
        registry.register(new UnsupportedSurfaceAdapter(SurfaceType.HUD));
        assertThrows(IllegalStateException.class,
                () -> registry.register(new UnsupportedSurfaceAdapter(SurfaceType.HUD)));
    }

    private static OpenRequest request(SurfaceType type) {
        return new OpenRequest(type, "ui", 1, 0, BindMode.NONE, RenderMode.SCREEN, 0, "");
    }
}
