package com.soscreate.chiselsbits;

import com.soscreate.universalmodsync.ModAdapter;
import com.soscreate.universalmodsync.ModDescriptor;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.*;
import java.util.Base64;

/**
 * Paper 1.21.4 server-side reimplementation of the core Chisels & Bits workflow.
 * The original NeoForge classes are deliberately not loaded by Paper.
 *
 * Structures are persisted in the Interaction entity's PDC. Their visible
 * geometry is greedily meshed into persistent BlockDisplay cuboids.
 */
public final class ChiselsBitsUniversalAdapter implements ModAdapter {
    private static final int SIZE = 16;
    private static final int CELLS = SIZE * SIZE * SIZE;
    private static final String[] ACCEPTED_FILES = {
        "chisels-and-bits-neoforge-1.4.157.jar",
        "chisels-and-bits-neoforge-1.4.157 (1).jar",
        "chisels-and-bits-neoforge-1.4.157(1).jar"
    };

    private JavaPlugin plugin;
    private Listener listener;
    private NamespacedKey toolKey, bitKey, structureKey, gridKey, displayKey,
            blueprintKey, ownerKey, anchorKey;

    private enum Tool { IRON_CHISEL, DIAMOND_CHISEL, GOLD_CHISEL, WRENCH, SAW, BLUEPRINT }
    @Override public String getName() { return "Chisels & Bits — Paper 1.21.4 adapter"; }
    @Override public Set<String> supportedModFiles() { return Set.of(ACCEPTED_FILES); }

    @Override
    public void onEnable(JavaPlugin plugin, List<ModDescriptor> mods) {
        this.plugin = plugin;
        ModDescriptor detected = mods.stream()
                .filter(m -> "chiselsandbits".equalsIgnoreCase(m.modId()))
                .findFirst().orElse(null);
        if (detected == null) {
            plugin.getLogger().warning("[C&B] UniversalModSync did not provide modId chiselsandbits.");
            return;
        }

        toolKey = key("tool");
        bitKey = key("bit_material");
        structureKey = key("structure_id");
        gridKey = key("voxel_grid");
        displayKey = key("display_owner");
        blueprintKey = key("blueprint_data");
        anchorKey = key("anchor");
        ownerKey = key("owner");

        registerRecipes();
        listener = new CabListener();
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        plugin.getLogger().info("[C&B] Server-side voxel engine enabled for detected " + detected.fileName()
                + " (" + detected.version() + "). 16x16x16 grid, persistent structures, greedy display meshing.");
        plugin.getLogger().warning("[C&B] Paper implementation is an independent compatibility port; it cannot "
                + "load NeoForge/Scena classes or provide native mod rendering/collision hooks.");
    }

    @Override
    public void onDisable() {
        if (listener != null) HandlerList.unregisterAll(listener);
        listener = null;
        for (String id : List.of("cab_iron_chisel", "cab_diamond_chisel", "cab_gold_chisel",
                "cab_wrench", "cab_saw", "cab_blueprint")) {
            Bukkit.removeRecipe(key(id));
        }
    }

    private NamespacedKey key(String value) { return new NamespacedKey(plugin, value); }

    private void registerRecipes() {
        recipe("cab_iron_chisel", tool(Material.IRON_PICKAXE, Tool.IRON_CHISEL, "Iron Chisel"),
                " I ", " SI", " S ", 'I', Material.IRON_INGOT, 'S', Material.STICK);
        recipe("cab_diamond_chisel", tool(Material.DIAMOND_PICKAXE, Tool.DIAMOND_CHISEL, "Diamond Chisel"),
                " D ", " SI", " S ", 'D', Material.DIAMOND, 'I', Material.IRON_INGOT, 'S', Material.STICK);
        recipe("cab_gold_chisel", tool(Material.GOLDEN_PICKAXE, Tool.GOLD_CHISEL, "Gold Chisel"),
                " G ", " SI", " S ", 'G', Material.GOLD_INGOT, 'I', Material.IRON_INGOT, 'S', Material.STICK);
        recipe("cab_wrench", tool(Material.IRON_HOE, Tool.WRENCH, "Bit Wrench"),
                " II", " SI", "S  ", 'I', Material.IRON_INGOT, 'S', Material.STICK);
        recipe("cab_saw", tool(Material.IRON_AXE, Tool.SAW, "Bit Saw"),
                " II", "ISI", " S ", 'I', Material.IRON_INGOT, 'S', Material.STICK);
        recipe("cab_blueprint", tool(Material.PAPER, Tool.BLUEPRINT, "Bit Blueprint"),
                "PPP", "P P", "PPP", 'P', Material.PAPER);
    }

