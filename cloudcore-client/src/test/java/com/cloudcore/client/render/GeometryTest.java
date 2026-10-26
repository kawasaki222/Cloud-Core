package com.cloudcore.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GeometryTest {

    @Test
    void pixelsPercentAndFill() {
        assertEquals(42, Geometry.resolve("42", 100, 0));
        assertEquals(50, Geometry.resolve("50%", 100, 0));
        assertEquals(100, Geometry.resolve("fill", 100, 0));
        assertEquals(100, Geometry.resolve("FILL", 100, 0));
    }

    @Test
    void missingOrUnparsableValuesFallBack() {
        assertEquals(7, Geometry.resolve(null, 100, 7));
        assertEquals(7, Geometry.resolve("", 100, 7));
        assertEquals(7, Geometry.resolve("  ", 100, 7));
        assertEquals(7, Geometry.resolve("wide", 100, 7));
        assertEquals(7, Geometry.resolve("12px", 100, 7));
    }
}
