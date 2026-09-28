package dev.biomeraces;

import java.util.Locale;

public enum Race {
    BOGBORN("Bogborn"), ROOTBOUND("Rootbound"), PETALFOLK("Petalfolk"),
    HOLLOW_EYED("Hollow-Eyed"), DWARF("Dwarf"), DRAGONBORN("Dragonborn");
    private final String label;
    Race(String label) { this.label = label; }
    public String label() { return label; }
    public String key() { return name().toLowerCase(Locale.ROOT).replace('_', '-'); }
    public static Race parse(String value) {
        if (value == null) return null;
        try { return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException ex) { return null; }
    }
}
