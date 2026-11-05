package com.soscreate.chiselsbits;

import com.soscreate.universalmodsync.ModAdapter;
import com.soscreate.universalmodsync.ModDescriptor;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * UniversalModSync adapter for Chisels & Bits 1.4.157.
 * Provides a Paper-side 4x4x4 micro-cube compatibility layer. It does not
 * load or execute the original NeoForge mod classes.
 */
public final class ChiselsBitsUniversalAdapter implements ModAdapter {
    private static final String TARGET = "chisels-and-bits-neoforge-1.4.157.jar";
    private static final int GRID = 4;
    private static final int BITS = GRID * GRID * GRID;

    private Listener listener;
    private NamespacedKey chiselKey, bitKey, ownerKey, cellKey, materialKey, recipeKey;

    @Override
    public String getName() {
        return "Chisels & Bits 1.4.157 Paper compatibility adapter";
    }

    @Override
    public Set<String> supportedModFiles() {
        return Set.of(TARGET, "chisels-and-bits-neoforge-1.4.157 (1).jar",
                "chisels-and-bits-neoforge-1.4.157(1).jar");
    }

    @Override
    public void onEnable(JavaPlugin plugin, List<ModDescriptor> mods) {
        boolean found = mods.stream().anyMatch(m -> "chiselsandbits".equalsIgnoreCase(m.modId()));
        if (!found) {
            plugin.getLogger().warning("[Chisels & Bits] Filename matched but mod id was not found.");
            return;
        }

        chiselKey = new NamespacedKey(plugin, "cab_chisel");
        bitKey = new NamespacedKey(plugin, "cab_bit_material");
        ownerKey = new NamespacedKey(plugin, "cab_microblock_owner");
        cellKey = new NamespacedKey(plugin, "cab_microblock_cell");
        materialKey = new NamespacedKey(plugin, "cab_microblock_material");
        recipeKey = new NamespacedKey(plugin, "cab_chisel_recipe");

        ItemStack chisel = new ItemStack(Material.IRON_PICKAXE);
        ItemMeta meta = chisel.getItemMeta();
        meta.displayName(Component.text("Chisels & Bits Chisel"));
        meta.getPersistentDataContainer().set(chiselKey, PersistentDataType.BYTE, (byte) 1);
        chisel.setItemMeta(meta);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, chisel);
        recipe.shape(" I ", " SI", " S ");
        recipe.setIngredient('I', Material.IRON_INGOT);
        recipe.setIngredient('S', Material.STICK);
        Bukkit.addRecipe(recipe);

        listener = new CabListener();
        Bukkit.getPluginManager().registerEvents(listener, plugin);

