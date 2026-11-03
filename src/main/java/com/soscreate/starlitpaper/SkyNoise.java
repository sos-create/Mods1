package com.soscreate.starlitpaper;

/** Small deterministic value-noise helpers ported from Starlit's shader noise functions. */
final class SkyNoise {
    private SkyNoise() { }

    static double hash(long x, long y, long z) {
        long h = x * 0x8da6b343L ^ y * 0xd8163841L ^ z * 0xcb1ab31fL;
        h ^= h >>> 15; h *= 0x2c1b3c6dL;
        h ^= h >>> 12; h *= 0x297a2d39L;
        h ^= h >>> 15;
        return (h >>> 11) * 0x1.0p-53;
    }

    static double noise(double x, double y, double z) {
        long ix=(long)Math.floor(x), iy=(long)Math.floor(y), iz=(long)Math.floor(z);
        double fx=smooth(fract(x)), fy=smooth(fract(y)), fz=smooth(fract(z));
        double a=lerp(hash(ix,iy,iz),hash(ix+1,iy,iz),fx);
        double b=lerp(hash(ix,iy+1,iz),hash(ix+1,iy+1,iz),fx);
        double c=lerp(hash(ix,iy,iz+1),hash(ix+1,iy,iz+1),fx);
        double d=lerp(hash(ix,iy+1,iz+1),hash(ix+1,iy+1,iz+1),fx);
        return lerp(lerp(a,b,fy),lerp(c,d,fy),fz);
    }

    static double fbm(double x, double y, double z, int octaves) {
        double sum=0, amp=.5, norm=0;
        for(int i=0;i<octaves;i++) {
            sum += noise(x,y,z)*amp; norm += amp;
            x=x*2.03+1.7; y=y*2.03+9.2; z=z*2.03+3.4; amp*=.5;
        }
        return norm==0?0:sum/norm;
    }

    static double smooth(double t) { return t*t*(3-2*t); }
    static double fract(double v) { return v-Math.floor(v); }
    static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    static double clamp(double v,double lo,double hi) { return Math.max(lo,Math.min(hi,v)); }
}
