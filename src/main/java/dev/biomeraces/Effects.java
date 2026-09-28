package dev.biomeraces;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.*;
import java.util.*;

/**
 * Only installs level-I effects into empty slots. External stacks that already exist are never touched.
 * If an external application meets our lease, observe it (including equal-strength shorter effects)
 * and relinquish the slot on the next tick, removing our hidden vanilla fallback in the process.
 * External durations age in server ticks, including during that deferred reconciliation.
 */
public final class Effects implements Listener {
    record Key(UUID entity, PotionEffectType type) {}
    static final class Lease {
        final LivingEntity entity;
        final PotionEffectType type;
        final boolean passive;
        final List<External> incoming = new ArrayList<>();
        long end;
        double absorptionRemaining;
        Lease(LivingEntity entity, PotionEffectType type, boolean passive, long end) {
            this.entity = entity; this.type = type; this.passive = passive; this.end = end;
        }
    }
    public record External(PotionEffect effect, long appliedTick) {
        public PotionEffect remaining(long now) {
            int duration = remainingDuration(effect.getDuration(), now - appliedTick);
            return duration == 0 ? null : new PotionEffect(effect.getType(), duration, effect.getAmplifier(), effect.isAmbient(), effect.hasParticles(), effect.hasIcon());
        }
    }
    private final BiomeRaces plugin;
    private final Map<Key, Lease> leases = new HashMap<>();
    private record TimedIntent(LivingEntity entity, long end) {}
    private final Map<Key, TimedIntent> timed = new HashMap<>();
    private final Set<Key> internal = new HashSet<>();
    private static final List<PotionEffectType> TYPES = List.of(PotionEffectType.WATER_BREATHING, PotionEffectType.SPEED,
        PotionEffectType.NIGHT_VISION, PotionEffectType.HASTE, PotionEffectType.STRENGTH, PotionEffectType.FIRE_RESISTANCE,
        PotionEffectType.ABSORPTION, PotionEffectType.POISON, PotionEffectType.SLOWNESS, PotionEffectType.DARKNESS);
    Effects(BiomeRaces plugin) { this.plugin = plugin; }
    public static int remainingDuration(int duration, long elapsed) {
        return duration == PotionEffect.INFINITE_DURATION ? duration : (int) Math.max(0, duration - Math.max(0, elapsed));
    }
    private long tick() { return Integer.toUnsignedLong(Bukkit.getCurrentTick()); }
    private NamespacedKey marker(PotionEffectType type) { return new NamespacedKey(plugin, "effect_" + type.getKey().getKey()); }

