package com.soscreate.universalmodsync;

import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;
import java.util.Set;

/** Compile-time API stub. The real interface is supplied by UniversalModSync at runtime. */
public interface ModAdapter {
    String getName();
    Set<String> supportedModFiles();
    void onEnable(JavaPlugin plugin, List<ModDescriptor> supportedMods) throws Exception;
    default void onDisable() { }
}
