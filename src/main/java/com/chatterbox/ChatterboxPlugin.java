package com.chatterbox;

import com.chatterbox.voice.VoiceHook;
import com.chatterbox.voice.VoiceOutput;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Chatterbox: an AI character you can talk to with your voice.
 * <ul>
 *   <li>AiCharacter - the body (walking, following, emotes)</li>
 *   <li>Brain - talks to Gemini, subtitles, actions</li>
 *   <li>gemini/LiveSession - the WebSocket connection to Gemini Live</li>
 *   <li>voice/VoiceBridge - Simple Voice Chat microphone in / voice out</li>
 *   <li>ToolBox - the actions the AI can take</li>
 * </ul>
 */
public final class ChatterboxPlugin extends JavaPlugin {

    public static final String PLACEHOLDER_KEY = "PASTE-YOUR-GEMINI-API-KEY-HERE";

    private AiCharacter character;
    private ToolBox tools;
    private Brain brain;
    private VoiceOutput voice = VoiceOutput.NONE;
    private boolean voiceHooked;
    private Persona persona;
    private BukkitTask ticker;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        loadPersona();

        character = new AiCharacter(this);
        tools = new ToolBox(this);

        if (getServer().getPluginManager().getPlugin("voicechat") != null) {
            hookVoiceChat();
        } else {
            getLogger().warning("Simple Voice Chat isn't installed - the character will only answer in chat (no voice).");
        }

        PluginCommand command = getCommand("chatterbox");
        if (command != null) {
            ChatterboxCommand executor = new ChatterboxCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getServer().getPluginManager().registerEvents(new ChatterboxListener(this), this);

        ticker = Bukkit.getScheduler().runTaskTimer(this, () -> character.tick(), 1L, 1L);

        // remove leftover bodies (e.g. after a crash) and respawn the saved one
        Bukkit.getScheduler().runTask(this, () -> {
            for (World world : Bukkit.getWorlds()) {
                for (Mannequin m : world.getEntitiesByClass(Mannequin.class)) {
                    if (isOurs(m)) {
                        m.remove();
                    }
                }
            }
            Location saved = loadSavedLocation();
            if (saved != null && getConfig().getBoolean("character.spawn-on-startup", true)) {
                spawnCharacter(saved);
            }
        });

        if (apiKey() == null) {
            getLogger().warning("No Gemini API key yet! Put it in plugins/Chatterbox/config.yml (gemini.api-key), then /cb reload.");
        }
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            ticker.cancel();
        }
        stopBrain();
        voice.shutdown();
        if (character != null) {
            character.remove();
        }
    }

    private void hookVoiceChat() {
        try {
            VoiceOutput bridge = VoiceHook.register(this);
            if (bridge == null) {
                getLogger().warning("Simple Voice Chat is installed but its API isn't available - voice disabled.");
                return;
            }
            voice = bridge;
            voiceHooked = true;
        } catch (Throwable t) {
            getLogger().warning("Couldn't hook into Simple Voice Chat (" + t + ") - voice disabled.");
        }
    }

    // ------------------------------------------------------------------ character lifecycle

    /** Spawn the character here and connect it to Gemini. */
    public void spawnCharacter(Location location) {
        character.spawn(location, persona);
        saveLocation(location);
        if (brain == null) {
            startBrain();
        }
    }

    public void removeCharacter() {
        character.remove();
        stopBrain();
        saveLocation(null);
    }

    /** (Re)connect to Gemini with a fresh conversation. Returns false if there's no API key. */
    public boolean startBrain() {
        stopBrain();
        String key = apiKey();
        if (key == null) {
            Msg.admins("<red>No Gemini API key set. Add it to <white>plugins/Chatterbox/config.yml</white> and run <white>/cb reload</white>.");
            return false;
        }
        brain = new Brain(this, key);
        brain.start();
        return true;
    }

    public void stopBrain() {
        if (brain != null) {
            brain.stop();
            brain = null;
        }
    }

    public String apiKey() {
        String key = getConfig().getString("gemini.api-key", "");
        if (key == null || key.isBlank() || key.equals(PLACEHOLDER_KEY)) {
            String env = System.getenv("GEMINI_API_KEY");
            return env == null || env.isBlank() ? null : env.trim();
        }
        return key.trim();
    }

    public void reloadEverything() {
        reloadConfig();
        loadPersona();
        voice.reload();
        character.applyPersona(persona);
        if (character.isSpawned()) {
            startBrain();
        }
    }

    // ------------------------------------------------------------------ personas

    private void loadPersona() {
        String id = getConfig().getString("persona", "bobby");
        ConfigurationSection section = getConfig().getConfigurationSection("personas." + id);
        if (section == null) {
            getLogger().warning("Persona '" + id + "' doesn't exist in config.yml, using defaults.");
        }
        persona = Persona.fromConfig(id, section);
    }

    public List<String> personaIds() {
        ConfigurationSection section = getConfig().getConfigurationSection("personas");
        return section == null ? List.of() : new ArrayList<>(section.getKeys(false));
    }

    /** Switch to another persona (saved to config) and start a fresh conversation. */
    public boolean switchPersona(String id) {
        if (getConfig().getConfigurationSection("personas." + id) == null) {
            return false;
        }
        getConfig().set("persona", id);
        saveConfig();
        loadPersona();
        character.applyPersona(persona);
        if (character.isSpawned()) {
            startBrain();
        }
        return true;
    }

    public Persona persona() {
        return persona;
    }

    // ------------------------------------------------------------------ saved location

    private void saveLocation(Location location) {
        YamlConfiguration data = new YamlConfiguration();
        data.set("location", location);
        try {
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("Couldn't save data.yml: " + ex.getMessage());
        }
    }

    private Location loadSavedLocation() {
        if (!dataFile.exists()) {
            return null;
        }
        try {
            return YamlConfiguration.loadConfiguration(dataFile).getLocation("location");
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------ getters

    public boolean isOurs(Entity entity) {
        return entity.getPersistentDataContainer().has(character.tag(), PersistentDataType.BYTE);
    }

    public NamespacedKey key(String name) {
        return new NamespacedKey(this, name);
    }

    public AiCharacter character() {
        return character;
    }

    public ToolBox tools() {
        return tools;
    }

    public Brain brain() {
        return brain;
    }

    public VoiceOutput voice() {
        return voice;
    }

    public boolean hasVoiceChat() {
        return voiceHooked;
    }
}