    public boolean grant(LivingEntity entity, PotionEffectType type, int duration, boolean passive) {
        Key key = new Key(entity.getUniqueId(), type);
        // A pre-existing short external debuff can cover the start of our duration. Retain the
        // remaining intent so its natural expiry cannot shorten a new Venom/Snare/Dread application.
        // Absorption is intentionally excluded: resuming it would refill spent temporary hearts.
        if (!passive && !type.equals(PotionEffectType.ABSORPTION)) {
            TimedIntent previous = timed.get(key);
            timed.put(key, new TimedIntent(entity, Math.max(tick() + duration, previous == null ? 0 : previous.end)));
        }
        Lease old = leases.get(key);
        if (old != null) {
            if (!old.incoming.isEmpty()) return false;
            if (passive && old.end - tick() < duration / 2) {
                internal.add(key);
                try {
                    if (entity.addPotionEffect(new PotionEffect(type, duration, 0, true, false, true))) old.end = tick() + duration;
                } finally { internal.remove(key); }
            }
            return false;
        }
        // Every race effect is level I. Any existing external level is sufficient and takes priority.
        if (entity.hasPotionEffect(type) || (type.equals(PotionEffectType.ABSORPTION) && entity.getAbsorptionAmount() > 0)) return false;
        internal.add(key);
        boolean applied;
        try {
            applied = entity.addPotionEffect(new PotionEffect(type, duration, 0, true, false, true));
            PotionEffect installed = entity.getPotionEffect(type);
            applied = applied && installed != null && installed.getAmplifier() == 0 && installed.isAmbient() && !installed.hasParticles();
        }
        finally { internal.remove(key); }
        if (applied) {
            Lease lease = new Lease(entity, type, passive, tick() + duration);
            if (type.equals(PotionEffectType.ABSORPTION)) lease.absorptionRemaining = Math.min(4, entity.getAbsorptionAmount());
            leases.put(key, lease);
            entity.getPersistentDataContainer().set(marker(type), PersistentDataType.BYTE, (byte) 1);
        } else timed.remove(key);
        return applied;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void changed(EntityPotionEffectEvent event) {
        Key key = new Key(event.getEntity().getUniqueId(), event.getModifiedType());
        if (internal.contains(key)) return;
        if (event.getNewEffect() == null && event.getCause() != EntityPotionEffectEvent.Cause.EXPIRATION) timed.remove(key);
        Lease lease = leases.get(key);
        if (lease == null) return;
        if (event.getNewEffect() == null) {
            leases.remove(key);
            lease.entity.getPersistentDataContainer().remove(marker(lease.type));
        } else {
            // Do not change the external event or its cancellation/override decision.
            lease.incoming.add(new External(event.getNewEffect(), tick()));
        }
    }

    public void pulse() {
        for (Key key : List.copyOf(leases.keySet())) {
            Lease lease = leases.get(key);
            if (lease == null) continue;
            if (!lease.incoming.isEmpty()) release(key);
            else if (tick() >= lease.end || !lease.entity.isValid() || lease.entity.isDead()) {
                release(key);
            }
        }
        for (Key key : List.copyOf(timed.keySet())) {
            TimedIntent intent = timed.get(key);
            if (intent.end <= tick() || !intent.entity.isValid() || intent.entity.isDead()) { timed.remove(key); continue; }
            if (!leases.containsKey(key) && !intent.entity.hasPotionEffect(key.type))
                grant(intent.entity, key.type, (int) (intent.end - tick()), false);
        }
    }
    public void consumedAbsorption(LivingEntity entity, double amount) {
        Lease lease = leases.get(new Key(entity.getUniqueId(), PotionEffectType.ABSORPTION));
        if (lease != null) lease.absorptionRemaining = Math.max(0, lease.absorptionRemaining - amount);
    }
    public void passives(LivingEntity entity, Set<PotionEffectType> desired, int duration) {
        for (PotionEffectType type : TYPES) {
            Key key = new Key(entity.getUniqueId(), type);
            Lease lease = leases.get(key);
            if (lease != null && lease.passive && !desired.contains(key.type)) release(key);
        }
        for (PotionEffectType type : desired) grant(entity, type, duration, true);
    }
    public void clear(LivingEntity entity) {
        for (PotionEffectType type : TYPES) {
            Key key = new Key(entity.getUniqueId(), type); timed.remove(key); release(key);
        }
    }
    public void clearSelf(LivingEntity entity) {
        for (PotionEffectType type : TYPES) {
            Key key = new Key(entity.getUniqueId(), type);
            Lease lease = leases.get(key);
            if (lease != null && (lease.passive || key.type.equals(PotionEffectType.ABSORPTION))) release(key);
        }
    }
    public void close() { timed.clear(); for (Key key : List.copyOf(leases.keySet())) release(key); }

    private void release(Key key) {
        Lease lease = leases.remove(key);
        if (lease == null) return;
        LivingEntity entity = lease.entity;
        entity.getPersistentDataContainer().remove(marker(lease.type));
        double absorption = entity.getAbsorptionAmount();
        internal.add(key);
        try {
            entity.removePotionEffect(lease.type);
            if (!entity.isDead()) {
                // Weak long effects first allows vanilla to retain its normal hidden-effect stack.
                List<PotionEffect> external = lease.incoming.stream().map(e -> e.remaining(tick())).filter(Objects::nonNull)
                    .sorted(Comparator.comparingInt(PotionEffect::getAmplifier).thenComparingInt(e -> e.getDuration() == -1 ? Integer.MAX_VALUE : e.getDuration()))
                    .toList();
                for (PotionEffect effect : external) entity.addPotionEffect(effect);
                if (lease.type.equals(PotionEffectType.ABSORPTION)) {
                    double retained = external.isEmpty() ? Math.max(0, absorption - lease.absorptionRemaining) : absorption;
                    // Reconciliation must not refill spent external or race absorption.
                    entity.setAbsorptionAmount(Math.min(retained, entity.getAbsorptionAmount()));
                }
            }
        } finally { internal.remove(key); }
    }

    /** Crash/restart residue is identified by our PDC markers, never by type alone. */
    public void recover(LivingEntity entity) {
        for (PotionEffectType type : TYPES) {
            if (!entity.getPersistentDataContainer().has(marker(type))) continue;
            PotionEffect effect = entity.getPotionEffect(type);
            if (effect != null && effect.getAmplifier() == 0 && effect.isAmbient() && !effect.hasParticles()) entity.removePotionEffect(type);
            entity.getPersistentDataContainer().remove(marker(type));
        }
    }
}
