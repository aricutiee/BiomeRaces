package dev.biomeraces;

public final class PlayerState {
    public Race base;
    public long offenseUntil, defenseUntil;
    public final CombatChain chain = new CombatChain();
    public boolean dragon;
    public int transformationStep = -1;
    public long nextMessageTick;
    public boolean dark;
    public long darkCandidateSince = -1;
    public Race active() { return dragon ? Race.DRAGONBORN : base; }
}
