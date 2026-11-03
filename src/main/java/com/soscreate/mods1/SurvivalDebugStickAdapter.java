package com.soscreate.mods1;

import com.soscreate.universalmodsync.ModAdapter;
import com.soscreate.universalmodsync.ModDescriptor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Paper-side reimplementation of Survival Debug Stick Mod.
 *
 * The Fabric mod uses AttackBlockCallback for left-clicks and a mixin to
 * enable the vanilla debug-stick behaviour outside creative mode. This
 * adapter replaces those hooks with Paper events and Bukkit BlockData.
 * No Fabric classes or Fabric API are loaded by this adapter.
 */
public final class SurvivalDebugStickAdapter implements ModAdapter, Listener {
    public static final String TARGET_FILE = "survival-debug-mod-1.2.2_1.21.2.jar";

    private static final Pattern PROPERTY_PATTERN =
            Pattern.compile("([a-z0-9_]+)=([a-z0-9_]+)");
    private static final List<String> BOOL = List.of("false", "true");
    private static final List<String> SLAB_TYPES = List.of("top", "bottom");
    private static final List<String> BAMBOO_LEAVES = List.of("none", "large");
    private static final Map<String, List<String>> ENUM_VALUES = createEnumValues();

    private JavaPlugin plugin;
    private NamespacedKey recipeKey;
    private NamespacedKey lastModifiedKey;
    private boolean enabled;

    @Override
    public String getName() {
        return "Survival Debug Stick Adapter";
    }

    @Override
    public Set<String> supportedModFiles() {
        // Exact archive-name matching is intentional. Do not replace with a mask.
        return Set.of(TARGET_FILE);
    }

    @Override
    public void onEnable(JavaPlugin plugin, List<ModDescriptor> supportedMods) {
        if (enabled) return;

        this.plugin = plugin;
        this.recipeKey = new NamespacedKey(plugin, "survival_debug_stick_recipe");
        this.lastModifiedKey = new NamespacedKey(plugin, "survival_debug_stick_last_modified");

        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerRecipe();
        enabled = true;

        plugin.getLogger().info("Survival Debug Stick adapter enabled for "
                + supportedMods.stream().map(ModDescriptor::fileName).distinct().toList()
                + ". Left-click with the vanilla debug stick in survival/adventure mode. "
                + "No client mod or Fabric API is required.");
    }

