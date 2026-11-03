package com.soscreate.starlitpaper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkyMathTest {
    @Test
    void clampsConfigurationValues() {
        assertEquals(5, SkyMath.clamp(1, 5, 40));
        assertEquals(40, SkyMath.clamp(100, 5, 40));
        assertEquals(12, SkyMath.clamp(12, 5, 40));
    }

    @Test
    void identifiesNightAndTwilightIntervalsAcrossMidnight() {
        assertTrue(SkyMath.isNight(13_000));
        assertTrue(SkyMath.isNight(23_999));
        assertTrue(SkyMath.isNight(0));
        assertFalse(SkyMath.isNight(8_000));
        assertTrue(SkyMath.isDusk(12_000));
        assertTrue(SkyMath.isDawn(23_000));
        assertFalse(SkyMath.isDawn(10_000));
    }

    @Test
    void mixesSeedsDeterministically() {
        assertEquals(SkyMath.mixSeed(1234L, 5678L), SkyMath.mixSeed(1234L, 5678L));
        assertNotEquals(SkyMath.mixSeed(1234L, 5678L), SkyMath.mixSeed(1234L, 5679L));
    }

    @Test
    void smoothStepIsClampedAndHasExpectedEndpoints() {
        assertEquals(0.0, SkyMath.smoothStep(-1.0));
        assertEquals(0.0, SkyMath.smoothStep(0.0));
        assertEquals(0.5, SkyMath.smoothStep(0.5));
        assertEquals(1.0, SkyMath.smoothStep(1.0));
        assertEquals(1.0, SkyMath.smoothStep(2.0));
    }
}
