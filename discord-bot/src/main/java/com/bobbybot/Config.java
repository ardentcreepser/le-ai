package com.bobbybot;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads config.yml. Anything missing from the user's file falls back to the default config.yml inside the jar,
 * so new settings in future versions work without editing the file.
 */
public final class Config {

    public static final String TOKEN_PLACEHOLDER = "PASTE-YOUR-DISCORD-BOT-TOKEN-HERE";
    public static final String KEY_PLACEHOLDER = "PASTE-YOUR-GEMINI-API-KEY-HERE";

    private final Map<String, Object> values;
    private final Map<String, Object> defaults;

    private Config(Map<String, Object> values, Map<String, Object> defaults) {
        this.values = values;
        this.defaults = defaults;
    }

    /** The default config bundled in the jar. */
    public static Config defaults() {
        Map<String, Object> d = loadDefaults();
        return new Config(d, d);
    }

    /** Load config.yml from disk (with the bundled defaults behind it). */
    public static Config load(Path file) throws IOException {
        Map<String, Object> d = loadDefaults();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object parsed = yaml().load(reader);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            return new Config(map, d);
        }
    }

    /** Write the default config.yml next to the jar if there isn't one yet. Returns true if it was created. */
    public static boolean writeDefaultIfMissing(Path file) throws IOException {
        if (Files.exists(file)) {
            return false;
        }
        try (InputStream in = Config.class.getResourceAsStream("/config.yml")) {
            if (in == null) {
                throw new IOException("Default config.yml is missing from the jar");
            }
            Files.copy(in, file);
        }
        return true;
    }

    private static Yaml yaml() {
        return new Yaml(new SafeConstructor(new LoaderOptions()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadDefaults() {
        try (InputStream in = Config.class.getResourceAsStream("/config.yml")) {
            if (in == null) {
                return Map.of();
            }
            Object parsed = yaml().load(in);
            return parsed instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        } catch (IOException ex) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------------ lookups

    private static Object find(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private Object raw(String path) {
        Object value = find(values, path);
        return value != null ? value : find(defaults, path);
    }

    public String string(String path, String fallback) {
        Object value = raw(path);
        return value == null ? fallback : String.valueOf(value);
    }

    public boolean bool(String path, boolean fallback) {
        Object value = raw(path);
        if (value instanceof Boolean b) {
            return b;
        }
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    public int integer(String path, int fallback) {
        Object value = raw(path);
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public double decimal(String path, double fallback) {
        Object value = raw(path);
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public List<String> strings(String path) {
        Object value = raw(path);
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item).trim());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ secrets

    /** The Discord token from config or the DISCORD_TOKEN environment variable, or null. */
    public String discordToken() {
        return secret("discord.token", TOKEN_PLACEHOLDER, "DISCORD_TOKEN");
    }

    /** The Gemini key from config or the GEMINI_API_KEY environment variable, or null. */
    public String geminiKey() {
        return secret("gemini.api-key", KEY_PLACEHOLDER, "GEMINI_API_KEY");
    }

    private String secret(String path, String placeholder, String env) {
        String value = string(path, "").trim();
        if (value.isEmpty() || value.equals(placeholder)) {
            String fromEnv = System.getenv(env);
            return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.trim();
        }
        return value;
    }
}
