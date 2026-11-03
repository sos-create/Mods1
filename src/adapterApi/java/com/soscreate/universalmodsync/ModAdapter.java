package com.soscreate.universalmodsync;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Set;

/**
 * Compile-time copy of the UniversalModSync adapter contract.
 * The adapter JAR excludes this class and uses the host plugin's copy at runtime.
 */
public interface ModAdapter {
    String getName();

    Set<String> supportedModFiles();

    void onEnable(JavaPlugin plugin, List<ModDescriptor> supportedMods) throws Exception;

    default void onDisable() { }

    static boolean targetsFile(Set<String> supportedFiles, String detectedFileName) {
        if (detectedFileName == null || detectedFileName.isBlank()
                || supportedFiles == null || supportedFiles.isEmpty()) return false;
        for (String configured : supportedFiles) {
            if (configured != null && configured.trim().equalsIgnoreCase(detectedFileName.trim())) {
                String lower = configured.trim().toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(".jar") || lower.endsWith(".zip")) return true;
            }
        }
        return false;
    }
}
