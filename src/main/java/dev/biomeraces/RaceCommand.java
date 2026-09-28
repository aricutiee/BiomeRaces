package dev.biomeraces;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import java.util.*;

public final class RaceCommand implements CommandExecutor, TabCompleter, Listener {
    private final BiomeRaces plugin;
    private static final Race[] BASE = {Race.BOGBORN, Race.ROOTBOUND, Race.PETALFOLK, Race.HOLLOW_EYED, Race.DWARF};
    private static final int[] SLOTS = {9, 11, 13, 15, 17};
    private static final class Menu implements InventoryHolder {
        Inventory inventory;
        final UUID player;
        Menu(UUID player) { this.player = player; }
        @Override public Inventory getInventory() { return inventory; }
    }
    RaceCommand(BiomeRaces plugin) { this.plugin = plugin; }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) { plugin.message(sender, "player-only"); return true; }
            if (plugin.state(player).base != null) plugin.message(player, "locked"); else open(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("info") && args.length == 1) {
            if (sender instanceof Player player) info(player); else plugin.message(sender, "player-only");
            return true;
        }
        if (!List.of("set", "reset", "reload").contains(sub)) return false;
        if (!sender.hasPermission("biomeraces.admin." + sub)) { plugin.message(sender, "no-permission"); return true; }
        if (sub.equals("reload")) {
            if (args.length != 1) return false;
            try { plugin.reloadSettings(); plugin.message(sender, "reloaded"); }
            catch (Exception ex) { plugin.getLogger().severe("Reload rejected: " + ex.getMessage()); plugin.message(sender, "reload-failed"); }
            return true;
        }
        if (args.length != (sub.equals("set") ? 3 : 2)) return false;
        Player target = Bukkit.getPlayerExact(args[1]);
        UUID uuid = target == null ? plugin.store().lookup(args[1]) : target.getUniqueId();
        if (uuid == null) { plugin.message(sender, "player-not-found"); return true; }
        Race race = sub.equals("set") ? Race.parse(args[2]) : null;
        if (sub.equals("set") && (race == null || race == Race.DRAGONBORN)) { plugin.message(sender, "invalid-race"); return true; }
        setBase(uuid, race);
        plugin.message(sender, race == null ? "reset" : "selected", "<race>", race == null ? "" : race.label());
        if (target != null && !target.equals(sender)) plugin.message(target, race == null ? "reset" : "selected", "<race>", race == null ? "" : race.label());
        return true;
    }
    private void setBase(UUID uuid, Race race) {
        PlayerState state = plugin.store().get(uuid); state.base = race; state.chain.reset();
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) { plugin.effects().clearSelf(player); plugin.runtime().updatePassives(player); }
        plugin.persist();
    }
    private void info(Player player) {
        plugin.runtime().syncHands(player);
        PlayerState state = plugin.state(player); Race active = state.active();
        plugin.message(player, "info-heading", "<race>", active == null ? plugin.settings().message("none") : active.label());
        if (state.dragon) plugin.message(player, "info-base", "<race>", state.base == null ? plugin.settings().message("none") : state.base.label());
        if (active != null) for (String line : plugin.settings().yaml.getStringList(active.key() + ".description")) player.sendMessage(MiniMessage.miniMessage().deserialize(line));
    }
    private void open(Player player) {
        Menu holder = new Menu(player.getUniqueId());
        Inventory menu = Bukkit.createInventory(holder, 27, MiniMessage.miniMessage().deserialize(plugin.settings().yaml.getString("menu.title", "Choose your race")));
        holder.inventory = menu;
        for (int i = 0; i < BASE.length; i++) {
            Race race = BASE[i]; ItemStack item = new ItemStack(Objects.requireNonNull(Material.matchMaterial(plugin.settings().yaml.getString(race.key() + ".icon", "STONE"))));
            var meta = item.getItemMeta();
            meta.displayName(MiniMessage.miniMessage().deserialize("<gold>" + race.label()).decoration(TextDecoration.ITALIC, false));
            List<String> lore = new ArrayList<>(plugin.settings().yaml.getStringList(race.key() + ".description"));
            lore.add(plugin.settings().yaml.getString("menu.choice-warning", "Permanent choice. An administrator can change it."));
            meta.lore(lore.stream().map(s -> MiniMessage.miniMessage().deserialize(s).decoration(TextDecoration.ITALIC, false)).toList());
            item.setItemMeta(meta); menu.setItem(SLOTS[i], item);
        }
        player.openInventory(menu);
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(menu.player)
            || plugin.state(player).base != null || !player.hasPermission("biomeraces.use")) return;
        int index = -1;
        for (int i = 0; i < SLOTS.length; i++) if (event.getRawSlot() == SLOTS[i]) index = i;
        if (index < 0 || !event.isLeftClick()) return;
        Race race = BASE[index]; setBase(player.getUniqueId(), race);
        plugin.message(player, "selected", "<race>", race.label());
        Bukkit.getScheduler().runTask(plugin, () -> player.closeInventory());
    }
    @EventHandler public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }
    public void closeMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top != null && top.getHolder() instanceof Menu) player.closeInventory();
        }
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> values = new ArrayList<>();
        if (args.length == 1) {
            values.add("info");
            for (String sub : List.of("set", "reset", "reload")) if (sender.hasPermission("biomeraces.admin." + sub)) values.add(sub);
        } else if (args.length == 2 && List.of("set", "reset").contains(args[0]) && sender.hasPermission("biomeraces.admin." + args[0])) {
            Bukkit.getOnlinePlayers().forEach(player -> values.add(player.getName()));
        } else if (args.length == 3 && args[0].equals("set") && sender.hasPermission("biomeraces.admin.set")) {
            for (Race race : BASE) values.add(race.key());
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
