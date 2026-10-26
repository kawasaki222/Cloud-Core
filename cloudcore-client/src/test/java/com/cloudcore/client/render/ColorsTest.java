package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ColorsTest {
    private static final int FALLBACK = 0xFF123456;

    @Test
    void parsesEightDigitArgb() {
        assertEquals(0x80FF8800, Colors.parse("#80FF8800", FALLBACK));
    }

    @Test
    void parsesSixDigitRgbAsOpaque() {
        assertEquals(0xFFFF8800, Colors.parse("#FF8800", FALLBACK));
    }

    @Test
    void acceptsHexWithoutTheHash() {
        assertEquals(0xFF00FF00, Colors.parse("00FF00", FALLBACK));
    }

    @Test
    void fallsBackForTokensNamesAndMalformedValues() {
        assertEquals(FALLBACK, Colors.parse("tx:accent", FALLBACK));
        assertEquals(FALLBACK, Colors.parse("red", FALLBACK));
        assertEquals(FALLBACK, Colors.parse("#12345", FALLBACK));
        assertEquals(FALLBACK, Colors.parse("#GGGGGG", FALLBACK));
        assertEquals(FALLBACK, Colors.parse(null, FALLBACK));
        assertEquals(FALLBACK, Colors.parse(42, FALLBACK));
    }
}