    private void recipe(String id, ItemStack result, String row1, String row2, String row3, Object... ingredients) {
        ShapedRecipe r = new ShapedRecipe(key(id), result);
        r.shape(row1, row2, row3);
        for (int i = 0; i < ingredients.length; i += 2)
            r.setIngredient((Character) ingredients[i], (Material) ingredients[i + 1]);
        Bukkit.addRecipe(r);
    }

    private ItemStack tool(Material material, Tool type, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, type.name());
        item.setItemMeta(meta);
        return item;
    }

    private Tool tool(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (value == null) return null;
        try { return Tool.valueOf(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    private ItemStack bit(Material material, int amount) {
        ItemStack item = new ItemStack(material.isItem() ? material : Material.STONE, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Bit: " + material.name().toLowerCase(Locale.ROOT)));
        meta.getPersistentDataContainer().set(bitKey, PersistentDataType.STRING, material.getKey().toString());
        item.setItemMeta(meta);
        return item;
    }

    private Material bitMaterial(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String name = item.getItemMeta().getPersistentDataContainer().get(bitKey, PersistentDataType.STRING);
        if (name == null) return null;
        Material m = Material.matchMaterial(name);
        return m != null && m.isBlock() && !m.isAir() ? m : null;
    }

    private static int index(int x, int y, int z) { return x + SIZE * (y + SIZE * z); }
    private static boolean inside(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < SIZE && y < SIZE && z < SIZE;
    }

    private static final class VoxelData {
        final byte[] cells = new byte[CELLS];
        final List<String> palette = new ArrayList<>();

        int materialIndex(Material material) {
            String id = material.getKey().toString();
            int found = palette.indexOf(id);
            if (found >= 0) return found + 1;
            if (palette.size() >= 254) throw new IllegalStateException("A structure can contain at most 254 materials.");
            palette.add(id);
            return palette.size();
        }

        Material material(int value) {
            if (value <= 0 || value > palette.size()) return Material.AIR;
            Material m = Material.matchMaterial(palette.get(value - 1));
            return m == null ? Material.AIR : m;
        }

        void fill(Material material) {
            int id = materialIndex(material);
            Arrays.fill(cells, (byte) id);
        }

        String encode() {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeByte(palette.size());
                for (String p : palette) out.writeUTF(p);
                out.write(cells);
                out.close();
                return Base64.getEncoder().encodeToString(bytes.toByteArray());
            } catch (Exception ex) { throw new IllegalStateException(ex); }
        }

        static VoxelData decode(String encoded) {
            try {
                if (encoded == null || encoded.length() > 16_384)
                    throw new IllegalArgumentException("Voxel data exceeds the supported size.");
                byte[] raw = Base64.getDecoder().decode(encoded);
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw));
                VoxelData data = new VoxelData();
                int count = in.readUnsignedByte();
                if (count == 255) throw new IllegalArgumentException("Invalid material palette.");
                for (int i = 0; i < count; i++) data.palette.add(in.readUTF());
                in.readFully(data.cells);
                if (in.available() != 0) throw new IllegalArgumentException("Unexpected trailing voxel data.");
                for (byte cell : data.cells)
                    if (Byte.toUnsignedInt(cell) > count) throw new IllegalArgumentException("Invalid voxel material index.");
                return data;
            } catch (Exception ex) { throw new IllegalArgumentException("Corrupt voxel data", ex); }
        }
    }

    private void save(Interaction anchor, VoxelData data) {
        anchor.getPersistentDataContainer().set(gridKey, PersistentDataType.STRING, data.encode());
    }

    private VoxelData load(Interaction anchor) {
        String encoded = anchor.getPersistentDataContainer().get(gridKey, PersistentDataType.STRING);
        return encoded == null ? new VoxelData() : VoxelData.decode(encoded);
    }

    private Interaction createStructure(Location blockLocation, VoxelData data, UUID owner) {
        World world = blockLocation.getWorld();
        Location base = new Location(world, blockLocation.getBlockX(), blockLocation.getBlockY(), blockLocation.getBlockZ());
        Interaction anchor = world.spawn(base, Interaction.class, entity -> {
            entity.setInteractionWidth(1.0f);
            entity.setInteractionHeight(1.0f);
            entity.setResponsive(true);
            entity.setPersistent(true);
            entity.setInvulnerable(true);
            entity.getPersistentDataContainer().set(anchorKey, PersistentDataType.BYTE, (byte) 1);
            entity.getPersistentDataContainer().set(structureKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            entity.getPersistentDataContainer().set(ownerKey, PersistentDataType.STRING, owner.toString());
        });
        save(anchor, data);
        rebuild(anchor, data);
        return anchor;
    }

    private void rebuild(Interaction anchor, VoxelData data) {
        String id = anchor.getPersistentDataContainer().get(structureKey, PersistentDataType.STRING);
        if (id == null) return;
        World world = anchor.getWorld();
        for (Entity entity : world.getNearbyEntities(anchor.getLocation().clone().add(0.5, 0.5, 0.5), 2, 2, 2)) {
            if (entity instanceof BlockDisplay display
                    && id.equals(display.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING)))
                display.remove();
        }

        boolean[] used = new boolean[CELLS];
        for (int z = 0; z < SIZE; z++) for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int at = index(x, y, z);
            int value = Byte.toUnsignedInt(data.cells[at]);
            if (value == 0 || used[at]) continue;

            int sx = 1;
            while (x + sx < SIZE && same(data, used, x + sx, y, z, value)) sx++;
            int sy = 1;
            outerY: while (y + sy < SIZE) {
                for (int xx = x; xx < x + sx; xx++)
                    if (!same(data, used, xx, y + sy, z, value)) break outerY;
                sy++;
            }
            int sz = 1;
            outerZ: while (z + sz < SIZE) {
                for (int yy = y; yy < y + sy; yy++)
                    for (int xx = x; xx < x + sx; xx++)
                        if (!same(data, used, xx, yy, z + sz, value)) break outerZ;
                sz++;
            }
            for (int zz = z; zz < z + sz; zz++)
                for (int yy = y; yy < y + sy; yy++)
                    for (int xx = x; xx < x + sx; xx++) used[index(xx, yy, zz)] = true;

            Material material = data.material(value);
            if (material.isAir()) continue;
            final int fX = sx, fY = sy, fZ = sz;
            Location loc = anchor.getLocation().clone().add(x / 16.0, y / 16.0, z / 16.0);
            world.spawn(loc, BlockDisplay.class, display -> {
                display.setBlock(material.createBlockData());
                display.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(fX / 16f, fY / 16f, fZ / 16f), new AxisAngle4f()));
                display.setPersistent(true);
                display.setInvulnerable(true);
                display.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, id);
            });
        }
        save(anchor, data);
    }

    private static boolean same(VoxelData data, boolean[] used, int x, int y, int z, int value) {
        int i = index(x, y, z);
        return !used[i] && Byte.toUnsignedInt(data.cells[i]) == value;
    }

    private void removeStructure(Interaction anchor) {
        String id = anchor.getPersistentDataContainer().get(structureKey, PersistentDataType.STRING);
        if (id != null) {
            for (Entity entity : anchor.getWorld().getNearbyEntities(anchor.getLocation().clone().add(0.5, 0.5, 0.5), 2, 2, 2))
                if (entity instanceof BlockDisplay d
                        && id.equals(d.getPersistentDataContainer().get(displayKey, PersistentDataType.STRING)))
                    d.remove();
        }
        anchor.remove();
    }

    private int[] targetedCell(Player player, Interaction anchor) {
        Location eye = player.getEyeLocation();
        RayTraceResult hit = new BoundingBox(anchor.getLocation().getX(), anchor.getLocation().getY(),
                anchor.getLocation().getZ(), anchor.getLocation().getX() + 1,
                anchor.getLocation().getY() + 1, anchor.getLocation().getZ() + 1)
                .rayTrace(eye.toVector(), eye.getDirection(), 6.0);
        if (hit == null) return null;
        Location p = hit.getHitPosition().toLocation(anchor.getWorld());
        int x = Math.min(15, Math.max(0, (int) Math.floor((p.getX() - anchor.getLocation().getX()) * 16)));
        int y = Math.min(15, Math.max(0, (int) Math.floor((p.getY() - anchor.getLocation().getY()) * 16)));
        int z = Math.min(15, Math.max(0, (int) Math.floor((p.getZ() - anchor.getLocation().getZ()) * 16)));
        return new int[]{x, y, z};
    }

    private void giveBits(Player player, Material material, int amount) {
        while (amount > 0) {
            int stack = Math.min(64, amount);
            Map<Integer, ItemStack> rest = player.getInventory().addItem(bit(material, stack));
            rest.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            amount -= stack;
        }
    }

    private void rotate(VoxelData data, BlockFace facing) {
        byte[] old = data.cells.clone();
        boolean reverse = facing == BlockFace.DOWN || facing == BlockFace.WEST || facing == BlockFace.SOUTH;
        for (int z = 0; z < SIZE; z++) for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) {
            int nx = x, ny = y, nz = z;
            switch (facing) {
                case UP, DOWN -> { nx = reverse ? y : SIZE - 1 - y; ny = reverse ? SIZE - 1 - x : x; }
                case EAST, WEST -> { ny = reverse ? z : SIZE - 1 - z; nz = reverse ? SIZE - 1 - y : y; }
                default -> { nx = reverse ? z : SIZE - 1 - z; nz = reverse ? SIZE - 1 - x : x; }
            }
            data.cells[index(nx, ny, nz)] = old[index(x, y, z)];
        }
    }

    private final class CabListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onChiselBlock(BlockDamageEvent event) {
            Player player = event.getPlayer();
            Tool heldTool = tool(player.getInventory().getItemInMainHand());
            if (heldTool != Tool.IRON_CHISEL && heldTool != Tool.DIAMOND_CHISEL && heldTool != Tool.GOLD_CHISEL) return;
            Block block = event.getBlock();
            Material material = block.getType();
            if (!material.isBlock() || material.isAir() || !block.getType().isSolid()) return;
            event.setCancelled(true);
            VoxelData data = new VoxelData();
            data.fill(material);
            Location location = block.getLocation();
            createStructure(location, data, player.getUniqueId());
            block.setType(Material.AIR, false);
            player.sendActionBar(Component.text("Block converted to a 16×16×16 chiseled structure."));
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onInteractStructure(PlayerInteractEntityEvent event) {
            if (!(event.getRightClicked() instanceof Interaction anchor)
                    || !anchor.getPersistentDataContainer().has(anchorKey, PersistentDataType.BYTE)) return;
            Player player = event.getPlayer();
            if (!canEdit(player, anchor)) {
                player.sendActionBar(Component.text("You do not own this chiseled structure."));
                return;
            }
            event.setCancelled(true);
            ItemStack hand = player.getInventory().getItemInMainHand();
            Tool held = tool(hand);
            VoxelData data = load(anchor);
            int[] cell = targetedCell(player, anchor);
            if (cell == null) return;
            int x = cell[0], y = cell[1], z = cell[2];
            Material bit = bitMaterial(hand);

            if (held == Tool.WRENCH) {
                rotate(data, player.getFacing());
                rebuild(anchor, data);
                player.sendActionBar(Component.text("Structure rotated."));
                damageTool(player, EquipmentSlot.HAND);
                return;
            }
            if (held == Tool.BLUEPRINT) {
                ItemMeta meta = hand.getItemMeta();
                meta.getPersistentDataContainer().set(blueprintKey, PersistentDataType.STRING, data.encode());
                meta.displayName(Component.text("Written Bit Blueprint"));
                hand.setItemMeta(meta);
                player.sendMessage(Component.text("Blueprint copied. Use it on an empty block to paste."));
                return;
            }
            if (bit != null) {
                int idx = index(x, y, z);
                if (data.cells[idx] != 0) {
                    int best = -1;
                    int bestDistance = Integer.MAX_VALUE;
                    for (int zz = 0; zz < SIZE; zz++) for (int yy = 0; yy < SIZE; yy++)
                        for (int xx = 0; xx < SIZE; xx++) {
                            int candidate = index(xx, yy, zz);
                            if (data.cells[candidate] != 0) continue;
                            int distance = Math.abs(xx - x) + Math.abs(yy - y) + Math.abs(zz - z);
                            if (distance < bestDistance) { bestDistance = distance; best = candidate; }
                        }
                    idx = best;
                }
                if (idx < 0) {
                    player.sendActionBar(Component.text("This chiseled block is full."));
                    return;
                }
                data.cells[idx] = (byte) data.materialIndex(bit);
                consumeOne(player, hand);
                rebuild(anchor, data);
                return;
            }
            if (held == Tool.IRON_CHISEL || held == Tool.DIAMOND_CHISEL || held == Tool.GOLD_CHISEL
                    || held == Tool.SAW) {
                int radius = held == Tool.DIAMOND_CHISEL ? 0 : held == Tool.GOLD_CHISEL ? 3
                        : held == Tool.SAW ? 1 : 1;
                int removed = 0;
                Map<Material, Integer> drops = new HashMap<>();
                for (int zz = Math.max(0, z - radius); zz <= Math.min(15, z + radius); zz++)
                    for (int yy = Math.max(0, y - radius); yy <= Math.min(15, y + radius); yy++)
                        for (int xx = Math.max(0, x - radius); xx <= Math.min(15, x + radius); xx++) {
                            int idx = index(xx, yy, zz);
                            if (data.cells[idx] == 0) continue;
                            Material drop = data.material(Byte.toUnsignedInt(data.cells[idx]));
                            if (drop != Material.AIR) drops.merge(drop, 1, Integer::sum);
                            data.cells[idx] = 0;
                            removed++;
                        }
                if (removed == 0) return;
                drops.forEach((material, count) -> giveBits(player, material, count));
                rebuild(anchor, data);
                damageTool(player, EquipmentSlot.HAND);
                player.sendActionBar(Component.text("Removed " + removed + " bits."));
                if (isEmpty(data)) removeStructure(anchor);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onLeftClickStructure(EntityDamageByEntityEvent event) {
            if (!(event.getDamager() instanceof Player player)
                    || !(event.getEntity() instanceof Interaction anchor)
                    || !anchor.getPersistentDataContainer().has(anchorKey, PersistentDataType.BYTE)) return;
            if (!canEdit(player, anchor)) return;
            event.setCancelled(true);
            ItemStack hand = player.getInventory().getItemInMainHand();
            Tool held = tool(hand);
            if (held != Tool.IRON_CHISEL && held != Tool.DIAMOND_CHISEL && held != Tool.GOLD_CHISEL
                    && held != Tool.SAW) return;
            VoxelData data = load(anchor);
            int[] cell = targetedCell(player, anchor);
            if (cell == null) return;
            int radius = held == Tool.DIAMOND_CHISEL ? 0 : held == Tool.GOLD_CHISEL ? 3 : held == Tool.SAW ? 1 : 1;
            int removed = 0;
            Map<Material, Integer> drops = new HashMap<>();
            for (int z = Math.max(0, cell[2] - radius); z <= Math.min(15, cell[2] + radius); z++)
                for (int y = Math.max(0, cell[1] - radius); y <= Math.min(15, cell[1] + radius); y++)
                    for (int x = Math.max(0, cell[0] - radius); x <= Math.min(15, cell[0] + radius); x++) {
                        int idx = index(x, y, z);
                        if (data.cells[idx] == 0) continue;
                        Material drop = data.material(Byte.toUnsignedInt(data.cells[idx]));
                        if (drop != Material.AIR) drops.merge(drop, 1, Integer::sum);
                        data.cells[idx] = 0;
                        removed++;
                    }
            if (removed == 0) return;
            drops.forEach((material, count) -> giveBits(player, material, count));
            rebuild(anchor, data);
            damageTool(player, EquipmentSlot.HAND);
            player.sendActionBar(Component.text("Removed " + removed + " bits."));
            if (isEmpty(data)) removeStructure(anchor);
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onPlaceBits(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                    || event.getClickedBlock() == null) return;
            Player player = event.getPlayer();
            ItemStack hand = player.getInventory().getItemInMainHand();
            Material material = bitMaterial(hand);
            if (material == null) return;
            Block target = event.getClickedBlock().getRelative(event.getBlockFace());
            if (!target.getType().isAir()) return;
            event.setCancelled(true);
            VoxelData data = new VoxelData();
            data.cells[index(8, 8, 8)] = (byte) data.materialIndex(material);
            createStructure(target.getLocation(), data, player.getUniqueId());
            consumeOne(player, hand);
        }

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        public void onBlueprintPaste(PlayerInteractEvent event) {
            if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                    || event.getClickedBlock() == null) return;
            ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
            if (tool(hand) != Tool.BLUEPRINT || !hand.hasItemMeta()) return;
            String encoded = hand.getItemMeta().getPersistentDataContainer()
                    .get(blueprintKey, PersistentDataType.STRING);
            if (encoded == null) return;
            Block target = event.getClickedBlock().getRelative(event.getBlockFace());
            if (!target.getType().isAir()) return;
            VoxelData blueprint;
            try {
                blueprint = VoxelData.decode(encoded);
            } catch (IllegalArgumentException ex) {
                event.getPlayer().sendMessage(Component.text("This blueprint is damaged and cannot be placed."));
                return;
            }
            event.setCancelled(true);
            createStructure(target.getLocation(), blueprint, event.getPlayer().getUniqueId());
            if (event.getPlayer().getGameMode() != GameMode.CREATIVE) consumeOne(event.getPlayer(), hand);
        }
    }

    private boolean canEdit(Player player, Interaction anchor) {
        String owner = anchor.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return player.hasPermission("chiselsbits.admin")
                || (owner != null && owner.equals(player.getUniqueId().toString()));
    }

    private boolean isEmpty(VoxelData data) {
        for (byte b : data.cells) if (b != 0) return false;
        return true;
    }

    private void consumeOne(Player player, ItemStack hand) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
    }

    private void damageTool(Player player, EquipmentSlot slot) {
        ItemStack item = slot == EquipmentSlot.HAND ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (item == null || item.getType().getMaxDurability() <= 0 || player.getGameMode() == GameMode.CREATIVE) return;
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable damageable) {
            damageable.setDamage(damageable.getDamage() + 1);
            item.setItemMeta((ItemMeta) damageable);
            if (damageable.getDamage() >= item.getType().getMaxDurability()) {
                if (slot == EquipmentSlot.HAND) player.getInventory().setItemInMainHand(null);
                else player.getInventory().setItemInOffHand(null);
            }
        }
    }

}