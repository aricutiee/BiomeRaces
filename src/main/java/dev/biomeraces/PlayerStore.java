package dev.biomeraces;

import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Atomic saves on choices and cooldown activation, not just logout. Expiries use UTC epoch millis. */
public final class PlayerStore {
    private final Path file;
    private final Map<UUID, PlayerState> states = new HashMap<>();
    private final Map<String, UUID> names = new HashMap<>();
    public PlayerStore(Path file) throws Exception {
        this.file = file;
        if (!Files.exists(file)) return;
        YamlConfiguration data = new YamlConfiguration(); data.load(file.toFile());
        var section = data.getConfigurationSection("players");
        if (section != null) for (String id : section.getKeys(false)) {
            UUID uuid = UUID.fromString(id); String path = "players." + id + ".";
            PlayerState state = get(uuid); state.base = Race.parse(data.getString(path + "race"));
            if (state.base == Race.DRAGONBORN) state.base = null;
            state.offenseUntil = data.getLong(path + "offense-until");
            state.defenseUntil = data.getLong(path + "defense-until");
        }
        var nameSection = data.getConfigurationSection("names");
        if (nameSection != null) for (String name : nameSection.getKeys(false)) names.put(name, UUID.fromString(nameSection.getString(name)));
    }
    public PlayerState get(UUID id) { return states.computeIfAbsent(id, unused -> new PlayerState()); }
    public Collection<PlayerState> all() { return states.values(); }
    public void remember(String name, UUID uuid) { names.put(name.toLowerCase(Locale.ROOT), uuid); }
    public UUID lookup(String name) {
        try { return UUID.fromString(name); } catch (IllegalArgumentException ignored) { return names.get(name.toLowerCase(Locale.ROOT)); }
    }
    public void save() throws IOException {
        YamlConfiguration data = new YamlConfiguration();
        states.forEach((id, state) -> {
            String path = "players." + id + ".";
            data.set(path + "race", state.base == null ? null : state.base.key());
            data.set(path + "offense-until", state.offenseUntil);
            data.set(path + "defense-until", state.defenseUntil);
        });
        names.forEach((name, id) -> data.set("names." + name, id.toString()));
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, data.saveToString(), StandardCharsets.UTF_8);
        try { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ex) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}