    private void registerRecipe() {
        if (Bukkit.getRecipe(recipeKey) != null) {
            Bukkit.removeRecipe(recipeKey);
        }

        ItemStack result = new ItemStack(Material.DEBUG_STICK, 1);
        ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, result);
        recipe.addIngredient(Material.STICK);
        recipe.addIngredient(Material.CHORUS_FRUIT);
        Bukkit.addRecipe(recipe);
    }

    /**
     * The original mod registers Fabric's AttackBlockCallback, so its trigger
     * is a left-click on a block, not a right-click interaction.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onUseDebugStick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        ItemStack item = event.getItem();
        Block block = event.getClickedBlock();
        if (item == null || item.getType() != Material.DEBUG_STICK || block == null) return;

        GameMode mode = event.getPlayer().getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return;

        // Prevent a debug-stick click from starting normal block mining.
        event.setCancelled(true);

        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;

            // The original Fabric callback throttles repeated use for 5 ticks.
            long currentTick = block.getWorld().getGameTime();
            Long lastTick = meta.getPersistentDataContainer()
                    .get(lastModifiedKey, PersistentDataType.LONG);
            if (lastTick != null && currentTick >= lastTick && currentTick - lastTick < 5L) {
                return;
            }
            meta.getPersistentDataContainer().set(
                    lastModifiedKey, PersistentDataType.LONG, currentTick);
            item.setItemMeta(meta);

            if (!isDefaultBlockAllowed(block.getType())) {
                event.getPlayer().sendMessage(ChatColor.RED
                        + "Этот блок не разрешён настройками Survival Debug Stick.");
                return;
            }

            Map<String, String> properties = readProperties(block.getBlockData().getAsString());
            if (properties.isEmpty()) {
                event.getPlayer().sendMessage(ChatColor.RED
                        + "У этого блока нет изменяемых свойств.");
                return;
            }

            NamespacedKey selectedKey = selectedPropertyKey(block.getType());
            String selected = meta.getPersistentDataContainer()
                    .get(selectedKey, PersistentDataType.STRING);

            if (event.getPlayer().isSneaking()) {
                String next = nextProperty(properties.keySet(), selected, block.getType());
                if (next == null) {
                    event.getPlayer().sendMessage(ChatColor.RED
                            + "Не удалось выбрать доступное свойство блока.");
                    return;
                }

                meta.getPersistentDataContainer().set(
                        selectedKey, PersistentDataType.STRING, next);
                item.setItemMeta(meta);
                event.getPlayer().sendMessage(ChatColor.YELLOW + "Свойство: "
                        + ChatColor.WHITE + next + ChatColor.GRAY + " ("
                        + block.getType().name().toLowerCase(Locale.ROOT) + ")");
                return;
            }

            if (selected == null || !properties.containsKey(selected)
                    || !isDefaultPropertyAllowed(block.getType(), selected)) {
                selected = firstAllowedProperty(block.getType(), properties);
                if (selected == null) {
                    event.getPlayer().sendMessage(ChatColor.RED
                            + "У этого блока нет доступных для изменения свойств.");
                    return;
                }
                meta.getPersistentDataContainer().set(
                        selectedKey, PersistentDataType.STRING, selected);
                item.setItemMeta(meta);
            }

            String current = properties.get(selected);
            String propertyToChange = selected;
            List<String> candidates = candidatesFor(propertyToChange, current).stream()
                    .filter(value -> isDefaultPropertyAllowed(
                            block.getType(), propertyToChange, value))
                    .toList();

            String nextValue = findNextValidValue(
                    block.getBlockData().getAsString(), selected, current, candidates);
            if (nextValue == null) {
                event.getPlayer().sendMessage(ChatColor.RED
                        + "Для свойства " + selected
                        + " не найдено другого разрешённого значения.");
                return;
            }

            String updatedState = replaceProperty(
                    block.getBlockData().getAsString(), selected, nextValue);
            BlockData newData = Bukkit.createBlockData(updatedState);
            block.setBlockData(newData, true);

            event.getPlayer().sendMessage(ChatColor.GREEN + selected + ChatColor.GRAY
                    + ": " + ChatColor.WHITE + current + ChatColor.GRAY
                    + " → " + ChatColor.WHITE + nextValue);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            plugin.getLogger().log(Level.FINE,
                    "Could not cycle debug-stick block property", exception);
            event.getPlayer().sendMessage(ChatColor.RED
                    + "Не удалось изменить свойство этого блока.");
        }
    }

    private NamespacedKey selectedPropertyKey(Material material) {
        return new NamespacedKey(plugin,
                "selected_" + material.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Mirrors the default whitelist in the Fabric mod's SDSConfig:
     * iron bars, bamboo, stairs, walls, glass panes, fences and slabs.
     */
    static boolean isDefaultBlockAllowed(Material material) {
        String name = material.name();
        return material == Material.IRON_BARS
                || material == Material.BAMBOO
                || name.endsWith("_STAIRS")
                || name.endsWith("_WALL")
                || name.endsWith("_FENCE")
                || name.equals("GLASS_PANE")
                || name.endsWith("_STAINED_GLASS_PANE")
                || name.endsWith("_SLAB");
    }

    /**
     * Mirrors the mod's default property allow/deny rules. waterlogged is
     * forbidden globally; bamboo and slabs have narrower explicit allowlists.
     */
    static boolean isDefaultPropertyAllowed(Material material, String property) {
        if (!isDefaultBlockAllowed(material) || property == null
                || property.equals("waterlogged")) return false;
        if (material == Material.BAMBOO) {
            return property.equals("age") || property.equals("leaves");
        }
        if (material.name().endsWith("_SLAB")) {
            return property.equals("type");
        }
        return true;
    }

    static boolean isDefaultPropertyAllowed(Material material, String property, String value) {
        if (value == null || !isDefaultPropertyAllowed(material, property)) return false;
        if (material == Material.BAMBOO && property.equals("leaves")) {
            return BAMBOO_LEAVES.contains(value);
        }
        if (material.name().endsWith("_SLAB") && property.equals("type")) {
            return SLAB_TYPES.contains(value);
        }
        return true;
    }

    static Map<String, String> readProperties(String blockData) {
        int start = blockData.indexOf('[');
        int end = blockData.lastIndexOf(']');
        if (start < 0 || end <= start) return Map.of();

        Map<String, String> result = new LinkedHashMap<>();
        Matcher matcher = PROPERTY_PATTERN.matcher(blockData.substring(start + 1, end));
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2));
        }
        return result;
    }

    static String replaceProperty(String blockData, String property, String value) {
        int start = blockData.indexOf('[');
        int end = blockData.lastIndexOf(']');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Block has no properties");
        }

        String inside = blockData.substring(start + 1, end);
        Matcher matcher = PROPERTY_PATTERN.matcher(inside);
        StringBuffer changed = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            if (matcher.group(1).equals(property)) {
                matcher.appendReplacement(changed,
                        Matcher.quoteReplacement(property + "=" + value));
                found = true;
            }
        }
        matcher.appendTail(changed);
        if (!found) throw new IllegalArgumentException("Property not found: " + property);
        return blockData.substring(0, start + 1) + changed + blockData.substring(end);
    }

    private static String firstAllowedProperty(Material material, Map<String, String> properties) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (isDefaultPropertyAllowed(material, entry.getKey())) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static String nextProperty(Set<String> properties, String selected, Material material) {
        List<String> ordered = properties.stream()
                .filter(property -> isDefaultPropertyAllowed(material, property))
                .toList();
        if (ordered.isEmpty()) return null;
        int index = ordered.indexOf(selected);
        return ordered.get((index + 1) % ordered.size());
    }

    private String findNextValidValue(
            String state, String property, String current, List<String> candidates) {
        if (candidates.isEmpty()) return null;

        int currentIndex = candidates.indexOf(current);
        for (int offset = 1; offset <= candidates.size(); offset++) {
            String candidate = candidates.get((Math.max(currentIndex, -1) + offset)
                    % candidates.size());
            if (candidate.equals(current)) continue;
            try {
                Bukkit.createBlockData(replaceProperty(state, property, candidate));
                return candidate;
            } catch (IllegalArgumentException ignored) {
                // A value can be valid for the property in general but invalid
                // for this particular block state.
            }
        }
        return null;
    }

    private static List<String> candidatesFor(String property, String current) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        List<String> known = ENUM_VALUES.get(property);
        if (known != null) values.addAll(known);

        if (BOOL.contains(current)) values.addAll(BOOL);
        if (current.matches("\\d+")) {
            int maximum = switch (property) {
                case "age" -> 25;
                case "distance" -> 7;
                case "layers" -> 8;
                case "delay" -> 4;
                case "eggs" -> 4;
                case "bites" -> 6;
                case "honey_level" -> 5;
                case "charges" -> 4;
                case "note" -> 24;
                default -> 15;
            };
            for (int i = 0; i <= maximum; i++) values.add(Integer.toString(i));
            if (property.equals("distance") || property.equals("layers")
                    || property.equals("delay")) {
                for (int i = 1; i <= maximum; i++) values.add(Integer.toString(i));
            }
        }

        values.add(current);
        return List.copyOf(values);
    }

    private static Map<String, List<String>> createEnumValues() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("axis", List.of("x", "y", "z"));
        values.put("facing", List.of("north", "south", "east", "west", "up", "down"));
        values.put("horizontal_facing", List.of("north", "south", "east", "west"));
        values.put("half", List.of("top", "bottom", "upper", "lower"));
        values.put("hinge", List.of("left", "right"));
        values.put("face", List.of("floor", "wall", "ceiling"));
        values.put("shape", List.of("straight", "inner_left", "inner_right", "outer_left",
                "outer_right", "north_south", "east_west", "ascending_east",
                "ascending_west", "ascending_north", "ascending_south", "south_east",
                "south_west", "north_west", "north_east"));
        values.put("type", List.of("single", "double", "top", "bottom", "normal",
                "sticky", "head", "tail", "left", "right"));
        values.put("mode", List.of("compare", "subtract"));
        values.put("instrument", List.of("harp", "basedrum", "snare", "hat", "bass",
                "flute", "bell", "guitar", "chime", "xylophone", "iron_xylophone",
                "cow_bell", "didgeridoo", "bit", "banjo", "pling"));
        values.put("orientation", List.of("down_east", "down_west", "down_north",
                "down_south", "up_east", "up_west", "up_north", "up_south",
                "west_up", "east_up", "north_up", "south_up", "west_down",
                "east_down", "north_down", "south_down"));
        values.put("vertical_direction", List.of("up", "down"));
        values.put("part", List.of("head", "foot"));
        values.put("attachment", List.of("floor", "ceiling", "single_wall", "double_wall"));
        values.put("thickness", List.of("tip_merge", "tip", "frustum", "middle", "base"));
        values.put("tilt", List.of("none", "unstable", "partial", "full"));
        values.put("leaves", List.of("none", "small", "large"));
        return Map.copyOf(values);
    }

    @Override
    public void onDisable() {
        if (!enabled) return;
        HandlerList.unregisterAll(this);
        if (recipeKey != null) Bukkit.removeRecipe(recipeKey);
        enabled = false;
        plugin = null;
        recipeKey = null;
        lastModifiedKey = null;
    }
}
