package dev.biomeraces;

import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Settings {
    public final YamlConfiguration yaml;
    private final Map<String, Set<Material>> blocks = new HashMap<>();
    private final Map<Race, Visual> visuals = new EnumMap<>(Race.class);
    public record Visual(List<Particle> particles, int count, Material block, String sound, float volume, float pitch) {}
    public Settings(YamlConfiguration yaml) {
        this.yaml = yaml;
        for (String path : List.of("rootbound.passive-blocks", "rootbound.defense-blocks", "dwarf.landing-blocks")) {
            Set<Material> materials = EnumSet.noneOf(Material.class);
            for (String name : yaml.getStringList(path)) {
                Material m = Material.matchMaterial(name);
                if (m == null || !m.isBlock()) throw new IllegalArgumentException("Invalid block in " + path + ": " + name);
                materials.add(m);
            }
            blocks.put(path, materials);
        }
        for (Race race : Race.values()) {
            String path = race.key() + ".visual.";
            List<Particle> particles = new ArrayList<>();
            for (String value : yaml.getStringList(path + "particles")) {
                Particle particle = Particle.valueOf(value);
                if (particle.getDataType() != Void.class && particle.getDataType() != org.bukkit.block.data.BlockData.class)
                    throw new IllegalArgumentException("Use a no-data or block-data particle: " + value);
                particles.add(particle);
            }
            Material block = Material.matchMaterial(yaml.getString(path + "block", "STONE"));
            if (block == null || !block.isBlock()) throw new IllegalArgumentException("Invalid visual block: " + race);
            visuals.put(race, new Visual(List.copyOf(particles), integer(path + "count"), block,
                yaml.getString(path + "sound", "minecraft:block.stone.hit"), (float) number(path + "volume"), (float) number(path + "pitch")));
            if (race != Race.DRAGONBORN) {
                Material icon = Material.matchMaterial(yaml.getString(race.key() + ".icon", "STONE"));
                if (icon == null || !icon.isItem()) throw new IllegalArgumentException("Invalid menu icon: " + race);
            }
        }
        for (String path : yaml.getKeys(true)) {
            Object value = yaml.get(path);
            if (value instanceof Number n && (!Double.isFinite(n.doubleValue()) || (n.doubleValue() < 0 && !path.equals("dwarf.underground-height"))))
                throw new IllegalArgumentException("Must be finite and nonnegative: " + path);
            if (path.endsWith("reduction") && number(path) > 1) throw new IllegalArgumentException("Reduction must be 0..1: " + path);
        }
        if (number("combat.minimum-attack-charge") > 1 || integer("combat.base-hits") < 1
            || integer("dragonborn.replacement-start-hit") < 4 || integer("checks.passive-interval-ticks") < 1
            || integer("checks.potion-lease-ticks") < 240 || integer("dragonborn.message-interval-ticks") < 1
            || integer("hollow-eyed.light-threshold") > 15 || yaml.getStringList("dragonborn.transformation-messages").isEmpty())
            throw new IllegalArgumentException("Invalid charge, hit count, interval, lease, light threshold or transformation messages");
    }
    public static Settings load(BiomeRaces plugin) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.load(new File(plugin.getDataFolder(), "config.yml"));
        try (InputStream stream = Objects.requireNonNull(plugin.getResource("config.yml"))) {
            config.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        return new Settings(config);
    }
    public double number(String path) { return yaml.getDouble(path); }
    public int integer(String path) { return yaml.getInt(path); }
    public long millis(String path) { return Math.round(number(path) * 1000); }
    public int ticks(String path) { return Math.max(1, (int) Math.round(number(path) * 20)); }
    public boolean onBlock(String path, Material block) { return blocks.get(path).contains(block); }
    public Visual visual(Race race) { return visuals.get(race); }
    public String message(String key) { return yaml.getString("messages." + key, key); }
}
