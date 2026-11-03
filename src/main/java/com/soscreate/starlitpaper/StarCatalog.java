package com.soscreate.starlitpaper;

import java.util.ArrayList;
import java.util.List;

/**
 * Recreates Starlit's deterministic star catalogue algorithm.
 * The original mod uses the same 38% galactic-plane / 62% all-sky distribution,
 * luminosity curve, size curve and six stellar colour classes.
 */
final class StarCatalog {
    static final long SEED = 91_519_505L;
    private static final double TAU = Math.PI * 2.0;
    private static final double[] NORMAL = normalize(0.35, 0.30, 0.887);
    private static final double[] E1 = normalize(cross(0, 1, 0, NORMAL));
    private static final double[] E2 = cross(NORMAL, E1);
    private static final double[][] CLASSES = {
            {0.66, 0.76, 1.00, 0.04},
            {0.80, 0.86, 1.00, 0.10},
            {0.97, 0.97, 1.00, 0.18},
            {1.00, 0.95, 0.84, 0.22},
            {1.00, 0.84, 0.64, 0.24},
            {1.00, 0.70, 0.50, 0.22}
    };

    private StarCatalog() { }

    static List<Star> generate(int count, boolean colored, long seed) {
        Rng rng = new Rng(seed);
        List<Star> stars = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double[] direction;
            if (rng.nextFloat() < 0.38f) {
                double angle = rng.range(0, TAU);
                double latitude = rng.gaussian() * 0.11;
                direction = inPlane(angle, latitude);
            } else {
                direction = rng.unitVector();
            }
            float random = rng.nextFloat();
            float luminosity = (float)(0.3 + 0.7 * Math.pow(random, 6.0));
            float size = 0.21f + 0.5f * luminosity;
            if (luminosity > 0.7f) size *= 1.35f;
            double[] tint = colored ? pick(rng.nextFloat()) : new double[]{1, 1, 1, 1};
            float modulation = luminosity * (0.8f + 0.4f * rng.nextFloat());
            float phase = rng.nextFloat() * 0.98f;
            stars.add(new Star(direction[0], direction[1], direction[2], size,
                    (float)Math.min(1, tint[0] * modulation),
                    (float)Math.min(1, tint[1] * modulation),
                    (float)Math.min(1, tint[2] * modulation), phase));
        }
        return stars;
    }

    private static double[] pick(float value) {
        double total = 0;
        for (double[] stellarClass : CLASSES) {
            total += stellarClass[3];
            if (value < total) return stellarClass;
        }
        return CLASSES[CLASSES.length - 1];
    }

    private static double[] inPlane(double angle, double latitude) {
        double cosLat = Math.cos(latitude);
        return normalize(
                E1[0] * Math.cos(angle) * cosLat + E2[0] * Math.sin(angle) * cosLat + NORMAL[0] * Math.sin(latitude),
                E1[1] * Math.cos(angle) * cosLat + E2[1] * Math.sin(angle) * cosLat + NORMAL[1] * Math.sin(latitude),
                E1[2] * Math.cos(angle) * cosLat + E2[2] * Math.sin(angle) * cosLat + NORMAL[2] * Math.sin(latitude));
    }

    static double[] normalize(double x, double y, double z) {
        double length = Math.sqrt(x*x + y*y + z*z);
        if (length < 1.0e-9) return new double[]{0, 1, 0};
        return new double[]{x/length, y/length, z/length};
    }
    private static double[] normalize(double[] v) { return normalize(v[0], v[1], v[2]); }
    private static double[] cross(double ax, double ay, double az, double[] b) {
        return new double[]{ay*b[2]-az*b[1], az*b[0]-ax*b[2], ax*b[1]-ay*b[0]};
    }
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]};
    }

    record Star(double x, double y, double z, float size, float red, float green, float blue, float phase) { }

    private static final class Rng {
        private long state;
        Rng(long seed) { state = seed; }
        private static long mix(long value) {
            value = (value ^ (value >>> 30)) * -4658895280553007687L;
            value = (value ^ (value >>> 27)) * -7723592293110705685L;
            return value ^ (value >>> 31);
        }
        long nextLong() { state += -7046029254386353131L; return mix(state); }
        float nextFloat() { return (nextLong() >>> 40) * 0x1.0p-24f; }
        double range(double min, double max) { return min + (max-min)*nextFloat(); }
        double gaussian() {
            double u = Math.max(1.0e-9, nextFloat());
            double v = nextFloat();
            return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(TAU * v);
        }
        double[] unitVector() {
            double y = range(-1, 1);
            double angle = range(0, TAU);
            double horizontal = Math.sqrt(1-y*y);
            return new double[]{horizontal*Math.cos(angle), horizontal*Math.sin(angle), y};
        }
    }
}
