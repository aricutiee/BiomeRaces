package dev.biomeraces;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

public final class EnvironmentRules {
    private EnvironmentRules() {}
    public static Material standingOn(Player player) { return player.getLocation().subtract(0, 0.05, 0).getBlock().getType(); }
    public static boolean headInWater(Player player) { return player.isUnderWater(); }
    public static boolean bodyInWater(Player player) { return player.isInWater(); }
    /** Paper CraftBlock delegates to Level.getMaxLocalRawBrightness, including sky darkening. */
    public static int effectiveLight(Player player) {
        Block eyes = player.getEyeLocation().getBlock();
        return eyes.getLightLevel();
    }
}
