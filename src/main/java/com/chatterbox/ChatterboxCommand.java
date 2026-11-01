package com.chatterbox;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /chatterbox (aliases /cb, /aibuddy) */
public final class ChatterboxCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("help", "spawn", "remove", "here", "follow", "stay",
            "come", "persona", "direct", "ask", "emote", "mute", "unmute", "reset", "status", "skin", "name", "reload");

    private final ChatterboxPlugin plugin;

    public ChatterboxCommand(ChatterboxPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("ask")) {
            ask(sender, args);
            return true;
        }
        if (!sender.hasPermission("chatterbox.admin")) {
            Msg.send(sender, "<red>You don't have permission to do that.");
            return true;
        }
        AiCharacter character = plugin.character();
        switch (sub) {
            case "spawn" -> {
                Player p = player(sender);
                if (p == null) {
                    return true;
                }
                if (args.length >= 2 && !plugin.switchPersona(args[1].toLowerCase(Locale.ROOT))) {
                    Msg.send(sender, "<red>No persona called <white><id></white>. Options: <white><list>",
                            Msg.ph("id", args[1]), Msg.ph("list", String.join(", ", plugin.personaIds())));
                    return true;
                }
                plugin.spawnCharacter(p.getLocation());
                Msg.send(sender, "<green>Spawned <white><name></white>. Connecting to Gemini...",
                        Msg.ph("name", plainName()));
                if (!plugin.hasVoiceChat()) {
                    Msg.send(sender, "<yellow>Simple Voice Chat isn't installed, so it can only chat by text.");
                }
            }
            case "remove" -> {
                plugin.removeCharacter();
                Msg.send(sender, "<gray>Character removed and disconnected.");
            }
            case "here" -> {
                Player p = player(sender);
                if (p != null && requireSpawned(sender)) {
                    character.teleport(p.getLocation());
                    Msg.send(sender, "<green>Teleported to you.");
                }
            }
            case "follow", "come" -> {
                if (!requireSpawned(sender)) {
                    return true;
                }
                Player target = args.length >= 2 ? Bukkit.getPlayer(args[1]) : player(sender);
                if (target == null) {
                    Msg.send(sender, "<red>Player not found.");
                    return true;
                }
                if (sub.equals("follow")) {
                    character.follow(target);
                } else {
                    character.comeTo(target);
                }
                Msg.send(sender, "<green><name> is <action> <player>.", Msg.ph("name", plainName()),
                        Msg.ph("action", sub.equals("follow") ? "following" : "walking to"), Msg.ph("player", target.getName()));
            }
            case "stay" -> {
                if (requireSpawned(sender)) {
                    character.stay();
                    Msg.send(sender, "<green>Staying put.");
                }
            }
            case "persona" -> {
                if (args.length < 2) {
                    Msg.send(sender, "<gray>Current: <white><id></white>. Options: <white><list>",
                            Msg.ph("id", plugin.persona().id()), Msg.ph("list", String.join(", ", plugin.personaIds())));
                    return true;
                }
                if (plugin.switchPersona(args[1].toLowerCase(Locale.ROOT))) {
                    Msg.send(sender, "<green>Now playing <white><name></white> (fresh conversation).", Msg.ph("name", plainName()));
                } else {
                    Msg.send(sender, "<red>No persona called <white><id></white>.", Msg.ph("id", args[1]));
                }
            }
            case "direct" -> {
                if (args.length < 2) {
                    Msg.send(sender, "<red>Usage: /cb direct <white>\\<secret instruction></white>");
                    return true;
                }
                Brain brain = plugin.brain();
                if (brain == null || !brain.isConnected()) {
                    Msg.send(sender, "<red>The character isn't connected right now.");
                    return true;
                }
                brain.direct(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                Msg.send(sender, "<gray><italic>Director note sent.");
            }
            case "emote" -> {
                if (args.length < 2 || !ToolBox.EMOTES.contains(args[1].toLowerCase(Locale.ROOT))) {
                    Msg.send(sender, "<red>Emotes: <white><list>", Msg.ph("list", String.join(", ", ToolBox.EMOTES)));
                    return true;
                }
                if (requireSpawned(sender)) {
                    character.emote(args[1].toLowerCase(Locale.ROOT));
                }
            }
            case "mute", "unmute" -> {
                Brain brain = plugin.brain();
                if (brain == null) {
                    Msg.send(sender, "<red>The character isn't running.");
                    return true;
                }
                brain.setMuted(sub.equals("mute"));
                Msg.send(sender, sub.equals("mute") ? "<yellow>Muted: it won't listen or talk until /cb unmute."
                        : "<green>Unmuted: it's listening again.");
            }
            case "reset" -> {
                if (!character.isSpawned()) {
                    Msg.send(sender, "<red>Spawn the character first with /cb spawn.");
                    return true;
                }
                if (plugin.startBrain()) {
                    Msg.send(sender, "<green>Started a fresh conversation (it forgot everything).");
                }
            }
            case "status" -> status(sender);
            case "skin", "name" -> {
                if (args.length < 2) {
                    Msg.send(sender, "<red>Usage: /cb <sub> \\<value>", Msg.ph("sub", sub));
                    return true;
                }
                String value = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                String path = "personas." + plugin.persona().id() + "." + sub;
                plugin.getConfig().set(path, value);
                plugin.saveConfig();
                plugin.reloadEverything();
                Msg.send(sender, "<green>Updated <white><sub></white> of persona <white><id></white>.",
                        Msg.ph("sub", sub), Msg.ph("id", plugin.persona().id()));
            }
            case "reload" -> {
                plugin.reloadEverything();
                Msg.send(sender, "<green>Config reloaded<gray><extra>.",
                        Msg.ph("extra", character.isSpawned() ? " and reconnected" : ""));
            }
            default -> help(sender, label);
        }
        return true;
    }

    private void ask(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            Msg.send(sender, "<red>Only players can do that (try /cb direct).");
            return;
        }
        if (!p.hasPermission("chatterbox.talk")) {
            Msg.send(sender, "<red>You don't have permission to do that.");
            return;
        }
        Brain brain = plugin.brain();
        if (brain == null || !brain.isConnected() || args.length < 2) {
            Msg.send(sender, brain == null || !brain.isConnected()
                    ? "<red>The character isn't connected right now." : "<red>Usage: /cb ask <white>\\<message>");
            return;
        }
        brain.chat(p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
    }

    private void status(CommandSender sender) {
        Brain brain = plugin.brain();
        AiCharacter character = plugin.character();
        String connection;
        if (plugin.apiKey() == null) {
            connection = "<red>no API key in config.yml";
        } else if (brain == null) {
            connection = "<gray>not running (spawn it first)";
        } else if (brain.isConnected()) {
            connection = brain.isMuted() ? "<yellow>connected, muted" : "<green>connected";
        } else {
            String problem = brain.lastProblem();
            connection = "<yellow>connecting..." + (problem.isEmpty() ? "" : " <red>(" + Msg.escape(problem) + ")");
        }
        Msg.send(sender, "<gray>Persona: <white><persona></white> (<id>)", Msg.ph("persona", plainName()),
                Msg.ph("id", plugin.persona().id()));
        sender.sendMessage(Msg.mm("<gray>  Gemini: " + connection));
        sender.sendMessage(Msg.mm("<gray>  Model: <white><model>",
                Msg.ph("model", plugin.getConfig().getString("gemini.model", "gemini-3.8-live"))));
        sender.sendMessage(Msg.mm("<gray>  Voice chat: " + (plugin.hasVoiceChat() ? "<green>hooked in" : "<yellow>not installed (text only)")));
        sender.sendMessage(Msg.mm("<gray>  Body: " + (character.isSpawned()
                ? "<green>spawned, " + character.mode().name().toLowerCase(Locale.ROOT) : "<gray>not spawned")));
        sender.sendMessage(Msg.mm("<gray>  Actions: <white><count></white> available",
                Msg.ph("count", String.valueOf(plugin.tools().declarations().size()))));
    }

    private void help(CommandSender sender, String label) {
        String l = "/" + label;
        String[][] lines = {
                {"spawn [persona]", "spawn the character where you stand"},
                {"remove", "remove it and disconnect"},
                {"here", "teleport it to you"},
                {"follow [player] / come / stay", "move it around"},
                {"persona [id]", "list or switch personas"},
                {"direct <note>", "secret instruction it acts on (great for videos)"},
                {"ask <message>", "talk to it by command"},
                {"emote <type>", "trigger an animation"},
                {"mute / unmute", "pause listening and talking"},
                {"reset", "fresh conversation"},
                {"skin <username> / name <name>", "change the current persona"},
                {"status", "connection info"},
                {"reload", "reload config.yml"}};
        Msg.send(sender, "<gold>Commands:");
        for (String[] line : lines) {
            sender.sendMessage(Msg.mm("<yellow>" + Msg.escape(l + " " + line[0]) + " <gray>- " + Msg.escape(line[1])));
        }
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) {
            return p;
        }
        Msg.send(sender, "<red>Only players can do that.");
        return null;
    }

    private boolean requireSpawned(CommandSender sender) {
        if (plugin.character().isSpawned()) {
            return true;
        }
        Msg.send(sender, "<red>Spawn the character first with /cb spawn.");
        return false;
    }

    private String plainName() {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(Msg.mm(plugin.persona().name()));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("chatterbox.admin")) {
                options.addAll(SUBCOMMANDS);
            } else {
                options.add("ask");
            }
        } else if (args.length == 2 && sender.hasPermission("chatterbox.admin")) {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "spawn", "persona" -> options.addAll(plugin.personaIds());
                case "follow", "come" -> Bukkit.getOnlinePlayers().forEach(p -> options.add(p.getName()));
                case "emote" -> options.addAll(ToolBox.EMOTES);
                default -> {
                }
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
