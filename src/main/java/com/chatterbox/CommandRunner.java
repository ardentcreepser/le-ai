package com.chatterbox;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandException;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Lets the AI run commands like an opped player: runs them from the AI's body (so ~ and @s are the AI),
 * captures the output so the AI knows if it worked, and refuses the blocked admin commands.
 */
public final class CommandRunner {

    /** What happened when the AI ran a command. */
    public record Outcome(boolean ok, String output) {
    }

    private final ChatterboxPlugin plugin;

    public CommandRunner(ChatterboxPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public boolean enabled() {
        return cfg().getBoolean("actions.commands.enabled", true);
    }

    public String whoCanAsk() {
        return cfg().getString("actions.commands.who-can-ask", "everyone").toLowerCase(Locale.ROOT);
    }

    /** Why this player can't make the AI run commands, or null if they can. */
    public String speakerProblem(UUID speaker) {
        String who = whoCanAsk();
        if (who.equals("everyone") || speaker == null) {
            return null;
        }
        Player player = Bukkit.getPlayer(speaker);
        if (player == null) {
            return null;
        }
        boolean allowed = who.equals("ops") ? player.isOp() : player.hasPermission("chatterbox.commands");
        return allowed ? null : player.getName() + " isn't allowed to make you run commands";
    }

    // ------------------------------------------------------------------ blocklist

    /** Why this command is blocked, or null if it's allowed. Also checks commands hidden inside /execute. */
    public String blockedReason(String command) {
        return check(command, 0);
    }

    private String check(String command, int depth) {
        if (depth > 8) {
            return "Too many commands inside each other";
        }
        String[] tokens = command.trim().split("\\s+");
        if (tokens.length == 0 || tokens[0].isEmpty()) {
            return "The command is empty";
        }
        String label = stripSlashes(tokens[0]).toLowerCase(Locale.ROOT);
        String bare = label.contains(":") ? label.substring(label.indexOf(':') + 1) : label;

        Set<String> blocked = lowerSet(cfg().getStringList("actions.commands.blocked"));
        Set<String> names = new HashSet<>(List.of(label, bare));
        Command known = lookup(label);
        if (known != null) {
            names.add(known.getName().toLowerCase(Locale.ROOT));
            if (known instanceof PluginIdentifiableCommand owned) {
                String pluginName = owned.getPlugin().getName();
                if (lowerSet(cfg().getStringList("actions.commands.blocked-plugins")).contains(pluginName.toLowerCase(Locale.ROOT))) {
                    return "Commands from " + pluginName + " are off-limits";
                }
            }
        }
        for (String name : names) {
            if (blocked.contains(name)) {
                return "/" + bare + " is not allowed";
            }
        }

        // "/execute ... run <command>" and "/return run <command>" can hide another command
        if (bare.equals("execute") || bare.equals("return")) {
            for (int i = 1; i < tokens.length - 1; i++) {
                if (tokens[i].equalsIgnoreCase("run")) {
                    String inner = String.join(" ", Arrays.copyOfRange(tokens, i + 1, tokens.length));
                    String reason = check(inner, depth + 1);
                    if (reason != null) {
                        return reason;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ running (main thread only)

    public Outcome run(Mannequin body, String command) {
        String clean = stripSlashes(command.trim());
        List<String> lines = new ArrayList<>();
        boolean[] error = {false};
        CommandSender sender = Bukkit.createCommandSender(message -> {
            Component component = Component.empty().append(message);
            String text = PlainTextComponentSerializer.plainText().serialize(component).trim();
            if (text.isEmpty()) {
                return;
            }
            synchronized (lines) {
                lines.add(text);
            }
            if (NamedTextColor.RED.equals(message.asComponent().color())) {
                error[0] = true; // Minecraft shows errors in red
            }
        });

        // Vanilla commands run "as" and "at" the AI's body. Plugin commands don't understand that, so they run as is.
        Command known = lookup(clean.split("\\s+")[0]);
        boolean pluginCommand = known instanceof PluginIdentifiableCommand;
        String line = !pluginCommand && body != null && body.isValid()
                ? "execute as " + body.getUniqueId() + " at @s run " + clean
                : clean;

        boolean success;
        try {
            success = Bukkit.dispatchCommand(sender, line);
        } catch (CommandException ex) {
            Throwable root = ex;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            return log(clean, new Outcome(false, "The command crashed: " + root.getMessage()));
        }

        String output;
        synchronized (lines) {
            output = String.join("\n", lines);
        }
        if (output.length() > 800) {
            output = output.substring(0, 800) + "...";
        }
        boolean ok = success && !error[0];
        if (output.isEmpty()) {
            output = ok ? "Done (the command gave no output)" : "The command failed";
        }
        return log(clean, new Outcome(ok, output));
    }

    private Outcome log(String command, Outcome outcome) {
        String name = PlainTextComponentSerializer.plainText().serialize(Msg.mm(plugin.persona().name()));
        String oneLine = outcome.output().replace('\n', ' ');
        plugin.getLogger().info("[AI command] " + name + " ran /" + command + " -> " + (outcome.ok() ? "" : "FAILED: ") + oneLine);
        if (cfg().getBoolean("actions.commands.announce-to-admins", false)) {
            Msg.admins("<gray><name> ran <white>/<command>", Msg.ph("name", name), Msg.ph("command", command));
        }
        return outcome;
    }

    // ------------------------------------------------------------------ helpers

    private static Command lookup(String label) {
        String clean = stripSlashes(label).toLowerCase(Locale.ROOT);
        try {
            Command command = Bukkit.getCommandMap().getCommand(clean);
            if (command == null && clean.contains(":")) {
                command = Bukkit.getCommandMap().getCommand(clean.substring(clean.indexOf(':') + 1));
            }
            return command;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String stripSlashes(String text) {
        String out = text;
        while (out.startsWith("/")) {
            out = out.substring(1);
        }
        return out;
    }

    private static Set<String> lowerSet(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String value : values) {
            out.add(stripSlashes(value.trim()).toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
