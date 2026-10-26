package com.cloudcore.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void definitionRoundTrips() {
        UiDefinition definition = new UiDefinition("shop", 2, 0,
                "{\"schema\":1}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] body = UiCodec.encodeDefinition(definition, Limits.S2C);
        UiDefinition decoded = UiCodec.decodeDefinition(body, Limits.S2C);
        assertEquals(definition.uiId(), decoded.uiId());
        assertEquals(definition.revision(), decoded.revision());
        assertEquals(definition.encoding(), decoded.encoding());
        org.junit.jupiter.api.Assertions.assertArrayEquals(definition.data(), decoded.data());
    }

    @Test
    void closeRoundTrips() {
        UiClose close = new UiClose("shop", 2);
        assertEquals(close, UiCodec.decodeClose(
                UiCodec.encodeClose(close, Limits.S2C), Limits.S2C));
    }

    @Test
    void closedRoundTrips() {
        UiClosed closed = new UiClosed("shop", UiClosedReason.PLAYER);
        assertEquals(closed, UiCodec.decodeClosed(
                UiCodec.encodeClosed(closed, Limits.C2S), Limits.C2S));
    }

    @Test
    void preservesUnknownSurfaceIdForForwardCompatibility() {
        byte[] body = UiCodec.encodeOpen(new UiOpen("a", 1, SurfaceType.CUSTOM_SCREEN,
                BindMode.NONE, 1, RenderMode.SCREEN, 0, ""), Limits.S2C);
        body[3] = (byte) 0x7F;
        UiOpen decoded = UiCodec.decodeOpen(body, Limits.S2C);
        assertEquals(0x7F, decoded.surfaceId());
        assertEquals(SurfaceType.UNKNOWN, decoded.surface());

        UiReady ready = new UiReady("a", 1, 1, decoded.surface(), decoded.surfaceId(),
                UiReadyState.SURFACE_UNSUPPORTED, "future surface");
        assertEquals(ready, UiCodec.decodeReady(
                UiCodec.encodeReady(ready, Limits.C2S), Limits.C2S));
    }

    @Test
    void unknownBindAndRenderModesDecodeInsteadOfFailing() {
        byte[] body = UiCodec.encodeOpen(new UiOpen("a", 1, SurfaceType.CUSTOM_SCREEN,
                BindMode.NONE, 1, RenderMode.SCREEN, 0, ""), Limits.S2C);
        // Layout: text uiId ("a" = 1 length byte + 1 data byte), varint revision,
        // surfaceId, bindMode, int token, renderMode, flags, text surfaceKey.
        int bindIndex = 2 + 1 + 1;
        int renderIndex = bindIndex + 1 + 4;
        body[bindIndex] = (byte) 0x7E;
        body[renderIndex] = (byte) 0x7D;

        UiOpen decoded = UiCodec.decodeOpen(body, Limits.S2C);

        assertEquals(BindMode.UNKNOWN, decoded.bindMode());
        assertEquals(RenderMode.UNKNOWN, decoded.renderMode());
    }
}
