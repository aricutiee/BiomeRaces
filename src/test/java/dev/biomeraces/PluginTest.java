package dev.biomeraces;

import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PluginTest {
    ServerMock server;
    BiomeRaces plugin;
    PlayerMock player;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); plugin = MockBukkit.load(BiomeRaces.class); player = server.addPlayer("Miner");
        assertTrue(plugin.isEnabled());
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    @Test void choicesAndCooldownsSurviveDiskReload() throws Exception {
        PlayerState state = plugin.state(player); state.base = Race.HOLLOW_EYED;
        state.offenseUntil = System.currentTimeMillis() + 12000; state.defenseUntil = System.currentTimeMillis() + 25000;
        plugin.persist(); PlayerStore reloaded = new PlayerStore(plugin.getDataFolder().toPath().resolve("players.yml"));
        PlayerState copy = reloaded.get(player.getUniqueId());
        assertEquals(Race.HOLLOW_EYED, copy.base); assertEquals(state.offenseUntil, copy.offenseUntil); assertEquals(state.defenseUntil, copy.defenseUntil);
        assertFalse(copy.dragon); assertEquals(player.getUniqueId(), reloaded.lookup("Miner"));
    }
    @Test void dragonOnlyUsesHandsAndCancelsPending() {
        plugin.state(player).base = Race.DWARF;
        player.getInventory().setItem(12, new ItemStack(Material.DRAGON_EGG)); server.getScheduler().performTicks(90);
        assertFalse(plugin.state(player).dragon); assertEquals(-1, plugin.state(player).transformationStep);
        player.getInventory().setItemInOffHand(new ItemStack(Material.DRAGON_EGG)); server.getScheduler().performTicks(25);
        assertFalse(plugin.state(player).dragon); assertTrue(plugin.state(player).transformationStep > 0);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR)); server.getScheduler().performTicks(90);
        assertFalse(plugin.state(player).dragon); assertEquals(Race.DWARF, plugin.state(player).active());
    }
    @Test void transformationDoesNotHealAndRestoresOtherHealthModifier() {
        plugin.state(player).base = Race.PETALFOLK;
        var maximum = player.getAttribute(Attribute.MAX_HEALTH);
        maximum.addModifier(new AttributeModifier(new NamespacedKey("external", "health"), 6, AttributeModifier.Operation.ADD_NUMBER));
        player.setHealth(10);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DRAGON_EGG)); server.getScheduler().performTicks(82);
        assertTrue(plugin.state(player).dragon); assertEquals(34, maximum.getValue()); assertEquals(10, player.getHealth());
        assertTrue(player.hasPotionEffect(PotionEffectType.STRENGTH));
        player.setHealth(33); player.getInventory().setItemInMainHand(new ItemStack(Material.AIR)); server.getScheduler().performOneTick();
        assertEquals(26, maximum.getValue()); assertEquals(26, player.getHealth());
        assertEquals(Race.PETALFOLK, plugin.state(player).active()); assertFalse(player.hasPotionEffect(PotionEffectType.STRENGTH));
    }
    @Test void handSwapDoesNotRestartTransformationOrStackHealth() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.DRAGON_EGG)); server.getScheduler().performTicks(40);
        int step = plugin.state(player).transformationStep;
        player.getInventory().setItemInMainHand(new ItemStack(Material.AIR)); player.getInventory().setItemInOffHand(new ItemStack(Material.DRAGON_EGG));
        server.getScheduler().performOneTick(); assertEquals(step, plugin.state(player).transformationStep);
        server.getScheduler().performTicks(50); assertTrue(plugin.state(player).dragon);
        server.getScheduler().performTicks(100); assertEquals(28, player.getAttribute(Attribute.MAX_HEALTH).getValue());
    }
    @Test void strongerExternalPotionIsNeverReplacedOrCleared() {
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 1200, 2));
        assertFalse(plugin.effects().grant(player, PotionEffectType.SPEED, 600, true));
        plugin.effects().clearSelf(player);
        assertEquals(2, player.getPotionEffect(PotionEffectType.SPEED).getAmplifier());
        assertEquals(1200, player.getPotionEffect(PotionEffectType.SPEED).getDuration());
    }
    @Test void externalPotionAppliedDuringRaceLeaseSurvivesCleanup() {
        assertTrue(plugin.effects().grant(player, PotionEffectType.SPEED, 600, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 2));
        plugin.effects().clearSelf(player);
        var effect = player.getPotionEffect(PotionEffectType.SPEED);
        assertNotNull(effect); assertEquals(2, effect.getAmplifier()); assertEquals(200, effect.getDuration());
    }
    @Test void equalStrengthShortExternalEffectKeepsItsOwnExpiry() {
        assertTrue(plugin.effects().grant(player, PotionEffectType.SPEED, 600, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 0));
        plugin.effects().clearSelf(player);
        assertEquals(40, player.getPotionEffect(PotionEffectType.SPEED).getDuration());
    }
    @Test void ordinaryPlayersCannotSetAndAdminCannotChooseDragonborn() {
        player.setOp(false); player.performCommand("race set Miner dwarf"); assertNull(plugin.state(player).base);
        player.setOp(true); player.performCommand("race set Miner dragonborn"); assertNull(plugin.state(player).base);
        player.performCommand("race set Miner dwarf"); assertEquals(Race.DWARF, plugin.state(player).base);
        plugin.state(player).defenseUntil = 9999999999999L;
        player.performCommand("race reset Miner"); assertNull(plugin.state(player).base); assertEquals(9999999999999L, plugin.state(player).defenseUntil);
    }
    @Test void selectionMenuLocksAfterOneChoice() {
        player.performCommand("race");
        assertEquals(27, player.getOpenInventory().getTopInventory().getSize());
        assertEquals(Material.LILY_PAD, player.getOpenInventory().getTopInventory().getItem(9).getType());
        player.simulateInventoryClick(player.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, 15);
        assertEquals(Race.HOLLOW_EYED, plugin.state(player).base);
        player.simulateInventoryClick(player.getOpenInventory(), org.bukkit.event.inventory.ClickType.LEFT, 17);
        assertEquals(Race.HOLLOW_EYED, plugin.state(player).base);
    }
    @Test void environmentalPassivesAndLightDebounce() {
        plugin.state(player).base = Race.ROOTBOUND;
        player.getLocation().subtract(0, .05, 0).getBlock().setType(Material.MUD);
        plugin.runtime().updatePassives(player); assertTrue(player.hasPotionEffect(PotionEffectType.SPEED));
        player.getLocation().subtract(0, .05, 0).getBlock().setType(Material.STONE);
        plugin.runtime().updatePassives(player); assertFalse(player.hasPotionEffect(PotionEffectType.SPEED));
        plugin.state(player).base = Race.HOLLOW_EYED;
        var eyes = (org.mockbukkit.mockbukkit.block.BlockMock) player.getEyeLocation().getBlock();
        eyes.setLightFromSky((byte) 0); eyes.setLightFromBlocks((byte) 7);
        server.getScheduler().performTicks(16); assertTrue(player.hasPotionEffect(PotionEffectType.NIGHT_VISION));
        eyes.setLightFromBlocks((byte) 8); server.getScheduler().performTicks(5);
        assertTrue(player.hasPotionEffect(PotionEffectType.NIGHT_VISION)); assertFalse(plugin.runtime().lowLight(player));
        server.getScheduler().performTicks(15); assertFalse(player.hasPotionEffect(PotionEffectType.NIGHT_VISION));
        eyes.setLightFromBlocks((byte) 0); eyes.setLightFromSky((byte) 15); server.getScheduler().performTicks(20);
        assertFalse(player.hasPotionEffect(PotionEffectType.NIGHT_VISION));
    }
    @Test void expiredIncomingEffectIsNotRestoredAndCancelledChangeIsIgnored() {
        assertTrue(plugin.effects().grant(player, PotionEffectType.SPEED, 600, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 1, 2));
        server.getScheduler().performTicks(2); plugin.effects().clearSelf(player);
        assertFalse(player.hasPotionEffect(PotionEffectType.SPEED));
        assertTrue(plugin.effects().grant(player, PotionEffectType.SPEED, 600, true));
        var cancelled = new org.bukkit.event.entity.EntityPotionEffectEvent(player, player.getPotionEffect(PotionEffectType.SPEED),
            new PotionEffect(PotionEffectType.SPEED, 400, 2), org.bukkit.event.entity.EntityPotionEffectEvent.Cause.PLUGIN,
            org.bukkit.event.entity.EntityPotionEffectEvent.Action.CHANGED, true);
        cancelled.setCancelled(true); server.getPluginManager().callEvent(cancelled);
        plugin.effects().clearSelf(player); assertFalse(player.hasPotionEffect(PotionEffectType.SPEED));
    }
    @Test void quitAndRejoinRetainCooldownAndBase() {
        plugin.state(player).base = Race.PETALFOLK; plugin.state(player).offenseUntil = System.currentTimeMillis() + 12000;
        plugin.state(player).defenseUntil = System.currentTimeMillis() + 30000;
        player.getInventory().setItemInOffHand(new ItemStack(Material.DRAGON_EGG)); server.getScheduler().performTicks(82);
        assertTrue(plugin.state(player).dragon);
        long offense = plugin.state(player).offenseUntil, defense = plugin.state(player).defenseUntil;
        player.disconnect(); assertFalse(plugin.state(player).dragon); assertEquals(20, player.getAttribute(Attribute.MAX_HEALTH).getValue());
        player.reconnect(); assertFalse(plugin.state(player).dragon); assertEquals(Race.PETALFOLK, plugin.state(player).base);
        assertEquals(offense, plugin.state(player).offenseUntil); assertEquals(defense, plugin.state(player).defenseUntil);
    }
    @Test void shorterExternalDebuffCannotTruncateNewOffenseButMilkCanClearIt() {
        player.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 1, 0));
        plugin.effects().grant(player, PotionEffectType.POISON, 40, false);
        server.getScheduler().performTicks(3);
        assertTrue(player.hasPotionEffect(PotionEffectType.POISON));
        assertTrue(player.getPotionEffect(PotionEffectType.POISON).getDuration() <= 38);
        // Public removal events represent a milk/command/plugin clear and cancel the remaining intent.
        player.removePotionEffect(PotionEffectType.POISON);
        server.getScheduler().performTicks(3); assertFalse(player.hasPotionEffect(PotionEffectType.POISON));
    }
    @Test void rejectedPotionApplicationDoesNotRetryAfterCancellation() {
        var blocker = new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler public void effect(org.bukkit.event.entity.EntityPotionEffectEvent event) {
                if (event.getNewEffect() != null && event.getModifiedType().equals(PotionEffectType.POISON)) event.setCancelled(true);
            }
        };
        server.getPluginManager().registerEvents(blocker, plugin);
        assertFalse(plugin.effects().grant(player, PotionEffectType.POISON, 40, false));
        org.bukkit.event.HandlerList.unregisterAll(blocker);
        server.getScheduler().performTicks(5); assertFalse(player.hasPotionEffect(PotionEffectType.POISON));
    }
}
