package com.soscreate.starlitpaper;

final class SkyMath {
    private SkyMath() { }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static boolean isNight(long worldTime) {
        long time = Math.floorMod(worldTime, 24_000L);
        return time >= 13_000L || time < 1_000L;
    }

    static boolean isDusk(long worldTime) {
        long time = Math.floorMod(worldTime, 24_000L);
        return time >= 11_500L && time < 14_000L;
    }

    static boolean isDawn(long worldTime) {
        long time = Math.floorMod(worldTime, 24_000L);
        return time >= 22_000L && time < 24_000L;
    }

    static long mixSeed(long seed, long salt) {
        long value = seed ^ salt;
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    static double smoothStep(double value) {
        double t = Math.max(0.0, Math.min(1.0, value));
        return t * t * (3.0 - 2.0 * t);
    }
}
