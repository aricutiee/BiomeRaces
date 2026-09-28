package dev.biomeraces;

import com.google.common.base.Function;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.*;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class CombatIntegrationTest {
    // MockBukkit does not implement fluid immersion. This fixture explicitly represents a dry player.
    static class DryPlayer extends PlayerMock {
        DryPlayer(ServerMock server, String name) { super(server, name); }
        @Override public boolean isUnderWater() { return false; }
        @Override public boolean isInWater() { return false; }
    }
    ServerMock server; BiomeRaces plugin; PlayerMock player, target;
    @BeforeEach void setup() {
        server = MockBukkit.mock(); plugin = MockBukkit.load(BiomeRaces.class);
        PlayerMock original = new DryPlayer(server, "Attacker"); server.addPlayer(original);
        player = spy(original); target = server.addPlayer("Target");
        doReturn(1f).when(player).getAttackCooldown();
        doReturn(false).when(player).isHandRaised();
        doReturn(false).when(player).isRiptiding();
        player.getWorld().setPVP(true);
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }
    EntityDamageByEntityEvent hit(double damage) { return hit(target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, damage, true); }
    EntityDamageByEntityEvent hit(LivingEntity victim, EntityDamageEvent.DamageCause cause, double damage, boolean pre) {
        if (pre) server.getPluginManager().callEvent(new PrePlayerAttackEntityEvent(player, victim, true));
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(player, victim, cause, damage);
        server.getPluginManager().callEvent(event); return event;
    }
    int count() { return plugin.state(player).chain.count(System.currentTimeMillis(), 8000); }
    @Test void thirdHitHeavyStrikeAndCooldownDoesNotBuild() {
        plugin.state(player).base = Race.DWARF;
        assertEquals(5, hit(5).getDamage()); assertEquals(1, count());
        assertEquals(5, hit(5).getDamage()); assertEquals(2, count());
        assertEquals(6, hit(5).getDamage()); assertEquals(0, count());
        long expiry = plugin.state(player).offenseUntil; assertTrue(expiry > System.currentTimeMillis());
        for (int i = 0; i < 4; i++) assertEquals(5, hit(5).getDamage());
        assertEquals(0, count()); assertEquals(expiry, plugin.state(player).offenseUntil);
    }
    @Test void cancelledThirdHitDoesNotConsumeOrAdvance() {
        plugin.state(player).base = Race.DWARF; hit(5); hit(5);
        Listener cancel = new Listener() { @EventHandler(priority = EventPriority.HIGHEST) public void damage(EntityDamageEvent event) { event.setCancelled(true); } };
        server.getPluginManager().registerEvents(cancel, plugin);
        assertTrue(hit(5).isCancelled()); assertEquals(2, count()); assertEquals(0, plugin.state(player).offenseUntil);
        HandlerList.unregisterAll(cancel); assertEquals(6, hit(5).getDamage()); assertEquals(0, count());
    }
    @Test void ignoresWeakZeroSweepSyntheticPassiveAndPvpOff() {
        plugin.state(player).base = Race.DWARF;
        doReturn(.89f).when(player).getAttackCooldown(); hit(5); assertEquals(0, count());
        doReturn(1f).when(player).getAttackCooldown(); hit(0);
        hit(target, EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK, 5, true);
        hit(target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5, false);
        Cow cow = mock(Cow.class); when(cow.getUniqueId()).thenReturn(UUID.randomUUID());
        hit(cow, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5, true);
        player.getWorld().setPVP(false); hit(5); assertEquals(0, count());
    }
    @Test void changingTargetsAndTargetDeathReset() {
        plugin.state(player).base = Race.DWARF; hit(5); hit(5);
        PlayerMock another = server.addPlayer("Other"); hit(another, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 5, true);
        assertEquals(1, count()); hit(5); assertEquals(1, count());
        plugin.combat().forget(target.getUniqueId()); assertEquals(0, count());
    }
    @Test void dragonProgressionPassesThroughDamageEvent() {
        plugin.state(player).dragon = true; player.getInventory().setItemInOffHand(new org.bukkit.inventory.ItemStack(Material.DRAGON_EGG));
        double[] expected = {13, 13, 15, 8, 10, 12, 14};
        for (double damage : expected) assertEquals(damage, hit(13).getDamage());
        assertEquals(0, plugin.state(player).offenseUntil);
    }
    @Test void defensesApplyAfterDragonReplacement() {
        plugin.state(player).dragon = true; player.getInventory().setItemInOffHand(new org.bukkit.inventory.ItemStack(Material.DRAGON_EGG));
        plugin.state(player).chain.commit(target.getUniqueId(), 3, System.currentTimeMillis());
        plugin.state(target).base = Race.ROOTBOUND; target.getLocation().subtract(0, .05, 0).getBlock().setType(Material.MUD);
        assertEquals(7.2, hit(20).getFinalDamage(), 1e-6);
        assertTrue(plugin.state(target).defenseUntil > System.currentTimeMillis());
    }
    @Test void onlyAssociatedKnockbackIsReduced() {
        plugin.state(target).base = Race.ROOTBOUND; target.getLocation().subtract(0, .05, 0).getBlock().setType(Material.MUD);
        hit(5);
        EntityPushedByEntityAttackEvent push = new EntityPushedByEntityAttackEvent(target, EntityKnockbackEvent.Cause.ENTITY_ATTACK, player, new Vector(1, .4, 0));
        server.getPluginManager().callEvent(push); assertEquals(.4, push.getKnockback().getX(), 1e-9);
        PlayerMock other = server.addPlayer("Unrelated");
        EntityPushedByEntityAttackEvent unrelated = new EntityPushedByEntityAttackEvent(target, EntityKnockbackEvent.Cause.ENTITY_ATTACK, other, new Vector(1, .4, 0));
        server.getPluginManager().callEvent(unrelated); assertEquals(1, unrelated.getKnockback().getX());
    }
    @Test void stoneLandingCapsActualProtectedDamageAtEight() {
        plugin.state(target).base = Race.DWARF; target.getLocation().subtract(0, .05, 0).getBlock().setType(Material.STONE);
        Map<EntityDamageEvent.DamageModifier, Double> modifiers = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        Map<EntityDamageEvent.DamageModifier, Function<? super Double, Double>> functions = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, 30d);
        modifiers.put(EntityDamageEvent.DamageModifier.MAGIC, -15d);
        functions.put(EntityDamageEvent.DamageModifier.BASE, value -> 0d);
        functions.put(EntityDamageEvent.DamageModifier.MAGIC, value -> -value * .5);
        EntityDamageEvent fall = new EntityDamageEvent(target, EntityDamageEvent.DamageCause.FALL, modifiers, functions);
        server.getPluginManager().callEvent(fall); assertEquals(7, fall.getFinalDamage(), 1e-6);
        assertTrue(plugin.state(target).defenseUntil > System.currentTimeMillis());
    }
    @Test void cancelledDefenseDoesNotSpendCooldown() {
        plugin.state(target).base = Race.DWARF; target.getLocation().subtract(0, .05, 0).getBlock().setType(Material.STONE);
        EntityDamageEvent event = new EntityDamageEvent(target, EntityDamageEvent.DamageCause.FALL, 10);
        event.setCancelled(true); server.getPluginManager().callEvent(event); assertEquals(0, plugin.state(target).defenseUntil);
    }
    @Test void petalShieldIsAfterHitAndNeverRefills() {
        plugin.state(target).base = Race.PETALFOLK;
        hit(5); assertFalse(target.hasPotionEffect(PotionEffectType.ABSORPTION));
        server.getScheduler().performOneTick(); assertTrue(target.hasPotionEffect(PotionEffectType.ABSORPTION));
        long until = plugin.state(target).defenseUntil; hit(5); server.getScheduler().performOneTick();
        assertEquals(until, plugin.state(target).defenseUntil);
    }
    @Test void lethalHitDoesNotGrantPetalShield() {
        plugin.state(target).base = Race.PETALFOLK;
        hit(30); server.getScheduler().performOneTick(); assertFalse(target.hasPotionEffect(PotionEffectType.ABSORPTION));
        assertEquals(0, plugin.state(target).defenseUntil);
    }
    @Test void fullyBlockedAttackDoesNotBuildButAbsorptionDamageDoes() {
        plugin.state(player).base = Race.DWARF;
        Map<EntityDamageEvent.DamageModifier, Double> modifiers = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        Map<EntityDamageEvent.DamageModifier, Function<? super Double, Double>> functions = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, 5d); modifiers.put(EntityDamageEvent.DamageModifier.BLOCKING, -5d);
        functions.put(EntityDamageEvent.DamageModifier.BASE, value -> 0d); functions.put(EntityDamageEvent.DamageModifier.BLOCKING, value -> -value);
        server.getPluginManager().callEvent(new PrePlayerAttackEntityEvent(player, target, true));
        server.getPluginManager().callEvent(new EntityDamageByEntityEvent(player, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, modifiers, functions));
        assertEquals(0, count());
        modifiers.remove(EntityDamageEvent.DamageModifier.BLOCKING); functions.remove(EntityDamageEvent.DamageModifier.BLOCKING);
        modifiers.put(EntityDamageEvent.DamageModifier.ABSORPTION, -5d); functions.put(EntityDamageEvent.DamageModifier.ABSORPTION, value -> -value);
        server.getPluginManager().callEvent(new PrePlayerAttackEntityEvent(player, target, true));
        server.getPluginManager().callEvent(new EntityDamageByEntityEvent(player, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, modifiers, functions));
        assertEquals(1, count());
    }
    @Test void rootDefenseAlsoReducesDamageToAbsorption() {
        plugin.state(target).base = Race.ROOTBOUND; target.getLocation().subtract(0, .05, 0).getBlock().setType(Material.MUD);
        Map<EntityDamageEvent.DamageModifier, Double> modifiers = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        Map<EntityDamageEvent.DamageModifier, Function<? super Double, Double>> functions = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, 4d); modifiers.put(EntityDamageEvent.DamageModifier.ABSORPTION, -4d);
        functions.put(EntityDamageEvent.DamageModifier.BASE, value -> 0d); functions.put(EntityDamageEvent.DamageModifier.ABSORPTION, value -> -Math.min(value, 4));
        var event = new EntityDamageByEntityEvent(player, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, modifiers, functions);
        server.getPluginManager().callEvent(event); assertEquals(3.6, CombatListener.actualDamage(event), 1e-6);
        assertTrue(plugin.state(target).defenseUntil > System.currentTimeMillis());
    }
    @Test void stoneLandingDoesNotAlsoEraseExistingAbsorptionDamage() {
        plugin.state(target).base = Race.DWARF; target.getLocation().subtract(0, .05, 0).getBlock().setType(Material.STONE);
        Map<EntityDamageEvent.DamageModifier, Double> modifiers = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        Map<EntityDamageEvent.DamageModifier, Function<? super Double, Double>> functions = new EnumMap<>(EntityDamageEvent.DamageModifier.class);
        modifiers.put(EntityDamageEvent.DamageModifier.BASE, 12d); modifiers.put(EntityDamageEvent.DamageModifier.ABSORPTION, -4d);
        functions.put(EntityDamageEvent.DamageModifier.BASE, value -> 0d); functions.put(EntityDamageEvent.DamageModifier.ABSORPTION, value -> -Math.min(value, 4));
        var event = new EntityDamageEvent(target, EntityDamageEvent.DamageCause.FALL, modifiers, functions);
        server.getPluginManager().callEvent(event);
        assertEquals(0, event.getFinalDamage(), 1e-6);
        assertEquals(4, CombatListener.absorptionUsed(event), 1e-6);
        assertEquals(8, 12 - CombatListener.actualDamage(event), 1e-6);
    }
    @Test void allThreeStatusOffensesAndBloomHealing() {
        Race[] races = {Race.BOGBORN, Race.ROOTBOUND, Race.HOLLOW_EYED};
        PotionEffectType[] types = {PotionEffectType.POISON, PotionEffectType.SLOWNESS, PotionEffectType.DARKNESS};
        for (int i = 0; i < races.length; i++) {
            plugin.state(player).base = races[i]; plugin.state(player).offenseUntil = 0;
            hit(4); hit(4); hit(4);
            server.getScheduler().performOneTick(); assertTrue(target.hasPotionEffect(types[i]));
            plugin.effects().clear(target);
        }
        plugin.state(player).base = Race.PETALFOLK; plugin.state(player).offenseUntil = 0; player.setHealth(10);
        hit(4); hit(4); hit(4); server.getScheduler().performOneTick(); assertEquals(11, player.getHealth());
    }
}
