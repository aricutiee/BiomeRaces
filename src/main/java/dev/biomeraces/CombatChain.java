package dev.biomeraces;

import java.util.UUID;

/** Preview never mutates the chain: only an accepted damage event can commit a hit. */
public final class CombatChain {
    private UUID target;
    private int count;
    private long lastHit;
    public int preview(UUID nextTarget, long now, long timeout) {
        return nextTarget.equals(target) && now - lastHit < timeout ? Math.min(count + 1, 1_000_000) : 1;
    }
    public void commit(UUID nextTarget, int nextCount, long now) {
        target = nextTarget; count = nextCount; lastHit = now;
    }
    public void reset() { target = null; count = 0; lastHit = 0; }
    public void forget(UUID deadTarget) { if (deadTarget.equals(target)) reset(); }
    public int count(long now, long timeout) { return now - lastHit < timeout ? count : 0; }
    public static double dragonDamage(double ordinary, int hit, double thirdBonus, int replaceFrom, double perHit) {
        if (hit >= replaceFrom) return perHit * hit;
        return hit == 3 ? ordinary + thirdBonus : ordinary;
    }
    public static boolean eligible(boolean direct, boolean mainHand, double charge, double minimum,
                                   boolean validTarget, boolean allowed, boolean cancelled, double actualDamage) {
        return direct && mainHand && charge >= minimum && validTarget && allowed && !cancelled && actualDamage > 0;
    }
}
