package dev.biomeraces;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CombatChainTest {
    @Test void exactDragonProgressionReplacesRatherThanAccumulates() {
        double[] expected = {13, 13, 15, 8, 10, 12, 14, 16, 18};
        for (int i = 0; i < expected.length; i++) assertEquals(expected[i], CombatChain.dragonDamage(13, i + 1, 2, 4, 2));
    }
    @Test void configurableDragonFormula() { assertEquals(20, CombatChain.dragonDamage(30, 5, 3, 4, 4)); }
    @Test void targetTimeoutAndDeathReset() {
        CombatChain chain = new CombatChain(); UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals(1, chain.preview(a, 1000, 8000)); chain.commit(a, 1, 1000);
        assertEquals(2, chain.preview(a, 8999, 8000));
        assertEquals(1, chain.preview(a, 9000, 8000));
        assertEquals(1, chain.preview(b, 2000, 8000));
        assertEquals(1, chain.preview(a, 5000, 4000));
        chain.forget(b); assertEquals(2, chain.preview(a, 2000, 8000));
        chain.forget(a); assertEquals(1, chain.preview(a, 2000, 8000));
    }
    @Test void previewDoesNotAdvanceCounter() {
        CombatChain chain = new CombatChain(); UUID id = UUID.randomUUID();
        for (int i = 0; i < 10; i++) assertEquals(1, chain.preview(id, 1000 + i, 8000));
        assertEquals(0, chain.count(1010, 8000));
    }
    @Test void eligibilityMatrix() {
        assertTrue(CombatChain.eligible(true, true, .9, .9, true, true, false, 1));
        assertFalse(CombatChain.eligible(true, true, .899, .9, true, true, false, 1));
        assertFalse(CombatChain.eligible(false, true, 1, .9, true, true, false, 1));
        assertFalse(CombatChain.eligible(true, false, 1, .9, true, true, false, 1));
        assertFalse(CombatChain.eligible(true, true, 1, .9, false, true, false, 1));
        assertFalse(CombatChain.eligible(true, true, 1, .9, true, false, false, 1));
        assertFalse(CombatChain.eligible(true, true, 1, .9, true, true, true, 1));
        assertFalse(CombatChain.eligible(true, true, 1, .9, true, true, false, 0));
    }
    @Test void externalDurationsExpireAndNeverRestart() {
        assertEquals(30, Effects.remainingDuration(40, 10));
        assertEquals(0, Effects.remainingDuration(40, 40));
        assertEquals(0, Effects.remainingDuration(40, 80));
        assertEquals(-1, Effects.remainingDuration(-1, 8000));
    }
}
