package dev.biomeraces;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffectType;
import java.util.*;

public final class CombatListener implements Listener {
    private record Swing(UUID target, int tick, float charge, boolean mainHand, PrePlayerAttackEntityEvent event) {}
    private record Attack(Player attacker, LivingEntity victim, Race race, int hit, long time, boolean proc) {}
    private record Defense(Player player, Race race, double before, boolean petal, double healthBefore) {}
    private record KnockbackReceipt(EntityDamageByEntityEvent hit, int tick) {}
    private final BiomeRaces plugin;
    private final Map<UUID, Swing> swings = new HashMap<>();
    private final Map<EntityDamageEvent, Attack> attacks = new IdentityHashMap<>();
    private final Map<EntityDamageEvent, Defense> defenses = new IdentityHashMap<>();
    private final Map<UUID, KnockbackReceipt> knockbacks = new HashMap<>();
    CombatListener(BiomeRaces plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void preAttack(PrePlayerAttackEntityEvent event) {
        Player player = event.getPlayer(); plugin.runtime().syncHands(player);
        // Offhand spear charging and Riptide contact attacks are not main-hand clicks.
        boolean main = !(player.isHandRaised() && player.getActiveItemHand() == EquipmentSlot.OFF_HAND) && !player.isRiptiding();
        Entity target = event.getAttacked() instanceof ComplexEntityPart part ? part.getParent() : event.getAttacked();
        swings.put(player.getUniqueId(), new Swing(target.getUniqueId(), Bukkit.getCurrentTick(), player.getAttackCooldown(), main, event));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void begin(EntityDamageEvent event) {
        knockbacks.remove(event.getEntity().getUniqueId());
        if (!(event instanceof EntityDamageByEntityEvent hit) || !(hit.getDamager() instanceof Player player)) return;
        Swing swing = swings.remove(player.getUniqueId()); // one event consumes the provenance token, even if cancelled
        if (hit.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (swing == null || swing.tick != Bukkit.getCurrentTick() || swing.event.isCancelled()
                || !swing.target.equals(event.getEntity().getUniqueId()) || !(event.getEntity() instanceof LivingEntity victim)) return;
        plugin.runtime().syncHands(player);
        boolean allowed = !(victim instanceof Player other) || (player.getWorld().getPVP() && !player.getUniqueId().equals(other.getUniqueId()));
        if (!CombatChain.eligible(true, swing.mainHand, swing.charge, plugin.settings().number("combat.minimum-attack-charge"),
                validTarget(victim), allowed, event.isCancelled(), actualDamage(event))) return;
        PlayerState state = plugin.state(player); Race race = state.active();
        long now = System.currentTimeMillis();
        if (race == null || (!state.dragon && now < state.offenseUntil)) return;
        long timeout = plugin.settings().millis(state.dragon ? "dragonborn.chain-timeout-seconds" : "combat.base-chain-timeout-seconds");
        int number = state.chain.preview(victim.getUniqueId(), now, timeout);
        attacks.put(event, new Attack(player, victim, race, number, now, number >= plugin.settings().integer("combat.base-hits")));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void offensiveDamage(EntityDamageEvent event) {
        Attack attack = attacks.get(event); if (attack == null || actualDamage(event) <= 0) return;
        Settings settings = plugin.settings();
        if (attack.race == Race.DRAGONBORN) {
            event.setDamage(CombatChain.dragonDamage(event.getDamage(), attack.hit, settings.number("dragonborn.third-hit-bonus"),
                settings.integer("dragonborn.replacement-start-hit"), settings.number("dragonborn.damage-per-hit-number")));
        } else if (attack.race == Race.DWARF && attack.proc) {
            event.setDamage(event.getDamage() + settings.number("dwarf.bonus-damage"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void defensiveDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || actualDamage(event) <= 0) return;
        plugin.runtime().syncHands(player);
        PlayerState state = plugin.state(player); Race race = state.active(); Settings settings = plugin.settings();
        if (race == null || race == Race.DRAGONBORN) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && race == Race.PETALFOLK) {
            reduceActual(event, actualDamage(event) * settings.number("petalfolk.fall-reduction")); return;
        }
        if (System.currentTimeMillis() < state.defenseUntil) return;
        double before = event.getFinalDamage();
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && race == Race.DWARF
                && settings.onBlock("dwarf.landing-blocks", EnvironmentRules.standingOn(player)) && before > 0) {
            reduceFinal(event, settings.number("dwarf.fall-prevention"));
            if (event.getFinalDamage() < before) defenses.put(event, new Defense(player, race, before, false, player.getHealth()));
            return;
        }
        if (!incoming(event)) return;
        boolean defend = switch (race) {
            case BOGBORN -> EnvironmentRules.bodyInWater(player);
            case ROOTBOUND -> settings.onBlock("rootbound.defense-blocks", EnvironmentRules.standingOn(player));
            case HOLLOW_EYED -> plugin.runtime().lowLight(player);
            default -> false;
        };
        if (defend) {
            double actualBefore = actualDamage(event);
            reduceActual(event, actualBefore * settings.number(race.key() + ".defense-reduction"));
            if (actualDamage(event) < actualBefore) defenses.put(event, new Defense(player, race, actualBefore, false, player.getHealth()));
        } else if (race == Race.PETALFOLK && before > 0 && player.getHealth() > before
                && !player.hasPotionEffect(PotionEffectType.ABSORPTION) && player.getAbsorptionAmount() <= 0) {
            defenses.put(event, new Defense(player, race, before, true, player.getHealth()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void completed(EntityDamageEvent event) {
        Attack attack = attacks.remove(event); Defense defense = defenses.remove(event);
        if (event.isCancelled()) return;
        double dealt = actualDamage(event);
        if (event.getEntity() instanceof LivingEntity entity && dealt > 0)
            plugin.effects().consumedAbsorption(entity, absorptionUsed(event));
        if (defense != null) {
            if (defense.petal) {
                // Damage has not yet been applied at MONITOR. Wait until the following server tick.
                Bukkit.getScheduler().runTask(plugin, () -> petalAfterHit(event, defense));
            } else if ((defense.race == Race.DWARF ? event.getFinalDamage() : actualDamage(event)) < defense.before) {
                defenseCooldown(defense.player, defense.race);
                if (defense.race == Race.ROOTBOUND && event instanceof EntityDamageByEntityEvent hit)
                    knockbacks.put(defense.player.getUniqueId(), new KnockbackReceipt(hit, Bukkit.getCurrentTick()));
            }
        }
        if (attack == null || dealt <= 0) return;
        PlayerState state = plugin.state(attack.attacker);
        if (state.active() != attack.race) return;
        state.chain.commit(attack.victim.getUniqueId(), attack.hit, attack.time);
        if (attack.race != Race.DRAGONBORN && attack.proc) {
            state.chain.reset(); state.offenseUntil = attack.time + plugin.settings().millis("combat.offensive-cooldown-seconds"); plugin.persist();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!event.isCancelled() && actualDamage(event) > 0 && attack.attacker.isOnline() && !attack.attacker.isDead()
                        && plugin.state(attack.attacker).active() == attack.race) proc(attack);
            });
        }
    }
    private void petalAfterHit(EntityDamageEvent event, Defense defense) {
        Player player = defense.player; PlayerState state = plugin.state(player);
        if (event.isCancelled() || event.getFinalDamage() <= 0 || !player.isOnline() || player.isDead()
                || state.active() != Race.PETALFOLK || state.defenseUntil > System.currentTimeMillis()) return;
        // Totem resurrection is deliberately not considered surviving the hit.
        if (event.getFinalDamage() >= defense.healthBefore) return;
        if (plugin.effects().grant(player, PotionEffectType.ABSORPTION, plugin.settings().ticks("petalfolk.absorption-seconds"), false))
            defenseCooldown(player, Race.PETALFOLK);
    }
    private void defenseCooldown(Player player, Race race) {
        plugin.state(player).defenseUntil = System.currentTimeMillis() + plugin.settings().millis(race.key() + ".defense-cooldown-seconds");
        plugin.persist();
    }
    private void proc(Attack attack) {
        LivingEntity victim = attack.victim; Settings settings = plugin.settings();
        if (!victim.isDead() && victim.isValid()) switch (attack.race) {
            case BOGBORN -> plugin.effects().grant(victim, PotionEffectType.POISON, settings.ticks("bogborn.poison-seconds"), false);
            case ROOTBOUND -> plugin.effects().grant(victim, PotionEffectType.SLOWNESS, settings.ticks("rootbound.slowness-seconds"), false);
            case HOLLOW_EYED -> plugin.effects().grant(victim, PotionEffectType.DARKNESS, settings.ticks("hollow-eyed.darkness-seconds"), false);
            default -> { }
        }
        if (attack.race == Race.PETALFOLK) {
            Player player = attack.attacker;
            EntityRegainHealthEvent heal = new EntityRegainHealthEvent(player, settings.number("petalfolk.healing"), EntityRegainHealthEvent.RegainReason.CUSTOM);
            if (heal.callEvent() && !player.isDead()) player.setHealth(Math.min(Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH)).getValue(), player.getHealth() + Math.max(0, heal.getAmount())));
        }
        plugin.visual(attack.race, victim.getLocation().add(0, 0.15, 0), null);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void knockback(EntityPushedByEntityAttackEvent event) {
        KnockbackReceipt receipt = knockbacks.get(event.getEntity().getUniqueId());
        if (receipt == null || receipt.tick != Bukkit.getCurrentTick() || receipt.hit.isCancelled()) return;
        Entity direct = receipt.hit.getDamager(); Entity source = direct;
        if (direct instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) source = shooter;
        if (!event.getPushedBy().getUniqueId().equals(direct.getUniqueId()) && !event.getPushedBy().getUniqueId().equals(source.getUniqueId())) return;
        switch (event.getCause()) {
            case ENTITY_ATTACK, DAMAGE -> event.setKnockback(event.getKnockback().multiply(1 - plugin.settings().number("rootbound.knockback-reduction")));
            default -> { }
        }
    }
    public static boolean validTarget(LivingEntity entity) {
        if (entity instanceof ArmorStand || entity instanceof Tameable tameable && tameable.isTamed()) return false;
        return entity instanceof Player || entity instanceof Enemy || entity instanceof Slime || entity instanceof Ghast
            || entity instanceof Phantom || entity instanceof Shulker || entity instanceof EnderDragon;
    }
    public static boolean incoming(EntityDamageEvent event) {
        return event instanceof EntityDamageByEntityEvent && (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
            || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK || event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE);
    }
    @SuppressWarnings("deprecation")
    static double absorptionUsed(EntityDamageEvent event) {
        return event.isApplicable(EntityDamageEvent.DamageModifier.ABSORPTION) ? Math.max(0, -event.getDamage(EntityDamageEvent.DamageModifier.ABSORPTION)) : 0;
    }
    public static double actualDamage(EntityDamageEvent event) { return Math.max(0, event.getFinalDamage()) + absorptionUsed(event); }
    /** Solve against the event's normal modifier functions. This respects armor, Resistance, enchantments and absorption. */
    static void reduceFinal(EntityDamageEvent event, double prevention) {
        reduce(event, prevention, false);
    }
    static void reduceActual(EntityDamageEvent event, double prevention) { reduce(event, prevention, true); }
    private static void reduce(EntityDamageEvent event, double prevention, boolean includeAbsorption) {
        double original = event.getDamage(), finalBefore = includeAbsorption ? actualDamage(event) : Math.max(0, event.getFinalDamage());
        double target = Math.max(0, finalBefore - Math.max(0, prevention));
        if (target >= finalBefore) return;
        double low = 0, high = original;
        for (int i = 0; i < 40; i++) {
            double mid = (low + high) / 2;
            event.setDamage(mid);
            double result = includeAbsorption ? actualDamage(event) : event.getFinalDamage();
            if (result <= target) low = mid; else high = mid;
        }
        // At zero, choose the largest base amount that still has zero final damage. This preserves
        // absorption consumption instead of deleting absorption damage in addition to the health cap.
        event.setDamage(target == 0 ? low : high);
    }
    @EventHandler public void death(EntityDeathEvent event) { forget(event.getEntity().getUniqueId()); }
    public void forget(UUID uuid) {
        swings.remove(uuid); knockbacks.remove(uuid); plugin.store().all().forEach(state -> state.chain.forget(uuid));
    }
    public void prune() {
        int now = Bukkit.getCurrentTick();
        swings.values().removeIf(swing -> swing.tick != now);
        knockbacks.values().removeIf(receipt -> receipt.tick != now);
    }
}
