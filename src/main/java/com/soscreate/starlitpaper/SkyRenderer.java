package com.soscreate.starlitpaper;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Server-side visual recreation of Starlit's sky layers using normal Minecraft particles. */
final class SkyRenderer {
    private final JavaPlugin plugin;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private BukkitTask task;
    private long ticks;
    private int interval = 10;

    SkyRenderer(JavaPlugin plugin) { this.plugin = plugin; }

    void start() {
        stop();
        interval = SkyMath.clamp(plugin.getConfig().getInt("render-interval-ticks", 10), 5, 40);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, interval);
    }

    void reload() {
        states.clear();
        start();
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        states.clear();
    }

    void forceMeteor(Player player, boolean wish) {
        PlayerState state = stateFor(player);
        state.meteorStart = ticks + 1;
        state.meteorX = state.random.nextDouble() * 10.0 - 5.0;
        state.meteorFireball = plugin.getConfig().getBoolean("meteors.fireballs", true);
        state.meteorDuration = 18;
        player.sendMessage(wish
                ? org.bukkit.ChatColor.LIGHT_PURPLE + "Ты загадал желание под падающей звездой."
                : org.bukkit.ChatColor.GOLD + "Над тобой пролетает метеор!");
    }

    private void tick() {
        ticks += interval;
        if (!plugin.getConfig().getBoolean("enabled", true)) return;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead()) continue;
            try {
                render(player, stateFor(player));
            } catch (RuntimeException exception) {
                plugin.getLogger().fine("Could not render sky for " + player.getName()
                        + ": " + exception.getMessage());
            }
        }
        states.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }

    private PlayerState stateFor(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> {
            long seed = SkyMath.mixSeed(player.getWorld().getSeed(),
                    id.getMostSignificantBits() ^ id.getLeastSignificantBits());
            return new PlayerState(seed);
        });
    }

    private void render(Player player, PlayerState state) {
        World world = player.getWorld();
        World.Environment environment = world.getEnvironment();
        int maximum = SkyMath.clamp(plugin.getConfig().getInt(
                "performance.max-particles-per-pass", 210), 30, 600);
        Budget budget = new Budget(maximum);
        Location center = player.getEyeLocation().add(0, 11, 0);
        long time = world.getTime();
        boolean night = SkyMath.isNight(time);
        boolean dusk = SkyMath.isDusk(time);
        boolean dawn = SkyMath.isDawn(time);

        if (environment == World.Environment.NETHER) {
            renderNether(player, center, budget);
            return;
        }
        if (environment == World.Environment.THE_END) {
            if (plugin.getConfig().getBoolean("worlds.end-sky", true)) {
                renderEnd(player, center, budget, state);
            }
            return;
        }
        if (environment != World.Environment.NORMAL
                && !plugin.getConfig().getBoolean("worlds.overworld", true)) return;

        boolean showStars = night || (dusk && plugin.getConfig().getBoolean("stars.at-dusk", true));
        if (showStars && plugin.getConfig().getBoolean("stars.enabled", true)) {
            renderStars(player, center, budget, state);
        }
        if (!night && !dusk && !dawn) {
            if (plugin.getConfig().getBoolean("atmosphere.birds", true)) {
                renderBirds(player, center, budget);
            }
            if (plugin.getConfig().getBoolean("atmosphere.rainbows", true) && world.hasStorm()) {
                renderRainbow(player, center, budget);
            }
        }
        if (dusk || dawn) renderSunset(player, center, budget, time);
        if (night) {
            if (plugin.getConfig().getBoolean("milky-way.enabled", true)) renderMilkyWay(player, center, budget);
            if (plugin.getConfig().getBoolean("nebulae.enabled", true)) renderNebulae(player, center, budget);
            if (plugin.getConfig().getBoolean("galaxies.enabled", true)) renderGalaxies(player, center, budget);
            if (plugin.getConfig().getBoolean("planets.enabled", true)) renderPlanets(player, center, budget, time);
            if (plugin.getConfig().getBoolean("moon-glow.enabled", true)) renderMoonGlow(player, center, budget);
            if (plugin.getConfig().getBoolean("constellations.enabled", true)) renderConstellation(player, center, budget);
            if (plugin.getConfig().getBoolean("aurora.enabled", true)) renderAurora(player, center, budget, state);
            scheduleSkyEvents(player, state);
            renderSkyEvents(player, center, budget, state);
        }
        if ((dusk || dawn) && plugin.getConfig().getBoolean("atmosphere.halos", true)) {
            renderHalo(player, center, budget);
        }
    }

    private void renderStars(Player player, Location center, Budget budget, PlayerState state) {
        int configured = SkyMath.clamp(plugin.getConfig().getInt("stars.count", 135), 0, 500);
        int brightness = SkyMath.clamp(plugin.getConfig().getInt("stars.brightness", 100), 10, 200);
        int twinkle = SkyMath.clamp(plugin.getConfig().getInt("stars.twinkle", 50), 0, 100);
        double pulse = 1.0 - (twinkle / 100.0)
                * (0.5 + 0.5 * Math.sin((ticks + state.phase) * 0.11));
        int count = (int) Math.round(configured * pulse * (brightness / 100.0));
        if (count <= 0) return;
        if (!plugin.getConfig().getBoolean("stars.colored", true)) {
            cloud(player, center, budget, count, 17, 5.5, 17, rgb(225, 235, 255), 0.78f);
            return;
        }
        int white = (int) (count * 0.52);
        int blue = (int) (count * 0.17);
        int gold = (int) (count * 0.15);
        int pink = (int) (count * 0.09);
        int violet = Math.max(0, count - white - blue - gold - pink);
        cloud(player, center, budget, white, 17, 5.5, 17, rgb(235, 240, 255), 0.72f);
        cloud(player, center, budget, blue, 17, 5.5, 17, rgb(130, 185, 255), 0.78f);
        cloud(player, center, budget, gold, 17, 5.5, 17, rgb(255, 218, 145), 0.82f);
        cloud(player, center, budget, pink, 17, 5.5, 17, rgb(255, 160, 190), 0.78f);
        cloud(player, center, budget, violet, 17, 5.5, 17, rgb(195, 155, 255), 0.8f);
    }

    private void renderMilkyWay(Player player, Location center, Budget budget) {
        VectorBasis b = basis(player);
        for (int i = 0; i < 5; i++) {
            double lane = (i - 2) * 5.2;
            Location p = offset(center, b.forwardX * 19 + b.rightX * lane,
                    1.6 + i * 0.55, b.forwardZ * 19 + b.rightZ * lane);
            cloud(player, p, budget, Math.max(2, plugin.getConfig().getInt(
                    "milky-way.density", 34) / 5), 3, 1.8, 3,
                    i % 2 == 0 ? rgb(170, 175, 255) : rgb(210, 190, 255), 0.95f);
        }
        cloud(player, offset(center, b.forwardX * 20, 13.5, b.forwardZ * 20),
                budget, 9, 3.8, 1.6, 3.8, rgb(235, 225, 255), 1.0f);
    }

    private void renderNebulae(Player player, Location center, Budget budget) {
        VectorBasis b = basis(player);
        cloud(player, offset(center, b.forwardX * 17 - b.rightX * 11, 15,
                b.forwardZ * 17 - b.rightZ * 11), budget, 12, 2.7, 2.3, 2.7,
                rgb(130, 75, 210), 1.35f);
        cloud(player, offset(center, b.forwardX * 20 + b.rightX * 12, 14,
                b.forwardZ * 20 + b.rightZ * 12), budget, 12, 2.5, 2.2, 2.5,
                rgb(70, 155, 220), 1.25f);
        cloud(player, offset(center, b.forwardX * 23 + b.rightX * 2, 17,
                b.forwardZ * 23 + b.rightZ * 2), budget, 8, 2.2, 1.8, 2.2,
                rgb(230, 115, 175), 1.2f);
    }

    private void renderGalaxies(Player player, Location center, Budget budget) {
        VectorBasis b = basis(player);
        Location galaxy = offset(center, b.forwardX * 22 + b.rightX * 8, 17,
                b.forwardZ * 22 + b.rightZ * 8);
        cloud(player, galaxy, budget, 9, 1.1, 1.4, 1.1, rgb(245, 225, 255), 1.1f);
        cloud(player, galaxy.clone().add(0, 0.5, 0), budget, 5, 2, 0.7, 2,
                rgb(130, 120, 235), 0.95f);
    }

    private void renderPlanets(Player player, Location center, Budget budget, long time) {
        VectorBasis b = basis(player);
        double orbit = time / 24_000.0 * Math.PI * 2.0;
        double side = 9 + Math.sin(orbit) * 2;
        Location a = offset(center, b.forwardX * 21 + b.rightX * side,
                16 + Math.cos(orbit) * 1.3, b.forwardZ * 21 + b.rightZ * side);
        point(player, a, budget, rgb(255, 205, 145), 1.65f);
        ring(player, a, b, 1.35, 9, budget, rgb(175, 195, 255), 0.75f);
        Location second = offset(center, b.forwardX * 23 - b.rightX * 13, 12.5,
                b.forwardZ * 23 - b.rightZ * 13);
        point(player, second, budget, rgb(140, 210, 235), 1.3f);
    }

    private void renderMoonGlow(Player player, Location center, Budget budget) {
        VectorBasis b = basis(player);
        Location moon = offset(center, b.forwardX * 24 - b.rightX * 2, 17,
                b.forwardZ * 24 - b.rightZ * 2);
        ring(player, moon, b, 3, 14, budget, rgb(180, 205, 255), 0.62f);
        point(player, moon, budget, rgb(235, 240, 255), 1.5f);
    }

    private void renderConstellation(Player player, Location center, Budget budget) {
        VectorBasis b = basis(player);
        Location base = offset(center, b.forwardX * 21 + b.rightX * 3, 13,
                b.forwardZ * 21 + b.rightZ * 3);
        double[][] nodes = {{-3.6, 0}, {-1.6, 1.8}, {0.1, 0.9}, {1.9, 2.9},
                {3.5, 1.7}, {2.1, -0.8}, {0.1, -1.7}, {-1.9, -0.9}};
        for (double[] node : nodes) {
            point(player, offset(base, b.rightX * node[0], node[1],
                    b.rightZ * node[0]), budget, rgb(225, 235, 255), 1.0f);
        }
        if (plugin.getConfig().getBoolean("constellations.lines", false)) {
            int[][] edges = {{0,1},{1,2},{2,3},{3,4},{4,5},{5,6},{6,7}};
            for (int[] edge : edges) {
                double[] a = nodes[edge[0]], c = nodes[edge[1]];
                for (int i = 1; i < 5; i++) {
                    double t = i / 5.0;
                    double x = a[0] + (c[0] - a[0]) * t;
                    double y = a[1] + (c[1] - a[1]) * t;
                    point(player, offset(base, b.rightX * x, y, b.rightZ * x),
                            budget, rgb(130, 165, 230), 0.55f);
                }
            }
        }
    }

    private void renderAurora(Player player, Location center, Budget budget, PlayerState state) {
        int brightness = SkyMath.clamp(plugin.getConfig().getInt("aurora.brightness", 100), 0, 150);
        if (brightness == 0 || state.random.nextInt(100) > 88) return;
        String mode = plugin.getConfig().getString("aurora.mode", "cold");
        Color first = "warm".equalsIgnoreCase(mode) ? rgb(255, 120, 100) : rgb(75, 235, 145);
        Color second = "mixed".equalsIgnoreCase(mode) ? rgb(185, 115, 255) : rgb(80, 190, 220);
        VectorBasis b = basis(player);
        for (int i = 0; i < 3; i++) {
            double side = (i - 1) * 7.0;
            Location curtain = offset(center, b.forwardX * 21 + b.rightX * side,
                    20 + Math.sin((ticks + i * 19) * 0.025) * 1.5,
                    b.forwardZ * 21 + b.rightZ * side);
            cloud(player, curtain, budget, Math.max(2, brightness / 12),
                    2.4, 2.8, 1, i % 2 == 0 ? first : second, 1.1f);
        }
    }

    private void renderSunset(Player player, Location center, Budget budget, long time) {
        if (!plugin.getConfig().getBoolean("atmosphere.enabled", true)
                || !plugin.getConfig().getBoolean("atmosphere.sunsets", true)) return;
        VectorBasis b = basis(player);
        double intensity = SkyMath.isDusk(time) ? 1 : 0.55;
        Location horizon = offset(center, b.forwardX * 17, 1.8, b.forwardZ * 17);
        cloud(player, horizon, budget, (int) (22 * intensity), 10, 1.8, 3,
                rgb(255, 125, 82), 1.15f);
        cloud(player, horizon.clone().add(0, 2, 0), budget, (int) (16 * intensity),
                9, 1.2, 3, rgb(245, 135, 185), 1);
    }

    private void renderHalo(Player player, Location center, Budget budget) {
        VectorBasis b = basis(player);
        ring(player, offset(center, b.forwardX * 18, 11, b.forwardZ * 18),
                b, 5.2, 18, budget, rgb(255, 205, 150), 0.82f);
    }

    private void renderRainbow(Player player, Location center, Budget budget) {
        VectorBasis b = basis(player);
        Color[] colors = {rgb(255,75,75), rgb(255,155,65), rgb(255,225,75),
                rgb(90,220,120), rgb(80,160,255), rgb(120,90,240), rgb(205,100,235)};
        for (int band = 0; band < colors.length; band++) {
            for (int i = 0; i <= 5; i++) {
                double angle = Math.PI * i / 5.0;
                double side = Math.cos(angle) * (9 + band * 0.18);
                double height = 4 + Math.sin(angle) * (8 + band * 0.18);
                Location p = offset(center, b.forwardX * 19 + b.rightX * side,
                        height, b.forwardZ * 19 + b.rightZ * side);
                point(player, p, budget, colors[band], 0.9f);
            }
        }
    }

    private void renderBirds(Player player, Location center, Budget budget) {
        VectorBasis b = basis(player);
        double travel = (ticks % 240L) / 12.0;
        for (int flock = 0; flock < 2; flock++) {
            for (int bird = 0; bird < 5; bird++) {
                double side = (bird - 2) * 0.85 + flock * 3.2;
                double forward = 18 + travel - flock * 3;
                double height = 5.5 + Math.sin((ticks + bird * 7L) * 0.09) * 0.4;
                Location p = offset(center, b.forwardX * forward + b.rightX * side,
                        height, b.forwardZ * forward + b.rightZ * side);
                if (budget.take(1)) player.spawnParticle(Particle.CRIT, p, 0,
                        0, 0, 0, 0, null, true);
            }
        }
    }

    private void renderEnd(Player player, Location center, Budget budget, PlayerState state) {
        int count = Math.max(35, plugin.getConfig().getInt("stars.count", 135) / 2);
        cloud(player, center, budget, count, 18, 7, 18, rgb(205, 165, 255), 0.9f);
        cloud(player, center.clone().add(0, 3, 0), budget, 24, 13, 4, 13,
                rgb(135, 90, 220), 1.25f);
        if (plugin.getConfig().getBoolean("galaxies.enabled", true)) {
            cloud(player, center.clone().add(0, 8, 0), budget, 15, 7, 2, 7,
                    rgb(240, 190, 255), 1.05f);
        }
        renderSkyEvents(player, center, budget, state);
    }

    private void renderNether(Player player, Location center, Budget budget) {
        if (!plugin.getConfig().getBoolean("worlds.nether-effects", false)) return;
        cloud(player, center.clone().add(0, 8, 0), budget, 28, 17, 5, 17,
                rgb(255, 105, 45), 0.85f);
        cloud(player, center.clone().add(0, 12, 0), budget, 10, 12, 2, 12,
                rgb(255, 55, 35), 1);
    }

    private void scheduleSkyEvents(Player player, PlayerState state) {
        int rate = SkyMath.clamp(plugin.getConfig().getInt("meteors.shooting-stars", 50), 0, 100);
        if (state.nextMeteor == 0) state.nextMeteor = ticks + 100 + state.random.nextInt(250);
        if (rate > 0 && ticks >= state.nextMeteor && state.meteorStart < 0) {
            state.meteorStart = ticks;
            state.meteorX = state.random.nextDouble() * 12 - 6;
            state.meteorFireball = plugin.getConfig().getBoolean("meteors.fireballs", true)
                    && state.random.nextInt(100) < 22;
            state.meteorDuration = 16;
            int showerRate = SkyMath.clamp(plugin.getConfig().getInt("meteors.meteor-showers", 15), 0, 100);
            state.showerRemaining = state.random.nextInt(100) < showerRate ? 2 : 0;
            state.nextMeteor = ticks + 360 + (100 - rate) * 9L + state.random.nextInt(500);
            if (plugin.getConfig().getBoolean("meteors.announce", false)) {
                player.sendMessage(org.bukkit.ChatColor.GRAY + "На небе пролетает падающая звезда...");
            }
        }
        int cometRate = SkyMath.clamp(plugin.getConfig().getInt("meteors.comets", 10), 0, 100);
        if (state.nextComet == 0) state.nextComet = ticks + 1800 + state.random.nextInt(2200);
        if (cometRate > 0 && ticks >= state.nextComet && state.cometStart < 0) {
            state.cometStart = ticks;
            state.cometX = state.random.nextDouble() * 10 - 5;
            state.nextComet = ticks + 5000 + (100 - cometRate) * 75L + state.random.nextInt(4000);
        }
    }

    private void renderSkyEvents(Player player, Location center, Budget budget, PlayerState state) {
        VectorBasis b = basis(player);
        if (state.meteorStart >= 0) {
            long age = ticks - state.meteorStart;
            if (age >= state.meteorDuration) {
                state.meteorStart = -1;
                if (state.showerRemaining > 0) {
                    state.showerRemaining--;
                    state.meteorStart = ticks + 5;
                    state.meteorX = state.random.nextDouble() * 12 - 6;
                    state.meteorDuration = 16;
                }
            } else {
                double progress = age / (double) state.meteorDuration;
                drawStreak(player, center, budget, b, state.meteorX + progress * 13,
                        18 - progress * 8, 6, rgb(205, 230, 255),
                        state.meteorFireball ? Particle.FLAME : Particle.END_ROD, 1);
            }
        }
        if (state.cometStart >= 0) {
            long age = ticks - state.cometStart;
            if (age >= 180) {
                state.cometStart = -1;
            } else {
                double progress = age / 180.0;
                drawStreak(player, center, budget, b, state.cometX + progress * 10,
                        20 - progress * 5, 9, rgb(120, 225, 255), Particle.END_ROD, 0.5);
                if (budget.take(1)) {
                    Location head = offset(center, b.forwardX * 19 + b.rightX * (state.cometX + progress * 10),
                            20 - progress * 5, b.forwardZ * 19 + b.rightZ * (state.cometX + progress * 10));
                    player.spawnParticle(Particle.SOUL_FIRE_FLAME, head, 0,
                            0, 0, 0, 0, null, true);
                }
            }
        }
    }

    private void drawStreak(Player player, Location center, Budget budget, VectorBasis b,
                            double x, double y, int length, Color color,
                            Particle headParticle, double speed) {
        for (int i = 0; i < length; i++) {
            if (!budget.take(1)) return;
            double trail = i * 0.72;
            Location p = offset(center, b.forwardX * 19 + b.rightX * (x - trail),
                    y + trail * 0.34, b.forwardZ * 19 + b.rightZ * (x - trail));
            if (i == 0) player.spawnParticle(headParticle, p, 0, 0, 0, 0, speed, null, true);
            else player.spawnParticle(Particle.END_ROD, p, 0, 0, 0, 0, 0, null, true);
        }
    }

    private void ring(Player player, Location center, VectorBasis b, double radius, int count,
                      Budget budget, Color color, float size) {
        for (int i = 0; i < count; i++) {
            double angle = Math.PI * 2 * i / count;
            Location p = offset(center, b.rightX * Math.cos(angle) * radius,
                    Math.sin(angle) * radius, b.rightZ * Math.cos(angle) * radius);
            point(player, p, budget, color, size);
        }
    }

    private void cloud(Player player, Location center, Budget budget, int count,
                       double x, double y, double z, Color color, float size) {
        int actual = budget.takeCount(count);
        if (actual <= 0) return;
        player.spawnParticle(Particle.DUST, center, actual, x, y, z, 0,
                new Particle.DustOptions(color, size), true);
    }

    private void point(Player player, Location location, Budget budget, Color color, float size) {
        if (!budget.take(1)) return;
        player.spawnParticle(Particle.DUST, location, 0, 0, 0, 0, 0,
                new Particle.DustOptions(color, size), true);
    }

    private static Color rgb(int r, int g, int b) { return Color.fromRGB(r, g, b); }

    private static Location offset(Location center, double x, double y, double z) {
        Location result = center.clone().add(x, y, z);
        int max = result.getWorld().getMaxHeight() - 2;
        int min = result.getWorld().getMinHeight() + 2;
        result.setY(Math.max(min, Math.min(max, result.getY())));
        return result;
    }

    private static VectorBasis basis(Player player) {
        double yaw = Math.toRadians(player.getLocation().getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        return new VectorBasis(fx, fz, Math.cos(yaw), Math.sin(yaw));
    }

    private static final class Budget {
        private int remaining;
        private Budget(int maximum) { remaining = maximum; }
        private boolean take(int amount) {
            if (amount <= 0 || remaining < amount) return false;
            remaining -= amount;
            return true;
        }
        private int takeCount(int requested) {
            int actual = Math.max(0, Math.min(requested, remaining));
            remaining -= actual;
            return actual;
        }
    }

    private record VectorBasis(double forwardX, double forwardZ, double rightX, double rightZ) { }

    private static final class PlayerState {
        private final Random random;
        private final long phase;
        private long nextMeteor;
        private long meteorStart = -1;
        private long nextComet;
        private long cometStart = -1;
        private double meteorX, cometX;
        private int meteorDuration, showerRemaining;
        private boolean meteorFireball;

        private PlayerState(long seed) {
            random = new Random(seed);
            phase = Math.floorMod(seed, 10_000L);
        }
    }
}
