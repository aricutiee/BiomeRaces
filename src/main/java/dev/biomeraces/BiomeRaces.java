package dev.biomeraces;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Objects;

public class BiomeRaces extends JavaPlugin {
    private Settings settings;
    private PlayerStore store;
    private Effects effects;
    private RaceRuntime runtime;
    private CombatListener combat;
    private RaceCommand command;
    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            settings = Settings.load(this);
            store = new PlayerStore(getDataFolder().toPath().resolve("players.yml"));
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "Cannot load configuration/player data. Disabling to avoid losing saved choices.", ex);
            getServer().getPluginManager().disablePlugin(this); return;
        }
        effects = new Effects(this); runtime = new RaceRuntime(this); combat = new CombatListener(this); command = new RaceCommand(this);
        var manager = getServer().getPluginManager();
        manager.registerEvents(effects, this); manager.registerEvents(runtime, this); manager.registerEvents(combat, this); manager.registerEvents(command, this);
        var race = Objects.requireNonNull(getCommand("race")); race.setExecutor(command); race.setTabCompleter(command);
        for (Player player : Bukkit.getOnlinePlayers()) { effects.recover(player); runtime.endDragon(player, false); store.remember(player.getName(), player.getUniqueId()); }
        getServer().getScheduler().runTaskTimer(this, () -> { combat.prune(); runtime.tick(); }, 1, 1);
        getLogger().info("BiomeRaces enabled for Paper 1.21.11. Base choices and cooldowns are UUID-persistent.");
    }
    @Override public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        if (command != null) command.closeMenus();
        if (runtime != null) runtime.shutdown();
        if (effects != null) effects.close();
        if (store != null) persist();
    }
    public void reloadSettings() throws Exception {
        Settings next = Settings.load(this); // Validate before touching running state.
        command.closeMenus(); runtime.shutdown(); effects.close(); settings = next;
        for (Player player : Bukkit.getOnlinePlayers()) runtime.updatePassives(player);
    }
    public void persist() {
        try { store.save(); }
        catch (Exception ex) { getLogger().log(java.util.logging.Level.SEVERE, "Could not persist player choices/cooldowns. Check disk permissions and free space.", ex); }
    }
    public void message(CommandSender sender, String key, String... replacements) {
        String text = settings.message(key);
        for (int i = 0; i + 1 < replacements.length; i += 2) text = text.replace(replacements[i], replacements[i + 1]);
        sender.sendMessage(MiniMessage.miniMessage().deserialize(text));
    }
    public void visual(Race race, Location location, Player privateViewer) {
        Settings.Visual visual = settings.visual(race);
        for (Particle particle : visual.particles()) {
            Object data = particle.getDataType() == org.bukkit.block.data.BlockData.class ? visual.block().createBlockData() : null;
            if (privateViewer != null) privateViewer.spawnParticle(particle, location, visual.count(), .25, .2, .25, .01, data);
            else location.getWorld().spawnParticle(particle, location, visual.count(), .25, .2, .25, .01, data);
        }
        if (visual.volume() > 0 && !visual.sound().isBlank()) {
            if (privateViewer != null) privateViewer.playSound(location, visual.sound(), SoundCategory.PLAYERS, visual.volume(), visual.pitch());
            else location.getWorld().playSound(location, visual.sound(), SoundCategory.PLAYERS, visual.volume(), visual.pitch());
        }
    }
    public Settings settings() { return settings; }
    public PlayerStore store() { return store; }
    public PlayerState state(Player player) { return store.get(player.getUniqueId()); }
    public Effects effects() { return effects; }
    public RaceRuntime runtime() { return runtime; }
    public CombatListener combat() { return combat; }
}
