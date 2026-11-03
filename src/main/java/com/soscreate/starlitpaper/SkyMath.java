package com.soscreate.starlitpaper;

final class SkyMath {
    private SkyMath() { }
    static int clamp(int value,int min,int max) { return Math.max(min,Math.min(max,value)); }
    static double clamp(double value,double min,double max) { return Math.max(min,Math.min(max,value)); }
    static boolean isNight(long time) { long t=Math.floorMod(time,24000L); return t>=13000L || t<1000L; }
    static boolean isDusk(long time) { long t=Math.floorMod(time,24000L); return t>=11500L && t<14000L; }
    static boolean isDawn(long time) { long t=Math.floorMod(time,24000L); return t>=22000L && t<24000L; }
    static long mixSeed(long seed,long salt) {
        long v=seed^salt; v^=v>>>30; v*=0xbf58476d1ce4e5b9L;
        v^=v>>>27; v*=0x94d049bb133111ebL; return v^(v>>>31);
    }
    static double smoothStep(double value) { double t=clamp(value,0,1); return t*t*(3-2*t); }
}
