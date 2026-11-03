package com.soscreate.starlitpaper;

import org.bukkit.Biome;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * High-detail Paper recreation of Starlit's procedural sky.
 *
 * Starlit itself draws full-screen GPU shader layers. Paper cannot inject those
 * shaders into vanilla clients, so this renderer reconstructs the same spatial
 * patterns as camera-relative, individually coloured particles. The star
 * catalogue distribution and stellar colour classes follow the original source.
 */
final class SkyRenderer {
    private static final double TAU = Math.PI * 2.0;
    private static final double[] GALAXY_NORMAL = StarCatalog.normalize(.35, .30, .887);
    private static final double[] GALAXY_E1 = StarCatalog.normalize(
            GALAXY_NORMAL[2], 0, -GALAXY_NORMAL[0]);
    private static final double[] GALAXY_E2 = cross(GALAXY_NORMAL, GALAXY_E1);
    private static final double[] GALAXY_CORE = StarCatalog.normalize(
            GALAXY_E1[0] * Math.cos(5.34) + GALAXY_E2[0] * Math.sin(5.34),
            GALAXY_E1[1] * Math.cos(5.34) + GALAXY_E2[1] * Math.sin(5.34),
            GALAXY_E1[2] * Math.cos(5.34) + GALAXY_E2[2] * Math.sin(5.34));
    private static final double[][] NEBULA_DIRECTIONS = {
            {.62,-.55,.56}, {-.48,-.71,-.52}, {.15,-.35,.92},
            {-.81,-.22,.54}, {.33,-.82,-.47}
    };
    private static final Color[] NEBULA_COLORS = {
            rgb(255,72,115), rgb(64,191,242), rgb(158,97,255),
            rgb(255,128,72), rgb(89,140,255)
    };
    private static final double[][] PLANET_DIRECTIONS = {
            {.23,.57,.79}, {-.66,.48,.57}, {.81,.31,-.49}, {-.34,.82,-.46}
    };

    private final JavaPlugin plugin;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private BukkitTask task;
    private long ticks;
    private int interval = 5;

    SkyRenderer(JavaPlugin plugin) { this.plugin = plugin; }

