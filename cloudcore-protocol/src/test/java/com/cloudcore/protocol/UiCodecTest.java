package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudcore.api.BindMode;
import com.cloudcore.api.RenderMode;
import com.cloudcore.api.SurfaceType;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class UiCodecTest {

    @Test
    void openRoundTrips() {
        UiOpen open = new UiOpen("shop", 3, SurfaceType.CONTAINER, BindMode.NEXT_CONTAINER,
                0xDEADBEEF, RenderMode.SCREEN, 0x05, "chest");
        byte[] body = UiCodec.encodeOpen(open, Limits.S2C);
        assertEquals(open, UiCodec.decodeOpen(body, Limits.S2C));
    }

    @Test
    void openGoldenVectorIsStable() {
        UiOpen open = new UiOpen("a", 1, SurfaceType.CUSTOM_SCREEN, BindMode.NONE,
                1, RenderMode.SCREEN, 0, "");
        assertEquals("016101000000000001000000",
                HexFormat.of().formatHex(UiCodec.encodeOpen(open, Limits.S2C)));
    }

    @Test
    void readyRoundTripsUnsupportedState() {
        UiReady ready = new UiReady("shop", 3, 42, SurfaceType.HUD,
                UiReadyState.SURFACE_UNSUPPORTED, "HUD not implemented");
        byte[] body = UiCodec.encodeReady(ready, Limits.C2S);
        assertEquals(ready, UiCodec.decodeReady(body, Limits.C2S));
    }

    @Test
    void rejectsUnknownSurfaceId() {
        byte[] body = UiCodec.encodeOpen(new UiOpen("a", 1, SurfaceType.CUSTOM_SCREEN,
                BindMode.NONE, 1, RenderMode.SCREEN, 0, ""), Limits.S2C);
        body[3] = (byte) 0x7F;
        assertThrows(ProtocolException.class, () -> UiCodec.decodeOpen(body, Limits.S2C));
    }
}
