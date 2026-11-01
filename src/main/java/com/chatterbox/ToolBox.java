package com.chatterbox;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The "tools" Gemini can call to act in the game (follow, emote, give items, troll...).
 * {@link #declarations()} describes them to Gemini; {@link #execute} runs them on the main thread.
 */
public final class ToolBox {

    public static final List<String> EMOTES = List.of("wave", "jump", "crouch", "dance", "spin", "nod",
            "shake_head", "celebrate", "scared");

    /** What a tool sends back to Gemini. ok=false makes the AI notice it didn't work. */
    public record Result(JsonObject response, boolean ok) {
        static Result ok(String message) {
            JsonObject o = new JsonObject();
            o.addProperty("result", message);
            return new Result(o, true);
        }

        static Result fail(String message) {
            JsonObject o = new JsonObject();
            o.addProperty("error", message);
            return new Result(o, false);
        }
    }

    private final ChatterboxPlugin plugin;

    public ToolBox(ChatterboxPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private boolean trollsAvailable() {
        return cfg().getBoolean("actions.allow-trolls", true)
                && Bukkit.getPluginManager().isPluginEnabled("Shenanigans")
                && !cfg().getStringList("actions.trolls").isEmpty();
    }

    // ------------------------------------------------------------------ declarations

    public JsonArray declarations() {
        JsonArray list = new JsonArray();
        list.add(fn("look_around", "See what is around you right now: nearby players (and what they hold), mobs, "
                + "time of day, weather, biome and your own state. Use it whenever you need to know what's going on.",
                true));
        list.add(fn("follow_player", "Start following a player around until told otherwise.", false,
                param("player", "STRING", "Player name. Leave empty for the person talking to you.")));
        list.add(fn("come_here", "Walk over to a player and then stay there.", false,
                param("player", "STRING", "Player name. Leave empty for the person talking to you.")));
        list.add(fn("stay", "Stop following or fighting and stay where you are.", false));
        list.add(fn("emote", "Do a body animation to express yourself while talking.", false,
                enumParam("emote", "Which animation", EMOTES)));
        list.add(fn("hold_item", "Hold an item in your hand (just for show). Use 'nothing' for an empty hand.", false,
                param("item", "STRING", "Minecraft item id, e.g. diamond_sword, torch, cake, nothing")));
        if (cfg().getBoolean("actions.allow-give-items", true)) {
            list.add(fn("give_item", "Throw real items to a player. Only when it fits the story or someone convinces you.", false,
                    param("player", "STRING", "Player name. Leave empty for the person talking to you."),
                    param("item", "STRING", "Minecraft item id, e.g. diamond, bread, oak_log"),
                    param("amount", "INTEGER", "How many (max " + cfg().getInt("actions.max-give-amount", 16) + ")")));
        }
        if (cfg().getBoolean("actions.allow-breaking-blocks", true)) {
            list.add(fn("break_blocks", "Break or mine blocks like a player: chop a tree, mine stone, dig dirt, clear leaves, "
                            + "or break the exact block a player is looking at. You walk over and break them one by one; "
                            + "the items drop on the ground for the players.", false,
                    required(param("block", "STRING", "What to break: 'looked_at' (the block the player is looking at), "
                            + "a group: 'tree', 'leaves', 'stone', 'dirt', 'sand', 'ores', or a Minecraft block id like 'oak_log' or 'diamond_ore'.")),
                    param("count", "INTEGER", "How many blocks (max " + cfg().getInt("actions.max-blocks-per-request", 16)
                            + "). A whole tree is about 6."),
                    param("player", "STRING", "Only for 'looked_at': whose view to use. Leave empty for the person talking to you.")));
        }
        if (cfg().getBoolean("actions.allow-fighting", true)) {
            list.add(fn("fight_mobs", "Attack the hostile mobs (zombies, skeletons, spiders...) near you for a while.", false));
        }
        if (cfg().getBoolean("actions.allow-world-control", true)) {
            list.add(fn("set_time", "Change the time of day for dramatic effect.", false,
                    enumParam("time", "Time of day", List.of("day", "noon", "sunset", "night", "midnight"))));
            list.add(fn("set_weather", "Change the weather.", false,
                    enumParam("weather", "Weather", List.of("clear", "rain", "thunder"))));
            list.add(fn("lightning", "Strike harmless lightning right next to a player, for drama.", false,
                    param("player", "STRING", "Player name. Leave empty for the person talking to you.")));
        }
        if (trollsAvailable()) {
            list.add(fn("troll_player", "Prank a player with a harmless troll effect.", false,
                    param("player", "STRING", "Player name. Leave empty for the person talking to you."),
                    enumParam("troll", "Which troll", cfg().getStringList("actions.trolls"))));
        }
        return list;
    }

    private static JsonObject fn(String name, String description, boolean blocking, JsonObject... params) {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("description", description);
        // BLOCKING = the AI waits for the answer (needed to "see"); the rest run while it keeps talking
        fn.addProperty("behavior", blocking ? "BLOCKING" : "NON_BLOCKING");
        if (params.length > 0) {
            JsonObject properties = new JsonObject();
            JsonArray required = new JsonArray();
            for (JsonObject p : params) {
                String pName = p.remove("__name").getAsString();
                boolean req = p.has("__required") && p.remove("__required").getAsBoolean();
                properties.add(pName, p);
                if (req) {
                    required.add(pName);
                }
            }
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "OBJECT");
            schema.add("properties", properties);
            if (!required.isEmpty()) {
                schema.add("required", required);
            }
            fn.add("parameters", schema);
        }
        return fn;
    }

    private static JsonObject param(String name, String type, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("__name", name);
        p.addProperty("type", type);
        p.addProperty("description", description);
        return p;
    }

    private static JsonObject required(JsonObject param) {
        param.addProperty("__required", true);
        return param;
    }

    private static JsonObject enumParam(String name, String description, List<String> values) {
        JsonObject p = param(name, "STRING", description);
        JsonArray options = new JsonArray();
        values.forEach(options::add);
        p.add("enum", options);
        p.addProperty("__required", true);
        return p;
    }

    // ------------------------------------------------------------------ execution (main thread)

    public Result execute(String name, JsonObject args) {
        AiCharacter character = plugin.character();
        if (!character.isSpawned() && !name.equals("set_time") && !name.equals("set_weather")) {
            return Result.fail("You don't have a body in the world right now.");
        }
        return switch (name) {
            case "look_around" -> lookAround();
            case "follow_player" -> withPlayer(args, p -> {
                character.follow(p);
                return Result.ok("Now following " + p.getName());
            });
            case "come_here" -> withPlayer(args, p -> {
                character.comeTo(p);
                return Result.ok("Walking to " + p.getName());
            });
            case "stay" -> {
                character.stay();
                yield Result.ok("Staying here");
            }
            case "emote" -> {
                String emote = str(args, "emote").toLowerCase(Locale.ROOT);
                yield EMOTES.contains(emote) && character.emote(emote)
                        ? Result.ok("Did the " + emote + " animation")
                        : Result.fail("Unknown emote " + emote);
            }
            case "hold_item" -> holdItem(str(args, "item"));
            case "give_item" -> withPlayer(args, p -> giveItem(p, str(args, "item"), integer(args, "amount", 1)));
            case "break_blocks" -> breakBlocks(args);
            case "fight_mobs" -> character.fight(cfg().getInt("actions.fight-seconds", 20))
                    ? Result.ok("Fighting the hostile mobs nearby")
                    : Result.fail("There are no hostile mobs nearby");
            case "set_time" -> setTime(str(args, "time"));
            case "set_weather" -> setWeather(str(args, "weather"));
            case "lightning" -> withPlayer(args, p -> {
                p.getWorld().strikeLightningEffect(p.getLocation().add(1.5, 0, 1.5));
                return Result.ok("Lightning struck next to " + p.getName());
            });
            case "troll_player" -> withPlayer(args, p -> troll(p, str(args, "troll")));
            default -> Result.fail("No such tool: " + name);
        };
    }

    private interface PlayerAction {
        Result run(Player player);
    }

    private Result withPlayer(JsonObject args, PlayerAction action) {
        Player player = resolvePlayer(str(args, "player"));
        if (player == null) {
            return Result.fail("Couldn't find that player. Online: " + onlineNames());
        }
        return action.run(player);
    }

    /** Find a player by (part of) their name, or fall back to whoever spoke last. */
    private Player resolvePlayer(String name) {
        if (name != null && !name.isBlank()) {
            String n = name.trim();
            Player exact = Bukkit.getPlayerExact(n);
            if (exact != null) {
                return exact;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).contains(n.toLowerCase(Locale.ROOT))) {
                    return p;
                }
            }
            return null;
        }
        UUID last = plugin.brain() == null ? null : plugin.brain().lastSpeaker();
        Player speaker = last == null ? null : Bukkit.getPlayer(last);
        if (speaker != null) {
            return speaker;
        }
        // nearest player as a last resort
        Mannequin body = plugin.character().body();
        Player nearest = null;
        double best = Double.MAX_VALUE;
        if (body != null) {
            for (Player p : body.getWorld().getPlayers()) {
                double d = p.getLocation().distanceSquared(body.getLocation());
                if (d < best) {
                    best = d;
                    nearest = p;
                }
            }
        }
        return nearest;
    }

    private Result lookAround() {
        Mannequin body = plugin.character().body();
        Location here = body.getLocation();
        World world = here.getWorld();
        JsonObject out = new JsonObject();

        JsonObject you = new JsonObject();
        you.addProperty("position", here.getBlockX() + " " + here.getBlockY() + " " + here.getBlockZ());
        you.addProperty("world", world.getName());
        you.addProperty("health", Math.round(body.getHealth()));
        you.addProperty("holding", itemName(body.getEquipment().getItemInMainHand().getType()));
        you.addProperty("currently", plugin.character().mode().name().toLowerCase(Locale.ROOT));
        out.add("you", you);

        long time = world.getTime();
        out.addProperty("time_of_day", time < 12000 ? "day" : time < 13500 ? "sunset" : time < 23000 ? "night" : "sunrise");
        out.addProperty("weather", world.isThundering() ? "thunderstorm" : world.hasStorm() ? "raining" : "clear");
        out.addProperty("biome", world.getBiome(here).key().value());
        out.addProperty("light_level", here.getBlock().getLightLevel());

        JsonArray players = new JsonArray();
        for (Player p : world.getPlayers()) {
            double distance = p.getLocation().distance(here);
            if (distance > 48) {
                continue;
            }
            JsonObject info = new JsonObject();
            info.addProperty("name", p.getName());
            info.addProperty("distance_blocks", Math.round(distance));
            info.addProperty("holding", itemName(p.getInventory().getItemInMainHand().getType()));
            info.addProperty("health", Math.round(p.getHealth()) + "/20");
            info.addProperty("game_mode", p.getGameMode().name().toLowerCase(Locale.ROOT));
            players.add(info);
        }
        out.add("players_nearby", players);

        Map<String, Integer> mobs = new TreeMap<>();
        for (Entity e : body.getNearbyEntities(24, 12, 24)) {
            if (e instanceof Player || !(e instanceof org.bukkit.entity.LivingEntity)) {
                continue;
            }
            mobs.merge(e.getType().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        JsonObject mobJson = new JsonObject();
        mobs.forEach(mobJson::addProperty);
        out.add("mobs_nearby", mobJson);

        JsonArray online = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            online.add(p.getName());
        }
        out.add("everyone_online", online);
        return new Result(out, true);
    }

    private Result holdItem(String id) {
        if (id.isBlank() || id.equalsIgnoreCase("nothing") || id.equalsIgnoreCase("air")) {
            plugin.character().hold(null);
            return Result.ok("Hand is empty now");
        }
        Material material = Material.matchMaterial(id);
        if (material == null || !material.isItem() || material.isAir()) {
            return Result.fail("Unknown item " + id);
        }
        plugin.character().hold(material);
        return Result.ok("Now holding " + itemName(material));
    }

    private Result giveItem(Player player, String id, int amount) {
        Material material = Material.matchMaterial(id);
        if (material == null || !material.isItem() || material.isAir()) {
            return Result.fail("There is no item called " + id);
        }
        for (String blocked : cfg().getStringList("actions.blocked-items")) {
            if (material.name().equalsIgnoreCase(blocked)) {
                return Result.fail("You are not allowed to give away " + itemName(material));
            }
        }
        int max = cfg().getInt("actions.max-give-amount", 16);
        int count = Math.max(1, Math.min(amount, max));
        plugin.character().give(player, material, count);
        return Result.ok("Threw " + count + " " + itemName(material) + " to " + player.getName());
    }

    // ------------------------------------------------------------------ breaking blocks

    private Result breakBlocks(JsonObject args) {
        if (!cfg().getBoolean("actions.allow-breaking-blocks", true)) {
            return Result.fail("You aren't allowed to break blocks on this server");
        }
        String what = str(args, "block").toLowerCase(Locale.ROOT).trim();
        int max = cfg().getInt("actions.max-blocks-per-request", 16);
        int count = Math.max(1, Math.min(integer(args, "count", 1), max));
        Mannequin body = plugin.character().body();

        List<Block> blocks;
        if (what.isEmpty() || what.equals("looked_at") || what.equals("this") || what.equals("that")) {
            Player player = resolvePlayer(str(args, "player"));
            if (player == null) {
                return Result.fail("Couldn't find that player. Online: " + onlineNames());
            }
            Block looked = player.getTargetBlockExact(8);
            if (looked == null || looked.getType().isAir()) {
                return Result.fail(player.getName() + " isn't looking at a block close enough");
            }
            if (!canBreak(looked)) {
                return Result.fail("That block is protected and can't be broken");
            }
            Material type = looked.getType();
            blocks = new ArrayList<>();
            blocks.add(looked);
            if (count > 1) {
                for (Block extra : findBlocks(looked.getLocation(), m -> m == type, count)) {
                    if (!extra.equals(looked) && blocks.size() < count) {
                        blocks.add(extra);
                    }
                }
            }
        } else {
            Predicate<Material> matcher = matcher(what);
            if (matcher == null) {
                return Result.fail("There is no block called " + what);
            }
            blocks = findBlocks(body.getLocation(), matcher, count);
            if (blocks.isEmpty()) {
                return Result.fail("There is no " + what + " within " + cfg().getInt("actions.mining-radius", 12) + " blocks of you");
            }
        }
        int queued = plugin.character().mine(blocks);
        return Result.ok("Breaking " + queued + " block(s) of " + itemName(blocks.get(0).getType()) + ". The items will drop on the ground.");
    }

    /** "tree", "stone", "oak_log"... -> which materials count. Null if unknown. */
    private static Predicate<Material> matcher(String what) {
        switch (what) {
            case "tree", "trees", "wood", "log", "logs" -> {
                return Tag.LOGS::isTagged;
            }
            case "leaves", "leaf" -> {
                return Tag.LEAVES::isTagged;
            }
            case "stone", "rock", "rocks" -> {
                return m -> Tag.BASE_STONE_OVERWORLD.isTagged(m) || m == Material.COBBLESTONE;
            }
            case "dirt", "grass", "ground" -> {
                return Tag.DIRT::isTagged;
            }
            case "sand" -> {
                return Tag.SAND::isTagged;
            }
            case "ore", "ores" -> {
                return m -> m.name().endsWith("_ORE") || m == Material.ANCIENT_DEBRIS;
            }
            default -> {
                Material exact = Material.matchMaterial(what);
                if (exact == null && what.endsWith("s")) {
                    exact = Material.matchMaterial(what.substring(0, what.length() - 1));
                }
                if (exact == null || !exact.isBlock() || exact.isAir()) {
                    return null;
                }
                Material target = exact;
                return m -> m == target;
            }
        }
    }

    /** The closest breakable blocks that match, nearest first. */
    private List<Block> findBlocks(Location center, Predicate<Material> matcher, int count) {
        int radius = cfg().getInt("actions.mining-radius", 12);
        List<Block> found = new ArrayList<>();
        Block origin = center.getBlock();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 10; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block block = origin.getRelative(dx, dy, dz);
                    if (matcher.test(block.getType()) && canBreak(block)) {
                        found.add(block);
                    }
                }
            }
        }
        Location c = origin.getLocation();
        found.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(c)));
        return found.size() > count ? new ArrayList<>(found.subList(0, count)) : found;
    }

    private boolean canBreak(Block block) {
        Material type = block.getType();
        if (type.isAir() || block.isLiquid() || type.getHardness() < 0) {
            return false; // air, water/lava, bedrock-like blocks
        }
        for (String blocked : cfg().getStringList("actions.unbreakable-blocks")) {
            if (type.name().equalsIgnoreCase(blocked)) {
                return false;
            }
        }
        return !(cfg().getBoolean("actions.protect-containers", true) && block.getState() instanceof Container);
    }

    private Result setTime(String value) {
        long ticks = switch (value.toLowerCase(Locale.ROOT)) {
            case "day" -> 1000;
            case "noon" -> 6000;
            case "sunset" -> 12000;
            case "night" -> 13500;
            case "midnight" -> 18000;
            default -> -1;
        };
        if (ticks < 0) {
            return Result.fail("Unknown time " + value);
        }
        World world = worldOfCharacter();
        world.setTime(ticks);
        return Result.ok("It is now " + value);
    }

    private Result setWeather(String value) {
        World world = worldOfCharacter();
        switch (value.toLowerCase(Locale.ROOT)) {
            case "clear" -> {
                world.setStorm(false);
                world.setThundering(false);
            }
            case "rain" -> {
                world.setStorm(true);
                world.setThundering(false);
            }
            case "thunder" -> {
                world.setStorm(true);
                world.setThundering(true);
            }
            default -> {
                return Result.fail("Unknown weather " + value);
            }
        }
        return Result.ok("Weather is now " + value);
    }

    private Result troll(Player player, String troll) {
        if (!trollsAvailable()) {
            return Result.fail("Trolls aren't available");
        }
        String id = troll.toLowerCase(Locale.ROOT);
        if (!cfg().getStringList("actions.trolls").contains(id)) {
            return Result.fail("That troll isn't allowed");
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "troll " + player.getName() + " " + id);
        return Result.ok("Used the " + id + " troll on " + player.getName());
    }

    private World worldOfCharacter() {
        Mannequin body = plugin.character().body();
        return body != null ? body.getWorld() : Bukkit.getWorlds().get(0);
    }

    private static String onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return String.join(", ", names);
    }

    private static String itemName(Material material) {
        return material == null || material.isAir() ? "nothing" : material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String str(JsonObject args, String key) {
        return args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsString() : "";
    }

    private static int integer(JsonObject args, String key, int fallback) {
        try {
            return args.has(key) ? (int) Math.round(args.get(key).getAsDouble()) : fallback;
        } catch (Exception ex) {
            return fallback;
        }
    }
}
