package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class NineSliceTest {

    @Test
    void cornersKeepSizeAndCentreStretches() {
        List<NineSlice.Piece> pieces = NineSlice.split(10, 20, 100, 50, 16, 16,
                new int[]{4, 4, 4, 4});

        assertEquals(9, pieces.size());
        NineSlice.Piece topLeft = pieces.get(0);
        assertEquals(0, topLeft.srcX());
        assertEquals(4, topLeft.srcWidth());
        assertEquals(10, topLeft.dstX());
        assertEquals(4, topLeft.dstWidth());

        NineSlice.Piece centre = pieces.get(4);
        assertEquals(4, centre.srcX());
        assertEquals(8, centre.srcWidth());
        assertEquals(14, centre.dstX());
        assertEquals(92, centre.dstWidth());
        assertEquals(24, centre.dstY());
        assertEquals(42, centre.dstHeight());

        NineSlice.Piece bottomRight = pieces.get(8);
        assertEquals(12, bottomRight.srcX());
        assertEquals(106, bottomRight.dstX());
        assertEquals(66, bottomRight.dstY());
    }

    @Test
    void oversizedBordersFallBackToASingleDraw() {
        List<NineSlice.Piece> pieces = NineSlice.split(0, 0, 10, 10, 4, 4,
                new int[]{3, 3, 3, 3});
        assertEquals(1, pieces.size());
        assertEquals(10, pieces.get(0).dstWidth());
    }

    @Test
    void missingBordersDrawTheWholeTexture() {
        List<NineSlice.Piece> pieces = NineSlice.split(0, 0, 32, 32, 16, 16, null);
        assertEquals(1, pieces.size());
        assertNotNull(pieces.get(0));
        assertEquals(16, pieces.get(0).srcWidth());
        assertEquals(32, pieces.get(0).dstWidth());
    }
}