    void start() {
        stop();
        interval = SkyMath.clamp(plugin.getConfig().getInt("render-interval-ticks", 5), 2, 20);
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
        state.meteorX = state.random.nextDouble() * .7 - .35;
        state.meteorDuration = 34;
        state.meteorFireball = true;
        state.showerRemaining = 0;
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
                plugin.getLogger().fine("Sky rendering failed for " + player.getName()
                        + ": " + exception.getMessage());
            }
        }
        states.keySet().removeIf(id -> plugin.getServer().getPlayer(id) == null);
    }

    private PlayerState stateFor(Player player) {
        return states.compute(player.getUniqueId(), (id, old) -> {
            long seed = player.getWorld().getSeed() ^ StarCatalog.SEED;
            if (old == null || old.worldSeed != player.getWorld().getSeed()) {
                return new PlayerState(seed, player.getWorld().getSeed(),
                        StarCatalog.generate(SkyMath.clamp(plugin.getConfig().getInt("stars.count", 5000),
                                500, 12000), plugin.getConfig().getBoolean("stars.colored", true), seed));
            }
            return old;
        });
    }

    private void render(Player player, PlayerState state) {
        World world = player.getWorld();
        World.Environment environment = world.getEnvironment();
        int maximum = SkyMath.clamp(plugin.getConfig().getInt(
                "performance.max-particles-per-pass", 1050), 250, 2200);
        Budget budget = new Budget(maximum);
        Location eye = player.getEyeLocation();
        View view = view(player);

        if (environment == World.Environment.NETHER) {
            if (plugin.getConfig().getBoolean("worlds.nether-effects", false))
                renderNether(player, eye, view, budget, state);
            return;
        }
        if (environment == World.Environment.THE_END) {
            if (plugin.getConfig().getBoolean("worlds.end-sky", true))
                renderEnd(player, eye, view, budget, state, world);
            return;
        }
        if (environment == World.Environment.NORMAL
                && !plugin.getConfig().getBoolean("worlds.overworld", true)) return;

        long time = world.getTime();
        boolean night = SkyMath.isNight(time);
        boolean dusk = SkyMath.isDusk(time);
        boolean dawn = SkyMath.isDawn(time);
        boolean starsVisible = night || (dusk && plugin.getConfig().getBoolean("stars.at-dusk", true));

        if (starsVisible && plugin.getConfig().getBoolean("stars.enabled", true))
            renderStars(player, eye, view, budget, state, world);
        if (night) {
            if (plugin.getConfig().getInt("milky-way", 100) > 0) renderMilkyWay(player, eye, view, budget, state);
            if (plugin.getConfig().getBoolean("nebulae", true)) renderNebulae(player, eye, view, budget, state);
            if (plugin.getConfig().getBoolean("galaxies", true)) renderGalaxies(player, eye, view, budget, state);
            if (plugin.getConfig().getBoolean("planets", true)) renderPlanets(player, eye, view, budget, world);
            if (plugin.getConfig().getBoolean("moon-glow", true)) renderMoonGlow(player, eye, view, budget, world);
            if (plugin.getConfig().getBoolean("constellations.enabled", true)) renderConstellations(player, eye, view, budget, state);
            renderAurora(player, eye, view, budget, state, world);
            scheduleSkyEvents(player, state, world);
            renderSkyEvents(player, eye, view, budget, state);
        } else {
            if (dusk || dawn) renderSunset(player, eye, view, budget, world, time);
            if (plugin.getConfig().getBoolean("atmosphere.halos", true))
                renderDayHalo(player, eye, view, budget, world, time, state);
            renderBirds(player, eye, view, budget, state);
            renderRainbow(player, eye, view, budget, state, world);
        }
    }

    /** 5,000 deterministic stars by default, with the original 38% galactic concentration. */
    private void renderStars(Player player, Location eye, View view, Budget budget,
                             PlayerState state, World world) {
        int brightness = SkyMath.clamp(plugin.getConfig().getInt("stars.brightness", 100), 10, 200);
        int twinkle = SkyMath.clamp(plugin.getConfig().getInt("stars.twinkle", 50), 0, 100);
        boolean spikes = plugin.getConfig().getBoolean("stars.spikes", true);
        int starBudget = Math.min(650, budget.remaining() * 2 / 3);
        int rendered = 0;
        List<StarCatalog.Star> stars = state.stars;
        double radius = 38.0;
        for (int i = 0; i < stars.size() && rendered < starBudget; i++) {
            StarCatalog.Star star = stars.get(i);
            if (star.y() < -0.045) continue;
            double facing = dot(star.x(), star.y(), star.z(), view.x, view.y, view.z);
            if (facing < 0.20) continue;
            double phase = star.phase();
            double wave = Math.sin((ticks * .035) * (2.3 + phase * 3.1) + phase * 61.0) * .6
                    + Math.sin((ticks * .035) * (5.7 + phase * 2.3) + phase * 17.0) * .4;
            double twinkleFactor = 1.0 - twinkle / 100.0 * (.5 + .5 * wave);
            double lum = Math.min(1.0, Math.max(star.red(), Math.max(star.green(), star.blue()))
                    * twinkleFactor * brightness / 100.0);
            if (lum < .045) continue;
            Color color = rgb((int)(star.red()*255), (int)(star.green()*255), (int)(star.blue()*255));
            Location at = skyPoint(eye, star.x(), star.y(), star.z(), radius);
            float size = (float)SkyMath.clamp(.38 + star.size() * 1.25, .35, 1.75);
            dust(player, at, budget, color, size * (float)(.75 + lum*.25));
            rendered++;
            if (star.size() > .86 && lum > .35 && budget.remaining() > 2) {
                dust(player, at, budget, rgb(225,235,255), size + .9f);
                if (spikes && star.size() > 1.0 && budget.remaining() > 4) {
                    VectorBasis basis = tangent(star.x(), star.y(), star.z());
                    for (int arm = 0; arm < 4; arm++) {
                        double angle = arm * Math.PI / 2;
                        double ox = (basis.rx*Math.cos(angle)+basis.ux*Math.sin(angle))*.18;
                        double oy = (basis.ry*Math.cos(angle)+basis.uy*Math.sin(angle))*.18;
                        double oz = (basis.rz*Math.cos(angle)+basis.uz*Math.sin(angle))*.18;
                        dust(player, skyPoint(eye, star.x()+ox, star.y()+oy, star.z()+oz, radius),
                                budget, color, .35f);
                    }
                }
            }
        }
    }

    /** Procedural galactic band with a bright bulge, dusty dark lane and mottled arms. */
    private void renderMilkyWay(Player player, Location eye, View view, Budget budget, PlayerState state) {
        int strength = SkyMath.clamp(plugin.getConfig().getInt("milky-way", 100), 0, 200);
        if (strength == 0) return;
        Random random = new Random(state.worldSeed ^ 0x4d494c4b59574159L);
        for (int i = 0; i < 270 && budget.remaining() > 0; i++) {
            double angle = random.nextDouble()*TAU;
            double latitude = random.nextGaussian() * (.045 + .045*random.nextDouble());
            double cos = Math.cos(latitude), sin = Math.sin(latitude);
            double x = GALAXY_E1[0]*Math.cos(angle)*cos + GALAXY_E2[0]*Math.sin(angle)*cos + GALAXY_NORMAL[0]*sin;
            double y = GALAXY_E1[1]*Math.cos(angle)*cos + GALAXY_E2[1]*Math.sin(angle)*cos + GALAXY_NORMAL[1]*sin;
            double z = GALAXY_E1[2]*Math.cos(angle)*cos + GALAXY_E2[2]*Math.sin(angle)*cos + GALAXY_NORMAL[2]*sin;
            if (y < -.025 || dot(x,y,z,view.x,view.y,view.z) < .12) continue;
            double bulge = Math.exp((dot(x,y,z,GALAXY_CORE[0],GALAXY_CORE[1],GALAXY_CORE[2])-1)*5);
            double large = SkyNoise.fbm(x*3.2,y*3.2,z*3.2,4);
            double fine = SkyNoise.fbm(x*11+3.1,y*11+3.1,z*11+3.1,3);
            double clouds = (.45+1.1*large)*(.65+.7*fine);
            double laneOffset = latitude - .012 + .02*(SkyNoise.noise(x*4,y*4,z*4)-.5);
            double lane = Math.exp(-laneOffset*laneOffset / Math.pow(.028+.035*bulge,2));
            double dustLane = SkyNoise.smooth(SkyNoise.clamp((SkyNoise.fbm(x*7+7.7,y*7+7.7,z*7+7.7,4)-.3)/.42,0,1))*lane;
            if (random.nextDouble() < dustLane*.88) continue;
            double glow = clouds*(1-.88*dustLane)*(.3+1.4*bulge)*strength/100.0;
            if (random.nextDouble() > SkyNoise.clamp(glow*1.2,0,1)) continue;
            Color tint = mix(rgb(158,179,255),rgb(255,219,168),SkyNoise.clamp(bulge*.9+(large-.5)*.3,0,1));
            float size = (float)SkyMath.clamp(.45+glow*1.3,.35,1.5);
            dust(player,skyPoint(eye,x,y,z,37),budget,tint,size);
            if (bulge>.55 && random.nextDouble()<.35 && budget.remaining()>0)
                dust(player,skyPoint(eye,x,y,z,37),budget,rgb(255,235,205),size+0.6f);
        }
    }

    /** Five shader-defined nebula directions with multi-octave turbulent edges. */
    private void renderNebulae(Player player, Location eye, View view, Budget budget, PlayerState state) {
        Random random = new Random(state.worldSeed ^ 0x4e4542554c41454cL);
        for (int n=0;n<NEBULA_DIRECTIONS.length;n++) {
            double[] center=StarCatalog.normalize(NEBULA_DIRECTIONS[n][0],NEBULA_DIRECTIONS[n][1],NEBULA_DIRECTIONS[n][2]);
            if (center[1]<-.1 || dot(center[0],center[1],center[2],view.x,view.y,view.z)<.10) continue;
            VectorBasis b=tangent(center[0],center[1],center[2]);
            for(int i=0;i<48 && budget.remaining()>0;i++) {
                double a=random.nextDouble()*TAU;
                double r=Math.sqrt(random.nextDouble())*.32;
                double x=center[0]+r*(b.rx*Math.cos(a)+b.ux*Math.sin(a));
                double y=center[1]+r*(b.ry*Math.cos(a)+b.uy*Math.sin(a));
                double z=center[2]+r*(b.rz*Math.cos(a)+b.uz*Math.sin(a));
                double[] d=StarCatalog.normalize(x,y,z);
                if(d[1]<-.05 || dot(d[0],d[1],d[2],view.x,view.y,view.z)<.12) continue;
                double warp=SkyNoise.fbm(d[0]*8+n*4.3,d[1]*8+n*4.3,d[2]*8+n*4.3,2);
                double shape=SkyNoise.fbm(d[0]*14+warp*2.5,d[1]*14+warp*2.5,d[2]*14+warp*2.5,5);
                if(shape<.40) continue;
                double wisps=SkyNoise.fbm(d[0]*30,d[1]*30,d[2]*30,2);
                double alpha=SkyNoise.clamp((shape-.38)*2.5,0,1)*(.6+.6*wisps);
                if(random.nextDouble()>alpha) continue;
                Color color=mix(NEBULA_COLORS[n],rgb(235,220,255),.18+alpha*.28);
                dust(player,skyPoint(eye,d[0],d[1],d[2],35),budget,color,(float)(.65+alpha*1.2));
                if(alpha>.72 && budget.remaining()>0)
                    dust(player,skyPoint(eye,d[0],d[1],d[2],35),budget,color,1.9f);
            }
        }
    }

    /** Spiral and elliptical deep-sky objects, with a luminous core and irregular arms. */
    private void renderGalaxies(Player player, Location eye, View view, Budget budget, PlayerState state) {
        double[][] centers={{.78,.38,.50},{-.64,.58,.49},{-.22,.91,-.34},{.28,.46,-.84}};
        Random random=new Random(state.worldSeed ^ 0x47414c4158494553L);
        for(int g=0;g<centers.length;g++) {
            double[] c=StarCatalog.normalize(centers[g][0],centers[g][1],centers[g][2]);
            if(c[1]<-.08 || dot(c[0],c[1],c[2],view.x,view.y,view.z)<.12) continue;
            VectorBasis basis=tangent(c[0],c[1],c[2]);
            for(int i=0;i<66 && budget.remaining()>0;i++) {
                double r=Math.sqrt(random.nextDouble());
                double angle=random.nextDouble()*TAU;
                double twist=(g%2==0?5.0:3.5);
                double arm=angle+Math.log(r+.04)*twist;
                double clump=SkyNoise.noise(r*9*Math.cos(angle)+g*13,r*9*Math.sin(angle)+g*13,0);
                double armWeight=.5+.5*Math.cos(2*arm);
                if(random.nextDouble()>SkyNoise.clamp(.16+.84*Math.pow(armWeight,2.5)*(.6+.6*clump),.08,1)) continue;
                double spread=.035+r*.11;
                double x=c[0]+spread*r*(basis.rx*Math.cos(angle)+basis.ux*Math.sin(angle));
                double y=c[1]+spread*r*(basis.ry*Math.cos(angle)+basis.uy*Math.sin(angle));
                double z=c[2]+spread*r*(basis.rz*Math.cos(angle)+basis.uz*Math.sin(angle));
                double[] d=StarCatalog.normalize(x,y,z);
                if(d[1]<-.05) continue;
                Color tint=mix(rgb(150,178,255),rgb(255,220,164),Math.max(0,1-r*1.4));
                dust(player,skyPoint(eye,d[0],d[1],d[2],34),budget,tint,(float)(.5+(1-r)*.9));
            }
            for(int i=0;i<7 && budget.remaining()>0;i++)
                dust(player,skyPoint(eye,c[0],c[1],c[2],34),budget,rgb(255,235,204),1.5f+i*.12f);
        }
    }

    private void renderPlanets(Player player, Location eye, View view, Budget budget, World world) {
        double orbit=world.getTime()/24000.0*TAU;
        for(int p=0;p<PLANET_DIRECTIONS.length;p++) {
            double[] base=PLANET_DIRECTIONS[p];
            double angle=orbit*(p%2==0?1:-.7)+p*1.7;
            double[] d=StarCatalog.normalize(base[0]*Math.cos(angle)-base[2]*Math.sin(angle),
                    base[1]+Math.sin(angle*.7)*.12,
                    base[0]*Math.sin(angle)+base[2]*Math.cos(angle));
            if(d[1]<.05 || dot(d[0],d[1],d[2],view.x,view.y,view.z)<.2) continue;
            Location at=skyPoint(eye,d[0],d[1],d[2],36);
            Color[] colors={rgb(255,194,135),rgb(120,190,245),rgb(245,155,180),rgb(190,170,255)};
            for(int ring=0;ring<3;ring++) {
                dust(player,at,budget,colors[p],1.3f+ring*.5f);
            }
            VectorBasis b=tangent(d[0],d[1],d[2]);
            for(int i=0;i<20 && budget.remaining()>0;i++) {
                double a=TAU*i/20;
                double r=.045;
                double[] q=StarCatalog.normalize(d[0]+r*(b.rx*Math.cos(a)+b.ux*Math.sin(a)),
                        d[1]+r*(b.ry*Math.cos(a)+b.uy*Math.sin(a)),
                        d[2]+r*(b.rz*Math.cos(a)+b.uz*Math.sin(a)));
                dust(player,skyPoint(eye,q[0],q[1],q[2],36),budget,rgb(190,205,255),.45f);
            }
        }
    }

    private void renderMoonGlow(Player player, Location eye, View view, Budget budget, World world) {
        double angle=world.getTime()/24000.0*TAU;
        double[] moon=StarCatalog.normalize(-Math.cos(angle),.36,Math.sin(angle));
        if(dot(moon[0],moon[1],moon[2],view.x,view.y,view.z)<.15) return;
        Location at=skyPoint(eye,moon[0],moon[1],moon[2],37);
        VectorBasis b=tangent(moon[0],moon[1],moon[2]);
        for(int ring=0;ring<3;ring++) {
            double radius=.045+ring*.035;
            for(int i=0;i<28 && budget.remaining()>0;i++) {
                double a=TAU*i/28;
                double[] q=StarCatalog.normalize(moon[0]+radius*(b.rx*Math.cos(a)+b.ux*Math.sin(a)),
                        moon[1]+radius*(b.ry*Math.cos(a)+b.uy*Math.sin(a)),
                        moon[2]+radius*(b.rz*Math.cos(a)+b.uz*Math.sin(a)));
                dust(player,skyPoint(eye,q[0],q[1],q[2],37),budget,
                        ring==0?rgb(240,245,255):rgb(155,190,255),ring==0?.75f:.45f);
            }
        }
        dust(player,at,budget,rgb(245,248,255),2.1f);
    }

    private void renderConstellations(Player player, Location eye, View view, Budget budget, PlayerState state) {
        // A fixed celestial pattern; stars and connecting lines remain in the same sky positions.
        double[][] nodes={{-.82,.32,.46},{-.69,.45,.56},{-.52,.38,.76},{-.38,.56,.73},
                {-.21,.48,.85},{-.06,.62,.78},{.12,.53,.84},{.28,.65,.70}};
        for(int i=0;i<nodes.length;i++) {
            double[] d=StarCatalog.normalize(nodes[i][0],nodes[i][1],nodes[i][2]);
            if(dot(d[0],d[1],d[2],view.x,view.y,view.z)<.12) continue;
            Location p=skyPoint(eye,d[0],d[1],d[2],37);
            dust(player,p,budget,rgb(215,230,255),1.25f);
            dust(player,p,budget,rgb(255,255,255),.55f);
        }
        if(plugin.getConfig().getBoolean("constellations.lines",false)) {
            for(int i=0;i<nodes.length-1;i++) {
                double[] a=nodes[i],b=nodes[i+1];
                for(int j=1;j<8 && budget.remaining()>0;j++) {
                    double t=j/8.0;
                    double[] d=StarCatalog.normalize(a[0]+(b[0]-a[0])*t,
                            a[1]+(b[1]-a[1])*t,a[2]+(b[2]-a[2])*t);
                    if(d[1]>.0 && dot(d[0],d[1],d[2],view.x,view.y,view.z)>.12)
                        dust(player,skyPoint(eye,d[0],d[1],d[2],37),budget,rgb(100,155,235),.28f);
                }
            }
        }
    }

    private void renderAurora(Player player, Location eye, View view, Budget budget,
                              PlayerState state, World world) {
        String mode=plugin.getConfig().getString("aurora.mode","cold");
        if(mode==null || mode.equalsIgnoreCase("off")) return;
        boolean cold=isColdBiome(player.getLocation().getBlock().getBiome());
        if(mode.equalsIgnoreCase("cold") && !cold && !world.isThundering()) return;
        int brightness=SkyMath.clamp(plugin.getConfig().getInt("aurora.brightness",100),0,200);
        if(brightness==0) return;
        String palette=plugin.getConfig().getString("aurora.colors","classic");
        Color low,mid,high;
        switch(palette==null?"classic":palette.toLowerCase()) {
            case "vivid" -> {low=rgb(30,105,255);mid=rgb(40,255,145);high=rgb(190,90,255);}
            case "crimson" -> {low=rgb(105,20,80);mid=rgb(245,45,90);high=rgb(255,160,95);}
            case "ice" -> {low=rgb(35,110,190);mid=rgb(80,220,255);high=rgb(225,250,255);}
            default -> {low=rgb(30,105,85);mid=rgb(55,220,135);high=rgb(120,245,205);}
        }
        VectorBasis b=tangent(view.x,Math.max(.2,view.y),view.z);
        double baseYaw=Math.atan2(view.z,view.x);
        for(int ribbon=0;ribbon<4;ribbon++) {
            for(int i=0;i<42 && budget.remaining()>0;i++) {
                double u=i/41.0;
                double wave=Math.sin(u*7.2+ribbon*1.7+ticks*.012)*.08
                        +Math.sin(u*17.0-ribbon+ticks*.006)*.025;
                double side=(u-.5)*1.0+(ribbon-1.5)*.15;
                double elevation=.36+wave+Math.sin(u*Math.PI)*.18;
                double az=baseYaw+side;
                double[] d=StarCatalog.normalize(Math.cos(elevation)*Math.cos(az),
                        Math.sin(elevation),Math.cos(elevation)*Math.sin(az));
                if(dot(d[0],d[1],d[2],view.x,view.y,view.z)<-.1) continue;
                double ray=SkyNoise.noise(u*95+ticks*.018,ribbon*7,ticks*.003);
                double patch=SkyNoise.fbm(u*5+ticks*.003,ribbon*11,0,2);
                if(ray<.18 || patch<.27) continue;
                Color c=mix(low,mid,SkyNoise.clamp(u*.7+wave*2,0,1));
                c=mix(c,high,SkyNoise.clamp(elevation*.5,0,.6));
                dust(player,skyPoint(eye,d[0],d[1],d[2],34),budget,c,
                        (float)(.55+brightness/100.0*.55));
                if(i%5==0 && budget.remaining()>0)
                    dust(player,skyPoint(eye,d[0],d[1],d[2],34),budget,high,1.15f);
            }
        }
    }

    private void renderSunset(Player player, Location eye, View view, Budget budget, World world, long time) {
        if(!plugin.getConfig().getBoolean("atmosphere.enabled",true)
                || !plugin.getConfig().getBoolean("atmosphere.sunsets",true)) return;
        double phase=time/24000.0*TAU;
        double[] sun=StarCatalog.normalize(Math.cos(phase),Math.sin(phase),.12);
        VectorBasis b=tangent(sun[0],sun[1],sun[2]);
        for(int i=0;i<90 && budget.remaining()>0;i++) {
            double a=TAU*i/90;
            double r=.16+Math.sin(a*3+time*.001)*.04;
            double[] d=StarCatalog.normalize(sun[0]+r*(b.rx*Math.cos(a)+b.ux*Math.sin(a)),
                    sun[1]+r*(b.ry*Math.cos(a)+b.uy*Math.sin(a)),
                    sun[2]+r*(b.rz*Math.cos(a)+b.uz*Math.sin(a)));
            if(d[1]<-.03 || dot(d[0],d[1],d[2],view.x,view.y,view.z)<.10) continue;
            double blend=(Math.sin(a)+1)*.5;
            dust(player,skyPoint(eye,d[0],d[1],d[2],35),budget,
                    mix(rgb(255,92,48),rgb(255,170,125),blend),.7f);
        }
    }

    private void renderDayHalo(Player player, Location eye, View view, Budget budget,
                               World world, long time, PlayerState state) {
        double phase=time/24000.0*TAU;
        double[] sun=StarCatalog.normalize(Math.cos(phase),Math.sin(phase),.12);
        if(sun[1]<-.15 || dot(sun[0],sun[1],sun[2],view.x,view.y,view.z)<.1) return;
        VectorBasis b=tangent(sun[0],sun[1],sun[2]);
        for(int i=0;i<72 && budget.remaining()>0;i++) {
            double a=TAU*i/72;
            double r=.34;
            double[] d=StarCatalog.normalize(sun[0]+r*(b.rx*Math.cos(a)+b.ux*Math.sin(a)),
                    sun[1]+r*(b.ry*Math.cos(a)+b.uy*Math.sin(a)),
                    sun[2]+r*(b.rz*Math.cos(a)+b.uz*Math.sin(a)));
            if(d[1]<-.02 || dot(d[0],d[1],d[2],view.x,view.y,view.z)<.12) continue;
            Color c=mix(rgb(255,160,105),rgb(185,215,255),i/72.0);
            dust(player,skyPoint(eye,d[0],d[1],d[2],35),budget,c,.5f);
        }
    }

    private void renderRainbow(Player player, Location eye, View view, Budget budget,
                               PlayerState state, World world) {
        if(!plugin.getConfig().getBoolean("atmosphere.rainbows",true)) return;
        if(world.hasStorm()) { state.lastRainTick=ticks; return; }
        if(state.lastRainTick<0 || ticks-state.lastRainTick>6000) return;
        double phase=world.getTime()/24000.0*TAU;
        double[] center=StarCatalog.normalize(-Math.cos(phase),.32,-.12-Math.sin(phase)*.4);
        VectorBasis b=tangent(center[0],center[1],center[2]);
        Color[] bands={rgb(255,65,65),rgb(255,150,55),rgb(255,225,70),
                rgb(75,220,115),rgb(75,165,255),rgb(120,95,245),rgb(215,100,235)};
        for(int band=0;band<bands.length;band++) {
            for(int i=0;i<38 && budget.remaining()>0;i++) {
                double a=Math.PI*(i/37.0);
                double radius=.48+band*.012;
                double[] d=StarCatalog.normalize(center[0]+radius*(b.rx*Math.cos(a)+b.ux*Math.sin(a)),
                        center[1]+radius*(b.ry*Math.cos(a)+b.uy*Math.sin(a)),
                        center[2]+radius*(b.rz*Math.cos(a)+b.uz*Math.sin(a)));
                if(d[1]<-.08 || dot(d[0],d[1],d[2],view.x,view.y,view.z)<.1) continue;
                dust(player,skyPoint(eye,d[0],d[1],d[2],35),budget,bands[band],.6f);
            }
        }
    }

    private void renderBirds(Player player, Location eye, View view, Budget budget, PlayerState state) {
        if(!plugin.getConfig().getBoolean("atmosphere.birds",true)) return;
        if(state.nextBird==0) state.nextBird=ticks+120+state.random.nextInt(600);
        if(ticks>=state.nextBird && state.birdStart<0) {
            state.birdStart=ticks;
            state.birdSide=state.random.nextDouble()*.8-.4;
            state.nextBird=ticks+1200+state.random.nextInt(900);
        }
        if(state.birdStart<0) return;
        double age=(ticks-state.birdStart)/40.0;
        if(age>18) {state.birdStart=-1;return;}
        for(int bird=0;bird<13 && budget.remaining()>4;bird++) {
            double x=state.birdSide+age*.035+(bird-6)*.025;
            double y=.24+Math.sin(age*.8+bird*.4)*.012-Math.abs(bird-6)*.004;
            double z=.92;
            double[] d=StarCatalog.normalize(x,y,z);
            if(dot(d[0],d[1],d[2],view.x,view.y,view.z)<.1) continue;
            Location p=skyPoint(eye,d[0],d[1],d[2],33);
            dust(player,p,budget,rgb(225,230,235),.38f);
            VectorBasis b=tangent(d[0],d[1],d[2]);
            double flap=Math.sin(ticks*.2+bird)*.025;
            for(int wing=-1;wing<=1;wing+=2) {
                double[] w=StarCatalog.normalize(d[0]+b.rx*.018*wing,d[1]+flap,d[2]+b.rz*.018*wing);
                dust(player,skyPoint(eye,w[0],w[1],w[2],33),budget,rgb(210,220,230),.3f);
            }
        }
    }

    private void scheduleSkyEvents(Player player, PlayerState state, World world) {
        int rate=SkyMath.clamp(plugin.getConfig().getInt("meteors.shooting-stars",50),0,100);
        int shower=SkyMath.clamp(plugin.getConfig().getInt("meteors.meteor-showers",15),0,100);
        if(state.nextMeteor==0) state.nextMeteor=ticks+80+state.random.nextInt(120);
        if(state.meteorStart<0 && rate>0 && ticks>=state.nextMeteor) {
            state.meteorStart=ticks;
            state.meteorX=state.random.nextDouble()*.9-.45;
            state.meteorDuration=state.random.nextInt(100)<22?58:34;
            state.meteorFireball=plugin.getConfig().getBoolean("meteors.fireballs",true)
                    && state.random.nextInt(100)<20;
            state.showerRemaining=state.random.nextInt(100)<shower?8+state.random.nextInt(13):0;
            state.nextMeteor=ticks+Math.max(80,(int)(3000.0/Math.max(1,rate)))
                    +state.random.nextInt(220);
            if(plugin.getConfig().getBoolean("meteors.announce",true)
                    && state.showerRemaining>0)
                player.sendMessage(org.bukkit.ChatColor.GRAY+"Сегодня ночью возможен метеоритный дождь.");
        }
        int cometRate=SkyMath.clamp(plugin.getConfig().getInt("meteors.comets",10),0,100);
        if(state.cometStart<0 && state.nextComet==0)
            state.nextComet=ticks+2400+state.random.nextInt(6000);
        if(state.cometStart<0 && cometRate>0 && ticks>=state.nextComet) {
            state.cometStart=ticks;
            state.cometX=state.random.nextDouble()*.7-.35;
            state.nextComet=ticks+24000L*6+state.random.nextInt(12000);
        }
    }

    private void renderSkyEvents(Player player, Location eye, View view, Budget budget, PlayerState state) {
        if(state.meteorStart>=0) {
            long age=ticks-state.meteorStart;
            if(age>=state.meteorDuration) {
                state.meteorStart=-1;
                if(state.showerRemaining>0) {
                    state.showerRemaining--;
                    state.meteorStart=ticks+5;
                    state.meteorX=state.random.nextDouble()*.9-.45;
                    state.meteorDuration=30+state.random.nextInt(20);
                    state.meteorFireball=false;
                }
            } else drawMeteor(player,eye,view,budget,state,age/(double)state.meteorDuration);
        }
        if(state.cometStart>=0) {
            double progress=(ticks-state.cometStart)/1800.0;
            if(progress>=1) state.cometStart=-1;
            else drawComet(player,eye,view,budget,state,progress);
        }
    }

    private void drawMeteor(Player player, Location eye, View view, Budget budget,
                            PlayerState state, double progress) {
        double x=state.meteorX+progress*.72;
        double y=.78-progress*.50;
        double z=.55;
        VectorBasis b=tangent(view.x,view.y,view.z);
        for(int i=0;i<22 && budget.remaining()>0;i++) {
            double tail=i/21.0;
            double[] d=StarCatalog.normalize(view.x+b.rx*(x-tail*.26),
                    view.y+y*.02+tail*.12,view.z+b.rz*(x-tail*.26));
            if(dot(d[0],d[1],d[2],view.x,view.y,view.z)<.1) continue;
            Color tint=state.meteorFireball
                    ? mix(rgb(255,90,40),rgb(255,225,155),1-tail)
                    : mix(rgb(100,160,255),rgb(255,255,255),1-tail);
            dust(player,skyPoint(eye,d[0],d[1],d[2],35),budget,tint,(float)(1.25*(1-tail)+.35));
            if(i==0 && budget.remaining()>0)
                player.spawnParticle(Particle.END_ROD,skyPoint(eye,d[0],d[1],d[2],35),
                        0,0,0,0,0,null,true);
        }
    }

    private void drawComet(Player player, Location eye, View view, Budget budget,
                           PlayerState state, double progress) {
        double x=state.cometX+progress*.55;
        double y=.68-progress*.12;
        VectorBasis b=tangent(view.x,view.y,view.z);
        for(int i=0;i<36 && budget.remaining()>0;i++) {
            double u=i/35.0;
            double bend=u*u*.16;
            double[] d=StarCatalog.normalize(view.x+b.rx*(x-u*.32),
                    view.y+y*.02+bend,view.z+b.rz*(x-u*.32));
            Color c=mix(rgb(110,190,255),rgb(255,245,215),Math.pow(1-u,1.7));
            dust(player,skyPoint(eye,d[0],d[1],d[2],35),budget,c,(float)(1.1*(1-u)+.3));
        }
    }

    private void renderEnd(Player player, Location eye, View view, Budget budget,
                           PlayerState state, World world) {
        Random random=new Random(world.getSeed() ^ 0x454e44534b594cL);
        for(int i=0;i<280 && budget.remaining()>0;i++) {
            double[] d=randomDirection(random);
            if(d[1]<-.1 || dot(d[0],d[1],d[2],view.x,view.y,view.z)<.12) continue;
            double cloud=SkyNoise.fbm(d[0]*2.2,d[1]*2.2,d[2]*2.2,4);
            double detail=SkyNoise.fbm(d[0]*6.5+cloud*2.2,d[1]*6.5+cloud*2.2,d[2]*6.5+cloud*2.2,3);
            if(detail<.38) continue;
            Color tint=mix(rgb(125,55,205),rgb(220,100,210),SkyNoise.clamp(cloud,0,1));
            dust(player,skyPoint(eye,d[0],d[1],d[2],37),budget,tint,(float)(.5+detail));
        }
        renderStars(player,eye,view,budget,state,world);
        renderSkyEvents(player,eye,view,budget,state);
    }

    private void renderNether(Player player, Location eye, View view, Budget budget, PlayerState state) {
        Random random=new Random(state.worldSeed ^ 0x4e45544845524cL);
        for(int i=0;i<100 && budget.remaining()>0;i++) {
            double[] d=randomDirection(random);
            if(d[1]<0 || dot(d[0],d[1],d[2],view.x,view.y,view.z)<.2) continue;
            double n=SkyNoise.fbm(d[0]*5,d[1]*5,d[2]*5,3);
            if(n<.35) continue;
            dust(player,skyPoint(eye,d[0],d[1],d[2],34),budget,
                    mix(rgb(150,25,18),rgb(255,105,42),n),.7f+(float)n);
        }
    }

    private boolean isColdBiome(Biome biome) {
        String name=biome.name();
        return name.contains("SNOW") || name.contains("ICE") || name.contains("FROZEN")
                || name.contains("GROVE") || name.contains("PEAK") || name.contains("TAIGA");
    }

    private static double[] randomDirection(Random random) {
        double y=random.nextDouble()*2-1, a=random.nextDouble()*TAU;
        double r=Math.sqrt(1-y*y);
        return new double[]{r*Math.cos(a),y,r*Math.sin(a)};
    }

    private static Location skyPoint(Location eye,double x,double y,double z,double radius) {
        Location point=eye.clone().add(x*radius,y*radius,z*radius);
        point.setY(Math.max(point.getWorld().getMinHeight()+2,Math.min(point.getWorld().getMaxHeight()-2,point.getY())));\n        return point;
    }

    private static View view(Player player) {
        double yaw=Math.toRadians(player.getLocation().getYaw());
        double pitch=Math.toRadians(player.getLocation().getPitch());
        double cp=Math.cos(pitch);
        return new View(-Math.sin(yaw)*cp,-Math.sin(pitch),Math.cos(yaw)*cp);
    }

    private static VectorBasis tangent(double x,double y,double z) {
        double[] up=Math.abs(y)>.92?new double[]{1,0,0}:new double[]{0,1,0};
        double[] right=StarCatalog.normalize(cross(up,new double[]{x,y,z}));
        double[] vertical=StarCatalog.normalize(cross(new double[]{x,y,z},right));
        return new VectorBasis(right[0],right[1],right[2],vertical[0],vertical[1],vertical[2]);
    }

    private static double[] cross(double[] a,double[] b) {
        return new double[]{a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]};
    }
    private static double dot(double ax,double ay,double az,double bx,double by,double bz) {
        return ax*bx+ay*by+az*bz;
    }
    private static Color rgb(int r,int g,int b) { return Color.fromRGB(r,g,b); }
    private static Color mix(Color a,Color b,double t) {
        t=SkyNoise.clamp(t,0,1);
        return rgb((int)(a.getRed()+(b.getRed()-a.getRed())*t),
                (int)(a.getGreen()+(b.getGreen()-a.getGreen())*t),
                (int)(a.getBlue()+(b.getBlue()-a.getBlue())*t));
    }
    private static void dust(Player player,Location at,Budget budget,Color color,float size) {
        if(!budget.take(1)) return;
        player.spawnParticle(Particle.DUST,at,0,0,0,0,0,
                new Particle.DustOptions(color,(float)SkyMath.clamp(size,.2f,4f)),true);
    }

    private record View(double x,double y,double z) { }
    private record VectorBasis(double rx,double ry,double rz,double ux,double uy,double uz) { }
    private static final class Budget {
        private int remaining;
        Budget(int maximum) { remaining=maximum; }
        boolean take(int amount) {
            if(amount<=0 || remaining<amount) return false;
            remaining-=amount; return true;
        }
        int remaining() { return remaining; }
    }
    private static final class PlayerState {
        final long worldSeed;
        final Random random;
        final List<StarCatalog.Star> stars;
        long nextMeteor,nextComet,nextBird,lastRainTick=-1;
        long meteorStart=-1,cometStart=-1,birdStart=-1;
        double meteorX,cometX,birdSide;
        int meteorDuration,showerRemaining;
        boolean meteorFireball;
        PlayerState(long seed,long worldSeed,List<StarCatalog.Star> stars) {
            this.worldSeed=worldSeed; this.stars=stars; this.random=new Random(seed);
        }
    }
}
