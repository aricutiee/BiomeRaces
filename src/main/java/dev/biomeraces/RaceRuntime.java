package dev.biomeraces;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.potion.PotionEffectType;
import java.util.*;

public final class RaceRuntime implements Listener {
    private final BiomeRaces plugin;
    private final NamespacedKey healthKey;
    private long ticks;
    RaceRuntime(BiomeRaces plugin) { this.plugin = plugin; healthKey = new NamespacedKey(plugin, "dragon_health"); }
    public static boolean holdsEgg(Player player) {
        return player.getInventory().getItemInMainHand().getType() == Material.DRAGON_EGG
            || player.getInventory().getItemInOffHand().getType() == Material.DRAGON_EGG;
    }
    public void tick() {
        ticks++;
        plugin.effects().pulse();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead()) continue;
            syncHands(player);
            if (ticks % plugin.settings().integer("checks.passive-interval-ticks") == 0) updatePassives(player);
            if (ticks % 10 == 0 && plugin.settings().yaml.getBoolean("combat.action-bar")) actionBar(player);
        }
    }
    public void syncHands(Player player) {
        PlayerState state = plugin.state(player);
        if (!player.isOnline() || player.isDead() || !holdsEgg(player)) {
            if (state.dragon) endDragon(player, true);
            state.transformationStep = -1;
            return;
        }
        if (state.dragon) return;
        if (state.transformationStep < 0) { state.transformationStep = 0; state.nextMessageTick = ticks; }
        if (ticks < state.nextMessageTick) return;
        List<String> lines = plugin.settings().yaml.getStringList("dragonborn.transformation-messages");
        player.sendMessage(MiniMessage.miniMessage().deserialize(lines.get(state.transformationStep)));
        state.transformationStep++;
        state.nextMessageTick = ticks + plugin.settings().integer("dragonborn.message-interval-ticks");
        if (state.transformationStep == lines.size() && holdsEgg(player)) {
            state.transformationStep = -1; state.dragon = true; state.chain.reset();
            plugin.effects().clearSelf(player);
            AttributeInstance maximum = Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH));
            maximum.removeModifier(healthKey);
            maximum.addTransientModifier(new AttributeModifier(healthKey, plugin.settings().number("dragonborn.extra-max-health"), AttributeModifier.Operation.ADD_NUMBER));
            // Adding a max-health modifier does not modify current health.
            updatePassives(player);
            plugin.visual(Race.DRAGONBORN, player.getLocation().add(0, 1, 0), player);
        }
    }
    public void endDragon(Player player, boolean message) {
        PlayerState state = plugin.state(player);
        boolean wasDragon = state.dragon;
        state.dragon = false; state.transformationStep = -1; state.chain.reset();
        plugin.effects().clearSelf(player);
        AttributeInstance maximum = player.getAttribute(Attribute.MAX_HEALTH);
        if (maximum != null) {
            maximum.removeModifier(healthKey);
            if (!player.isDead() && player.getHealth() > maximum.getValue()) player.setHealth(maximum.getValue());
        }
        if (wasDragon && message) plugin.message(player, "returned");
        if (player.isOnline() && !player.isDead()) updatePassives(player);
    }
    public void updatePassives(Player player) {
        PlayerState state = plugin.state(player); Settings settings = plugin.settings();
        if (state.active() == Race.HOLLOW_EYED) updateLight(player, state);
        else { state.dark = false; state.darkCandidateSince = -1; }
        Set<PotionEffectType> effects = new HashSet<>();
        Race active = state.active();
        if (active != null) switch (active) {
            case BOGBORN -> { if (EnvironmentRules.headInWater(player)) effects.add(PotionEffectType.WATER_BREATHING); }
            case ROOTBOUND -> { if (settings.onBlock("rootbound.passive-blocks", EnvironmentRules.standingOn(player))) effects.add(PotionEffectType.SPEED); }
            case HOLLOW_EYED -> { if (state.dark) effects.add(PotionEffectType.NIGHT_VISION); }
            case DWARF -> { if (player.getLocation().getY() < settings.number("dwarf.underground-height")) effects.add(PotionEffectType.HASTE); }
            case DRAGONBORN -> effects.addAll(Set.of(PotionEffectType.STRENGTH, PotionEffectType.SPEED, PotionEffectType.FIRE_RESISTANCE));
            default -> { }
        }
        plugin.effects().passives(player, effects, settings.integer("checks.potion-lease-ticks"));
    }
    private void updateLight(Player player, PlayerState state) {
        boolean low = EnvironmentRules.effectiveLight(player) <= plugin.settings().integer("hollow-eyed.light-threshold");
        if (low == state.dark) state.darkCandidateSince = -1;
        else if (state.darkCandidateSince < 0) state.darkCandidateSince = ticks;
        else if (ticks - state.darkCandidateSince >= plugin.settings().integer("hollow-eyed.light-debounce-ticks")) {
            state.dark = low; state.darkCandidateSince = -1;
        }
    }
    public boolean lowLight(Player player) {
        // Defense uses the actual condition, so the visual debounce never extends damage protection into bright light.
        return EnvironmentRules.effectiveLight(player) <= plugin.settings().integer("hollow-eyed.light-threshold");
    }
    private void actionBar(Player player) {
        PlayerState state = plugin.state(player); if (state.active() == null) return;
        long now = System.currentTimeMillis();
        long timeout = plugin.settings().millis(state.dragon ? "dragonborn.chain-timeout-seconds" : "combat.base-chain-timeout-seconds");
        int count = state.chain.count(now, timeout);
        if (count == 0 && state.offenseUntil <= now && state.defenseUntil <= now) return;
        String text = plugin.settings().message("action-bar").replace("<race>", state.active().label())
            .replace("<count>", Integer.toString(count)).replace("<offense>", Long.toString(state.dragon ? 0 : remaining(state.offenseUntil, now)))
            .replace("<defense>", Long.toString(remaining(state.defenseUntil, now)));
        player.sendActionBar(MiniMessage.miniMessage().deserialize(text));
    }
    private static long remaining(long until, long now) { return Math.max(0, (until - now + 999) / 1000); }
    private void nextTick(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> { if (player.isOnline() && !player.isDead()) { syncHands(player); updatePassives(player); } });
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer(); plugin.store().remember(player.getName(), player.getUniqueId());
        plugin.effects().recover(player); endDragon(player, false); nextTick(player); plugin.persist();
    }
    @EventHandler public void quit(PlayerQuitEvent event) { cleanup(event.getPlayer()); }
    @EventHandler public void death(PlayerDeathEvent event) { cleanup(event.getEntity()); }
    @EventHandler public void respawn(PlayerRespawnEvent event) { nextTick(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void held(PlayerItemHeldEvent event) { nextTick(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void swap(PlayerSwapHandItemsEvent event) { nextTick(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void drop(PlayerDropItemEvent event) { nextTick(event.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void click(InventoryClickEvent event) { if (event.getWhoClicked() instanceof Player player) nextTick(player); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void drag(InventoryDragEvent event) { if (event.getWhoClicked() instanceof Player player) nextTick(player); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void teleport(PlayerTeleportEvent event) { nextTick(event.getPlayer()); }
    private void cleanup(Player player) {
        endDragon(player, false); plugin.effects().clear(player); plugin.state(player).chain.reset(); plugin.combat().forget(player.getUniqueId()); plugin.persist();
    }
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) { endDragon(player, false); plugin.effects().clear(player); }
    }
}
