package com.chatterbox;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Chat message helpers (MiniMessage colors). */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<dark_gray>[<gradient:#7f7fd5:#86a8e7:#91eae4>Chatterbox</gradient>]</dark_gray> ";

    private Msg() {
    }

    public static Component mm(String text, TagResolver... resolvers) {
        return MM.deserialize(text == null ? "" : text, resolvers);
    }

    public static TagResolver ph(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    public static String escape(String text) {
        return MM.escapeTags(text);
    }

    public static void send(CommandSender to, String text, TagResolver... resolvers) {
        to.sendMessage(mm(PREFIX + text, resolvers));
    }

    /** Tell every admin (and the console). */
    public static void admins(String text, TagResolver... resolvers) {
        Component message = mm(PREFIX + text, resolvers);
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("chatterbox.admin")) {
                player.sendMessage(message);
            }
        }
    }
}
