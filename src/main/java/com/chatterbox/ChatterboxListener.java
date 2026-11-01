package com.chatterbox;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Chat to the AI, reactions to getting hit or poked, and cleanup of stray bodies. */
public final class ChatterboxListener implements Listener {

    private final ChatterboxPlugin plugin;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    public ChatterboxListener(ChatterboxPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean cooledDown(UUID player, String what, long millis) {
        UUID key = new UUID(player.getMostSignificantBits() ^ what.hashCode(), player.getLeastSignificantBits());
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(key);
        if (last != null && now - last < millis) {
            return false;
        }
        cooldowns.put(key, now);
        return true;
    }

    // ------------------------------------------------------------------ chat

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Brain brain = plugin.brain();
        if (brain == null || !brain.isConnected() || !player.hasPermission("chatterbox.talk")
                || !plugin.character().inChatRange(player.getUniqueId())) {
            return;
        }
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        String mode = plugin.getConfig().getString("chat.respond-to", "mention");
        if (mode.equalsIgnoreCase("mention")) {
            String name = PlainTextComponentSerializer.plainText().serialize(Msg.mm(plugin.persona().name()));
            if (!message.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
        } else if (mode.equalsIgnoreCase("off")) {
            return;
        }
        brain.chat(player, message);
    }

    // ------------------------------------------------------------------ getting hit / poked

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (!plugin.isOurs(event.getEntity())) {
            return;
        }
        if (plugin.getConfig().getBoolean("character.invulnerable", true)) {
            event.setCancelled(true);
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) {
            return;
        }
        Entity damager = byEntity.getDamager();
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
            damager = shooter;
        }
        if (damager instanceof Player attacker && plugin.getConfig().getBoolean("character.react-to-hits", true)
                && cooledDown(attacker.getUniqueId(), "hit", 6000)) {
            Brain brain = plugin.brain();
            if (brain != null && brain.isConnected()) {
                plugin.character().focusOn(attacker.getUniqueId(), 6000);
                brain.event(attacker.getName() + " just hit you", true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPoke(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !plugin.isOurs(event.getRightClicked())) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Brain brain = plugin.brain();
        if (brain != null && brain.isConnected() && player.hasPermission("chatterbox.talk")
                && cooledDown(player.getUniqueId(), "poke", 4000)) {
            plugin.character().focusOn(player.getUniqueId(), 8000);
            brain.event(player.getName() + " walked up and tapped you to get your attention", true);
        }
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (plugin.isOurs(entity) && entity != plugin.character().body()) {
                entity.remove(); // a leftover body from a crash
            }
        }
    }
}
