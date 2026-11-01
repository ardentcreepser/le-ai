package com.chatterbox;

import org.bukkit.configuration.ConfigurationSection;

/** One character from the "personas" section of config.yml. */
public record Persona(String id, String name, String skin, String voice, String description, String personality) {

    public static Persona fromConfig(String id, ConfigurationSection section) {
        if (section == null) {
            return new Persona(id, "Bobby", "", "", "<gray>AI", "You are a friendly Minecraft player.");
        }
        return new Persona(
                id,
                section.getString("name", "Bobby"),
                section.getString("skin", ""),
                section.getString("voice", ""),
                section.getString("name-tag-description", "<gray>AI"),
                section.getString("personality", "You are a friendly Minecraft player."));
    }

    /** True if the skin looks like a valid Minecraft username. */
    public boolean hasValidSkin() {
        return skin != null && skin.matches("[A-Za-z0-9_]{3,16}");
    }
}
