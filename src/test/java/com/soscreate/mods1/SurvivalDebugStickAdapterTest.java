package com.soscreate.mods1;

import com.soscreate.universalmodsync.ModAdapter;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SurvivalDebugStickAdapterTest {

    @Test
    void adapterMatchesOnlyTheExactModArchiveName() {
        SurvivalDebugStickAdapter adapter = new SurvivalDebugStickAdapter();

        assertEquals(Set.of("survival-debug-mod-1.2.2_1.21.2.jar"),
                adapter.supportedModFiles());
        assertTrue(ModAdapter.targetsFile(adapter.supportedModFiles(),
                "survival-debug-mod-1.2.2_1.21.2.jar"));
        assertTrue(ModAdapter.targetsFile(adapter.supportedModFiles(),
                "SURVIVAL-DEBUG-MOD-1.2.2_1.21.2.JAR"));
        assertFalse(ModAdapter.targetsFile(adapter.supportedModFiles(),
                "survival-debug-mod-1.2.2_1.21.2-fabric.jar"));
    }

    @Test
    void parsesBlockStateProperties() {
        Map<String, String> properties = SurvivalDebugStickAdapter.readProperties(
                "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");

        assertEquals("north", properties.get("facing"));
        assertEquals("bottom", properties.get("half"));
        assertEquals("straight", properties.get("shape"));
        assertEquals("false", properties.get("waterlogged"));
        assertTrue(SurvivalDebugStickAdapter.readProperties("minecraft:stone").isEmpty());
    }

    @Test
    void replacesOnlyTheRequestedProperty() {
        String state = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]";
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight]",
                SurvivalDebugStickAdapter.replaceProperty(state, "facing", "east"));
        assertThrows(IllegalArgumentException.class,
                () -> SurvivalDebugStickAdapter.replaceProperty(state, "missing", "east"));
    }

    @Test
    void appliesTheOriginalModsDefaultBlockWhitelist() {
        assertTrue(SurvivalDebugStickAdapter.isDefaultBlockAllowed(Material.IRON_BARS));
        assertTrue(SurvivalDebugStickAdapter.isDefaultBlockAllowed(Material.BAMBOO));
        assertTrue(SurvivalDebugStickAdapter.isDefaultBlockAllowed(Material.OAK_STAIRS));
        assertTrue(SurvivalDebugStickAdapter.isDefaultBlockAllowed(Material.COBBLESTONE_WALL));
        assertTrue(SurvivalDebugStickAdapter.isDefaultBlockAllowed(Material.GLASS_PANE));
        assertTrue(SurvivalDebugStickAdapter.isDefaultBlockAllowed(Material.OAK_FENCE));
        assertTrue(SurvivalDebugStickAdapter.isDefaultBlockAllowed(Material.OAK_SLAB));
        assertFalse(SurvivalDebugStickAdapter.isDefaultBlockAllowed(Material.STONE));
    }

    @Test
    void appliesTheOriginalModsPropertyRestrictions() {
        assertFalse(SurvivalDebugStickAdapter.isDefaultPropertyAllowed(
                Material.OAK_STAIRS, "waterlogged", "false"));
        assertTrue(SurvivalDebugStickAdapter.isDefaultPropertyAllowed(
                Material.OAK_STAIRS, "facing", "north"));

        assertTrue(SurvivalDebugStickAdapter.isDefaultPropertyAllowed(
                Material.BAMBOO, "leaves", "large"));
        assertFalse(SurvivalDebugStickAdapter.isDefaultPropertyAllowed(
                Material.BAMBOO, "leaves", "small"));
        assertTrue(SurvivalDebugStickAdapter.isDefaultPropertyAllowed(
                Material.BAMBOO, "age", "24"));
        assertFalse(SurvivalDebugStickAdapter.isDefaultPropertyAllowed(
                Material.BAMBOO, "stage", "1"));

        assertTrue(SurvivalDebugStickAdapter.isDefaultPropertyAllowed(
                Material.OAK_SLAB, "type", "top"));
        assertTrue(SurvivalDebugStickAdapter.isDefaultPropertyAllowed(
                Material.OAK_SLAB, "type", "bottom"));
        assertFalse(SurvivalDebugStickAdapter.isDefaultPropertyAllowed(
                Material.OAK_SLAB, "type", "double"));
        assertFalse(SurvivalDebugStickAdapter.isDefaultPropertyAllowed(
                Material.OAK_SLAB, "waterlogged", "false"));
    }
}
