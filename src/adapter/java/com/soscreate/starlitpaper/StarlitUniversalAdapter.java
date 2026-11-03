package com.soscreate.starlitpaper;

import com.soscreate.universalmodsync.ModAdapter;
import com.soscreate.universalmodsync.ModDescriptor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;

/**
 * UniversalModSync provider for Starlit 1.1.1.
 *
 * Starlit is a Fabric client mod. Its Fabric entrypoint and GLSL shaders cannot
 * run on Paper. This provider is a Paper-side recreation using the existing
 * procedural particle renderer; it never loads code from either uploaded JAR.
 */
public final class StarlitUniversalAdapter implements ModAdapter {
    private static final Set<String> TARGET_FILES = Set.of(
            "starlit-1.1.1-mc1.21.4.jar",
            "starlit-1.1.1-mc1.21.4(1).jar",
            "fabric-api-0.119.4+1.21.4.jar",
            "fabric-api-0.119.4+1.21.4 (1)(2).jar"
    );

    private SkyRenderer renderer;

    @Override
    public String getName() {
        return "Starlit 1.1.1 Paper recreation";
    }

    @Override
    public Set<String> supportedModFiles() {
        return TARGET_FILES;
    }

    @Override
    public void onEnable(JavaPlugin plugin, List<ModDescriptor> supportedMods) {
        boolean hasStarlit = supportedMods.stream()
                .anyMatch(mod -> "starlit".equalsIgnoreCase(mod.modId()));
        boolean hasFabricApi = supportedMods.stream()
                .anyMatch(mod -> "fabric-api".equalsIgnoreCase(mod.modId()));

        if (!hasStarlit) {
            plugin.getLogger().info("[Starlit adapter] Fabric API archive detected. "
                    + "It is a client-side library and has no server-side Paper code to install.");
            return;
        }

        if (renderer != null) renderer.stop();
        renderer = new SkyRenderer(plugin);
        renderer.start();

        plugin.getLogger().info("[Starlit adapter] Starlit 1.1.1 recognized through UniversalModSync.");
        plugin.getLogger().info("[Starlit adapter] Started Paper particle recreation: stars, Milky Way, "
                + "nebulae, galaxies, planets, moon glow, constellations, aurora, meteors, comets, "
                + "sunset effects, halos, rainbows and birds.");
        plugin.getLogger().info("[Starlit adapter] Original Fabric bytecode and GPU shaders are not executed. "
                + "This is the server-side recreation for vanilla clients, not pixel-identical GLSL rendering.");
        if (hasFabricApi) {
            plugin.getLogger().info("[Starlit adapter] The supplied Fabric API library was recognized; "
                    + "it is not required by the Paper-side recreation.");
        }
    }

    @Override
    public void onDisable() {
        if (renderer != null) {
            renderer.stop();
            renderer = null;
        }
    }
}
