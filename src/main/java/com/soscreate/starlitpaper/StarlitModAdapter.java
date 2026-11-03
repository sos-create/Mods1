package com.soscreate.starlitpaper;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Adapter for the original Fabric mod Starlit 1.1.1.
 *
 * Paper cannot load Fabric classes or execute GLSL in a vanilla client. This
 * adapter identifies the original archive, inspects its metadata and shader
 * catalogue, then reports which shader families are represented by the native
 * Paper renderer. It never attempts to load untrusted classes from the mod JAR.
 */
final class StarlitModAdapter {
    private static final String MOD_ID = "starlit";
    private static final String EXPECTED_VERSION = "1.1.1";
    private static final String SHADER_PREFIX = "assets/starlit/shaders/core/";
    private static final List<String> TRANSLATED_FAMILIES = List.of(
            "star", "glow", "halo", "disc", "day_glow", "day_shade",
            "milky_way", "galaxy", "aurora", "rainbow", "comet",
            "trail", "bird", "end_sky", "line"
    );

    private final JavaPlugin plugin;

    StarlitModAdapter(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    Report inspectConfiguredArchive() {
        String configured = plugin.getConfig().getString(
                "adapter.source-jar", "adapters/starlit-1.1.1-mc1.21.4.jar");
        Path path = plugin.getDataFolder().toPath().resolve(configured).normalize();
        if (!path.startsWith(plugin.getDataFolder().toPath().normalize())) {
            return Report.notFound("Путь к архиву Starlit выходит за пределы папки плагина.");
        }
        if (!Files.isRegularFile(path)) {
            return Report.notFound("Оригинальный JAR не найден: " + path
                    + ". Встроенный Paper-переводчик продолжает работать.");
        }
        return inspect(path);
    }

    private Report inspect(Path path) {
        List<String> shaders = new ArrayList<>();
        String metadata = "";
        try (JarFile jar = new JarFile(path.toFile())) {
            var manifest = jar.getManifest();
            if (manifest != null) {
                metadata = manifest.getMainAttributes().getValue("Implementation-Version");
            }
            ZipEntry fabricMetadata = jar.getEntry("fabric.mod.json");
            if (fabricMetadata == null) {
                return Report.invalid(path, "В архиве нет fabric.mod.json.");
            }
            try (var input = jar.getInputStream(fabricMetadata)) {
                metadata += "\n" + new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            String normalized = metadata.toLowerCase(Locale.ROOT);
            if (!normalized.contains("\"id\"") || !normalized.contains(MOD_ID)) {
                return Report.invalid(path, "Архив не идентифицирован как мод Starlit.");
            }
            if (!normalized.contains(EXPECTED_VERSION)) {
                plugin.getLogger().warning("Starlit adapter: версия архива отличается от "
                        + EXPECTED_VERSION + "; выполняется проверка совместимости по ресурсам.");
            }
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith(SHADER_PREFIX)
                        && (name.endsWith(".fsh") || name.endsWith(".vsh")
                        || name.endsWith(".json"))) {
                    shaders.add(name.substring(SHADER_PREFIX.length()));
                }
            }
        } catch (IOException exception) {
            return Report.invalid(path, "Не удалось прочитать JAR: " + exception.getMessage());
        }

        List<String> foundFamilies = TRANSLATED_FAMILIES.stream()
                .filter(family -> shaders.stream().anyMatch(name ->
                        name.toLowerCase(Locale.ROOT).contains(family)))
                .toList();
        return new Report(true, true, path.toString(), shaders.size(),
                foundFamilies, shaders, "Архив Starlit распознан; ресурсы проверены.");
    }

    record Report(boolean found, boolean valid, String archive, int shaderResources,
                  List<String> translatedFamilies, List<String> shaderFiles, String message) {
        static Report notFound(String message) {
            return new Report(false, false, "", 0, List.of(), List.of(), message);
        }

        static Report invalid(Path path, String message) {
            return new Report(true, false, path.toString(), 0, List.of(), List.of(), message);
        }

        boolean hasShaderResources() {
            return shaderResources > 0;
        }
    }
}