        String version = mods.stream().filter(m -> "chiselsandbits".equalsIgnoreCase(m.modId()))
                .map(ModDescriptor::version).findFirst().orElse("unknown");
        plugin.getLogger().info("[Chisels & Bits] Detected version " + version
                + " through UniversalModSync. Enabled chisel, bit harvesting and 4x4x4 micro-cube placement.");
        plugin.getLogger().warning("[Chisels & Bits] This is a Paper compatibility subset, not the original "
                + "NeoForge runtime. The supplied mod targets Minecraft 1.20.4 / NeoForge 20.4.147+ "
                + "and declares Scena as a dependency; neither NeoForge nor Scena is loaded on Paper.");
    }

    @Override
    public void onDisable() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        if (recipeKey != null) Bukkit.removeRecipe(recipeKey);
    }

    private boolean isChisel(ItemStack item) {
        return item != null && item.getType() == Material.IRON_PICKAXE && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(chiselKey, PersistentDataType.BYTE);
    }

    private ItemStack bit(Material material, int amount) {
        Material itemType = material.isItem() ? material : Material.STONE;
        ItemStack result = new ItemStack(itemType, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = result.getItemMeta();
        meta.displayName(Component.text("Bit: " + material.name().toLowerCase(Locale.ROOT)));
        meta.getPersistentDataContainer().set(bitKey, PersistentDataType.STRING, material.getKey().toString());
        result.setItemMeta(meta);
        return result;
    }

    private Material bitMaterial(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String key = item.getItemMeta().getPersistentDataContainer().get(bitKey, PersistentDataType.STRING);
        if (key == null) return null;
        Material material = Material.matchMaterial(key);
        return material != null && material.isBlock() && !material.isAir() ? material : null;
    }

    private String owner(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }

    private final class CabListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onBlockBreak(BlockBreakEvent event) {
            Player player = event.getPlayer();
            ItemStack held = player.getInventory().getItemInMainHand();
            if (!isChisel(held) || player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
            Material material = event.getBlock().getType();
            if (!material.isBlock() || material.isAir()) return;

            event.setDropItems(false);
            event.setExpToDrop(0);
            event.getBlock().getWorld().dropItemNaturally(
                    event.getBlock().getLocation().add(0.5, 0.5, 0.5), bit(material, BITS));
            player.sendActionBar(Component.text("Harvested " + BITS + " bits from " + material.name()));
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onPlaceBit(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                    || event.getClickedBlock() == null) return;
            ItemStack held = event.getPlayer().getInventory().getItemInMainHand();
            Material material = bitMaterial(held);
            if (material == null) return;

            Block target = event.getClickedBlock().getRelative(event.getBlockFace());
            if (!target.getType().isAir() && !target.isPassable()) return;
            event.setCancelled(true);

            String owner = owner(target);
            Set<String> occupied = new HashSet<>();
            for (Entity entity : target.getWorld().getNearbyEntities(
                    target.getLocation().add(0.5, 0.5, 0.5), 0.8, 0.8, 0.8)) {
                if (!(entity instanceof BlockDisplay display)) continue;
                PersistentDataContainer pdc = display.getPersistentDataContainer();
                if (owner.equals(pdc.get(ownerKey, PersistentDataType.STRING))) {
                    String cell = pdc.get(cellKey, PersistentDataType.STRING);
                    if (cell != null) occupied.add(cell);
                }
            }

            int cell = -1;
            for (int i = 0; i < BITS; i++) {
                if (!occupied.contains(Integer.toString(i))) { cell = i; break; }
            }
            if (cell < 0) {
                event.getPlayer().sendActionBar(Component.text("Micro-cube grid is full (4x4x4)."));
                return;
            }

            final int selected = cell;
            int x = selected % GRID;
            int y = (selected / GRID) % GRID;
            int z = selected / (GRID * GRID);
            target.getWorld().spawn(target.getLocation().add(
                    x / (double) GRID, y / (double) GRID, z / (double) GRID),
                    BlockDisplay.class, display -> {
                        display.setBlock(material.createBlockData());
                        display.setTransformation(new Transformation(new Vector3f(0, 0, 0),
                                new AxisAngle4f(), new Vector3f(1f / GRID, 1f / GRID, 1f / GRID),
                                new AxisAngle4f()));
                        display.setPersistent(true);
                        PersistentDataContainer pdc = display.getPersistentDataContainer();
                        pdc.set(ownerKey, PersistentDataType.STRING, owner);
                        pdc.set(cellKey, PersistentDataType.STRING, Integer.toString(selected));
                        pdc.set(materialKey, PersistentDataType.STRING, material.getKey().toString());
                    });

            if (held.getAmount() <= 1) event.getPlayer().getInventory().setItemInMainHand(null);
            else held.setAmount(held.getAmount() - 1);
            event.getPlayer().sendActionBar(Component.text("Placed micro-cube "
                    + (occupied.size() + 1) + "/" + BITS));
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onRecoverBit(PlayerInteractEntityEvent event) {
            if (!(event.getRightClicked() instanceof BlockDisplay display)) return;
            Player player = event.getPlayer();
            if (!isChisel(player.getInventory().getItemInMainHand())) return;

            String key = display.getPersistentDataContainer().get(materialKey, PersistentDataType.STRING);
            if (key == null) return;
            Material material = Material.matchMaterial(key);
            if (material == null || !material.isBlock() || material.isAir()) return;

            event.setCancelled(true);
            display.remove();
            player.getInventory().addItem(bit(material, 1)).values()
                    .forEach(leftover -> player.getWorld().dropItemNaturally(player.getLocation(), leftover));
        }
    }
}
