package com.chatterbox;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.LookAnchor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The AI's body: a Mannequin (the player-shaped entity from Minecraft 1.21.9+) that walks, follows,
 * fights, emotes and looks at whoever is talking. Everything here runs on the main server thread,
 * except the few volatile fields that the voice thread reads.
 */
public final class AiCharacter {

    public enum Mode { IDLE, FOLLOW, STAY, COME, FIGHT, MINE }

    private final ChatterboxPlugin plugin;
    private final NamespacedKey tag;

    private volatile Mannequin body;
    private Location lastLocation;
    private Mode mode = Mode.IDLE;
    private UUID target;
    private LivingEntity fightTarget;
    private long fightUntil;
    private ItemStack handBeforeFight;
    private int attackCooldown;

    // mining
    private final ArrayDeque<Block> mineQueue = new ArrayDeque<>();
    private Block mining;
    private int mineProgress;
    private int mineNeeded;
    private int mineWalkTicks;
    private ItemStack handBeforeMining;

    private volatile UUID focus;
    private volatile long focusUntil;
    private volatile Set<UUID> hearing = Set.of();
    private volatile Set<UUID> chatRange = Set.of();

    private Location lastProgress;
    private int stuckTicks;
    private int ticks;
    private boolean movedThisTick;
    private BukkitTask emote;

    public AiCharacter(ChatterboxPlugin plugin) {
        this.plugin = plugin;
        this.tag = plugin.key("character");
    }

    public NamespacedKey tag() {
        return tag;
    }

    public boolean isSpawned() {
        Mannequin b = body;
        return b != null && b.isValid();
    }

    public Mannequin body() {
        return body;
    }

    public Mode mode() {
        return mode;
    }

    // ------------------------------------------------------------------ spawning

    public void spawn(Location location, Persona persona) {
        remove();
        mode = Mode.IDLE;
        target = null;
        spawnBody(location, persona);
    }

    private void spawnBody(Location location, Persona persona) {
        World world = location.getWorld();
        body = world.spawn(location, Mannequin.class, m -> applyTo(m, persona));
        lastLocation = location.clone();
    }

    private void applyTo(Mannequin m, Persona persona) {
        m.customName(Msg.mm(persona.name()));
        m.setCustomNameVisible(true);
        String description = persona.description();
        m.setDescription(description == null || description.isBlank() ? null : Msg.mm(description));
        if (persona.hasValidSkin()) {
            m.setProfile(ResolvableProfile.resolvableProfile().name(persona.skin()).build());
        } else {
            m.setProfile(Mannequin.defaultProfile());
        }
        m.setPersistent(false); // never saved into the world, so no leftovers after a restart
        m.setRemoveWhenFarAway(false);
        m.setInvulnerable(plugin.getConfig().getBoolean("character.invulnerable", true));
        m.setImmovable(false);
        m.getPersistentDataContainer().set(tag, PersistentDataType.BYTE, (byte) 1);
    }

    /** Change name/skin of the spawned body (after switching persona). */
    public void applyPersona(Persona persona) {
        Mannequin b = body;
        if (b != null && b.isValid()) {
            applyTo(b, persona);
        }
    }

    public void remove() {
        cancelEmote();
        clearCrack();
        mineQueue.clear();
        mining = null;
        if (mode == Mode.MINE) {
            mode = Mode.IDLE;
        }
        Mannequin b = body;
        body = null;
        if (b != null && b.isValid()) {
            b.remove();
        }
        hearing = Set.of();
        chatRange = Set.of();
    }

    public void teleport(Location location) {
        Mannequin b = body;
        if (b != null && b.isValid()) {
            b.teleport(location);
            lastLocation = location.clone();
        } else {
            spawnBody(location, plugin.persona());
        }
    }

    public Location location() {
        Mannequin b = body;
        return b != null && b.isValid() ? b.getLocation() : lastLocation;
    }

    // ------------------------------------------------------------------ modes

    public void follow(Player player) {
        endFight();
        stopMining();
        mode = Mode.FOLLOW;
        target = player.getUniqueId();
    }

    public void comeTo(Player player) {
        endFight();
        stopMining();
        mode = Mode.COME;
        target = player.getUniqueId();
    }

    public void stay() {
        endFight();
        stopMining();
        mode = Mode.STAY;
        target = null;
        Mannequin b = body;
        if (b != null && b.isValid()) {
            b.setVelocity(new Vector(0, b.getVelocity().getY(), 0));
        }
    }

    /** Fight nearby hostile mobs for a while. Returns false if there are none. */
    public boolean fight(int seconds) {
        Mannequin b = body;
        if (b == null || !b.isValid()) {
            return false;
        }
        LivingEntity enemy = findEnemy(b, 16);
        if (enemy == null) {
            return false;
        }
        stopMining();
        fightTarget = enemy;
        fightUntil = System.currentTimeMillis() + seconds * 1000L;
        if (mode != Mode.FIGHT) {
            handBeforeFight = b.getEquipment().getItemInMainHand().clone();
            if (handBeforeFight.isEmpty()) {
                b.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
            }
        }
        mode = Mode.FIGHT;
        return true;
    }

    private void endFight() {
        if (mode != Mode.FIGHT) {
            return;
        }
        Mannequin b = body;
        if (b != null && b.isValid() && handBeforeFight != null) {
            b.getEquipment().setItemInMainHand(handBeforeFight);
        }
        handBeforeFight = null;
        fightTarget = null;
        mode = Mode.IDLE;
    }

    /** Walk to these blocks and break them one by one. Returns how many were queued. */
    public int mine(Collection<Block> blocks) {
        Mannequin b = body;
        if (b == null || !b.isValid() || blocks.isEmpty()) {
            return 0;
        }
        endFight();
        if (mode != Mode.MINE) {
            handBeforeMining = b.getEquipment().getItemInMainHand().clone();
        }
        clearCrack();
        mineQueue.clear();
        mineQueue.addAll(blocks);
        mining = null;
        mode = Mode.MINE;
        return blocks.size();
    }

    private void stopMining() {
        if (mode != Mode.MINE) {
            return;
        }
        clearCrack();
        mineQueue.clear();
        mining = null;
        Mannequin b = body;
        if (b != null && b.isValid() && handBeforeMining != null) {
            b.getEquipment().setItemInMainHand(handBeforeMining);
        }
        handBeforeMining = null;
        mode = Mode.IDLE;
    }

    /** Pick the next block to break, skipping ones that are already gone. */
    private boolean nextMineBlock() {
        clearCrack();
        mining = null;
        while (!mineQueue.isEmpty()) {
            Block next = mineQueue.poll();
            if (!next.getType().isAir() && !next.isLiquid()) {
                mining = next;
                mineProgress = 0;
                mineWalkTicks = 0;
                // roughly how long a player with a good tool takes: stone ~1s, logs ~1.2s, dirt ~0.3s
                float hardness = next.getType().getHardness();
                mineNeeded = Math.max(6, Math.min(80, Math.round(hardness * 12)));
                return true;
            }
        }
        return false;
    }

    /** Mining step. Returns where to walk, or null if we're standing still (breaking or done). */
    private Location mineTick(Mannequin b) {
        if (mining == null || mining.getType().isAir()) {
            if (!nextMineBlock()) {
                stopMining();
                return null;
            }
        }
        Location center = mining.getLocation().add(0.5, 0.5, 0.5);
        double reach = plugin.getConfig().getDouble("actions.mining-reach", 5.0);
        if (b.getEyeLocation().distance(center) > reach) {
            if (++mineWalkTicks > 100) {
                nextMineBlock(); // can't get to it in 5 seconds, skip it
            }
            return center;
        }

        Material type = mining.getType();
        ItemStack tool = toolFor(type);
        if (b.getEquipment().getItemInMainHand().getType() != tool.getType()) {
            b.getEquipment().setItemInMainHand(tool);
        }
        b.lookAt(center.getX(), center.getY(), center.getZ(), LookAnchor.EYES);
        b.setVelocity(new Vector(0, b.getVelocity().getY(), 0));
        mineProgress++;
        if (mineProgress % 4 == 1) {
            b.swingMainHand();
            b.getWorld().playSound(center, mining.getBlockData().getSoundGroup().getHitSound(), 0.6f, 0.8f);
        }
        float progress = Math.min(0.99f, (float) mineProgress / mineNeeded);
        for (Player viewer : b.getWorld().getPlayers()) {
            if (viewer.getLocation().distanceSquared(center) < 48 * 48) {
                viewer.sendBlockDamage(mining.getLocation(), progress, b);
            }
        }
        if (mineProgress >= mineNeeded) {
            Block done = mining;
            clearCrack();
            done.breakNaturally(tool, true);
            mining = null;
        }
        return null;
    }

    private void clearCrack() {
        Mannequin b = body;
        if (mining == null || b == null || !b.isValid()) {
            return;
        }
        for (Player viewer : b.getWorld().getPlayers()) {
            viewer.sendBlockDamage(mining.getLocation(), 0f, b);
        }
    }

    private static ItemStack toolFor(Material type) {
        if (Tag.MINEABLE_AXE.isTagged(type)) {
            return new ItemStack(Material.DIAMOND_AXE);
        }
        if (Tag.MINEABLE_SHOVEL.isTagged(type)) {
            return new ItemStack(Material.DIAMOND_SHOVEL);
        }
        if (Tag.MINEABLE_HOE.isTagged(type)) {
            return new ItemStack(Material.DIAMOND_HOE);
        }
        return new ItemStack(Material.DIAMOND_PICKAXE);
    }

    /** Look at this player for a while (used when they talk to us). Safe to call from any thread. */
    public void focusOn(UUID player, long millis) {
        focus = player;
        focusUntil = System.currentTimeMillis() + millis;
    }

    public boolean canHear(UUID player) {
        return hearing.contains(player);
    }

    public boolean inChatRange(UUID player) {
        return chatRange.contains(player);
    }

    // ------------------------------------------------------------------ actions

    public void hold(Material material) {
        Mannequin b = body;
        if (b != null && b.isValid()) {
            b.getEquipment().setItemInMainHand(material == null ? null : new ItemStack(material));
        }
    }

    /** Throw items to a player. */
    public void give(Player player, Material material, int amount) {
        Mannequin b = body;
        if (b == null || !b.isValid()) {
            return;
        }
        b.lookAt(player.getEyeLocation().getX(), player.getEyeLocation().getY(), player.getEyeLocation().getZ(), LookAnchor.EYES);
        b.swingMainHand();
        Location from = b.getEyeLocation();
        Vector toss = player.getLocation().toVector().subtract(from.toVector());
        double distance = toss.length();
        toss.normalize().multiply(Math.min(0.5, 0.12 + distance * 0.05)).setY(0.25);
        int left = amount;
        while (left > 0) {
            int stackSize = Math.min(left, material.getMaxStackSize());
            Item item = b.getWorld().dropItem(from, new ItemStack(material, stackSize));
            item.setVelocity(toss);
            item.setPickupDelay(10);
            left -= stackSize;
        }
        b.getWorld().playSound(from, Sound.ENTITY_ITEM_PICKUP, 1f, 0.8f);
    }

    public boolean emote(String type) {
        Mannequin b = body;
        if (b == null || !b.isValid()) {
            return false;
        }
        cancelEmote();
        float baseYaw = b.getLocation().getYaw();
        int[] tick = {0};
        emote = new BukkitRunnable() {
            @Override
            public void run() {
                Mannequin m = body;
                if (m == null || !m.isValid()) {
                    cancel();
                    return;
                }
                boolean done = emoteStep(type, m, tick[0]++, baseYaw);
                if (done) {
                    cancel();
                    setPose(m, Pose.STANDING, false);
                    emote = null;
                }
            }
        }.runTaskTimer(plugin, 0L, 1L);
        return true;
    }

    private boolean emoteStep(String type, Mannequin m, int t, float baseYaw) {
        switch (type) {
            case "wave" -> {
                if (t % 6 == 0) {
                    m.swingMainHand();
                }
                return t >= 30;
            }
            case "jump" -> {
                if (t == 0 && m.isOnGround()) {
                    m.setVelocity(m.getVelocity().setY(0.42));
                }
                return t >= 12;
            }
            case "crouch" -> {
                if (t % 5 == 0) {
                    setPose(m, (t / 5) % 2 == 0 ? Pose.SNEAKING : Pose.STANDING, true);
                }
                return t >= 40;
            }
            case "dance" -> {
                if (t % 4 == 0) {
                    setPose(m, (t / 4) % 2 == 0 ? Pose.SNEAKING : Pose.STANDING, true);
                }
                if (t % 20 == 10 && m.isOnGround()) {
                    m.setVelocity(m.getVelocity().setY(0.42));
                }
                m.setRotation(baseYaw + t * 12f, 0f);
                if (t % 10 == 0) {
                    m.getWorld().spawnParticle(Particle.NOTE, m.getLocation().add(0, 2.3, 0), 1);
                }
                return t >= 80;
            }
            case "spin" -> {
                m.setRotation(baseYaw + t * 30f, 0f);
                return t >= 24;
            }
            case "nod" -> {
                m.setRotation(baseYaw, (t / 4) % 2 == 0 ? 30f : -5f);
                return t >= 24;
            }
            case "shake_head" -> {
                m.setRotation(baseYaw + ((t / 4) % 2 == 0 ? 35f : -35f), 0f);
                return t >= 24;
            }
            case "celebrate" -> {
                if (t % 10 == 0 && m.isOnGround()) {
                    m.setVelocity(m.getVelocity().setY(0.42));
                    m.swingMainHand();
                }
                if (t % 5 == 0) {
                    m.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, m.getLocation().add(0, 1.2, 0), 6, 0.4, 0.6, 0.4);
                }
                if (t == 0) {
                    m.getWorld().playSound(m.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.3f);
                }
                return t >= 40;
            }
            case "scared" -> {
                if (t == 0) {
                    m.getWorld().playSound(m.getLocation(), Sound.ENTITY_VILLAGER_HURT, 1f, 1.4f);
                    setPose(m, Pose.SNEAKING, true);
                }
                m.setRotation(baseYaw + (t % 2 == 0 ? 6f : -6f), 10f);
                return t >= 30;
            }
            default -> {
                return true;
            }
        }
    }

    private static void setPose(Mannequin m, Pose pose, boolean fixed) {
        try {
            m.setPose(pose, fixed);
        } catch (IllegalArgumentException ignored) {
            // this pose isn't allowed for mannequins in this version
        }
    }

    private void cancelEmote() {
        if (emote != null) {
            emote.cancel();
            emote = null;
            Mannequin b = body;
            if (b != null && b.isValid()) {
                setPose(b, Pose.STANDING, false);
            }
        }
    }

    // ------------------------------------------------------------------ every tick

    public void tick() {
        ticks++;
        Mannequin b = body;
        if (b == null) {
            return;
        }
        if (!b.isValid()) {
            // Its chunk unloaded or it died: bring it back once someone is near its last spot.
            if (lastLocation != null && lastLocation.isChunkLoaded() && anyPlayerNear(lastLocation, 48)) {
                spawnBody(lastLocation, plugin.persona());
            }
            return;
        }
        lastLocation = b.getLocation();

        if (ticks % 5 == 0) {
            updateRanges(b);
        }
        movedThisTick = false;
        moveTick(b);
        if (!movedThisTick && emote == null && ticks % 3 == 0) {
            lookTick(b);
        }
    }

    private void updateRanges(Mannequin b) {
        double hearRange = plugin.getConfig().getDouble("voice.hearing-range", 16);
        double talkRange = plugin.getConfig().getDouble("chat.range", 24);
        Set<UUID> hear = new HashSet<>();
        Set<UUID> chat = new HashSet<>();
        Location here = b.getLocation();
        for (Player player : b.getWorld().getPlayers()) {
            double distance = player.getLocation().distance(here);
            if (distance <= hearRange) {
                hear.add(player.getUniqueId());
            }
            if (distance <= talkRange) {
                chat.add(player.getUniqueId());
            }
        }
        hearing = Set.copyOf(hear);
        chatRange = Set.copyOf(chat);
    }

    private void moveTick(Mannequin b) {
        Location pos = b.getLocation();
        Location goal = null;
        double stop = 0;

        switch (mode) {
            case FOLLOW, COME -> {
                Player player = target == null ? null : Bukkit.getPlayer(target);
                if (player == null) {
                    mode = Mode.IDLE;
                    return;
                }
                goal = player.getLocation();
                stop = mode == Mode.FOLLOW ? plugin.getConfig().getDouble("character.follow-distance", 3) : 2.0;
            }
            case FIGHT -> {
                if (System.currentTimeMillis() > fightUntil) {
                    endFight();
                    return;
                }
                if (fightTarget == null || !fightTarget.isValid() || fightTarget.isDead()
                        || !fightTarget.getWorld().equals(b.getWorld())
                        || fightTarget.getLocation().distanceSquared(pos) > 20 * 20) {
                    fightTarget = findEnemy(b, 16);
                }
                if (fightTarget == null) {
                    endFight();
                    return;
                }
                goal = fightTarget.getLocation();
                stop = 1.6;
                attackTick(b);
            }
            case MINE -> {
                goal = mineTick(b);
                if (goal == null) {
                    return;
                }
                stop = 1.2;
            }
            default -> {
                return;
            }
        }

        if (!goal.getWorld().equals(pos.getWorld()) || pos.distanceSquared(goal) > 40 * 40) {
            b.teleport(goal);
            stuckTicks = 0;
            return;
        }

        Vector diff = goal.toVector().subtract(pos.toVector()).setY(0);
        double distance = diff.length();
        if (distance <= stop) {
            stuckTicks = 0;
            if (mode == Mode.COME) {
                mode = Mode.STAY;
            }
            return;
        }

        Vector dir = diff.normalize();
        double speed = distance > 7
                ? plugin.getConfig().getDouble("character.run-speed", 0.3)
                : plugin.getConfig().getDouble("character.walk-speed", 0.22);
        Vector velocity = dir.clone().multiply(speed);
        velocity.setY(b.getVelocity().getY());
        if (b.isOnGround() && blockedAhead(pos, dir)) {
            velocity.setY(0.42);
        }
        b.setVelocity(velocity);
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        b.setRotation(yaw, 0f);
        movedThisTick = true;

        if (lastProgress != null && lastProgress.getWorld().equals(pos.getWorld()) && lastProgress.distanceSquared(pos) < 0.0025) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastProgress = pos;
        if (stuckTicks > 60) {
            if (mode == Mode.MINE) {
                nextMineBlock(); // can't reach this one, try the next
            } else {
                b.teleport(goal); // stuck behind something: just pop over
            }
            stuckTicks = 0;
        }
    }

    private void attackTick(Mannequin b) {
        if (attackCooldown > 0) {
            attackCooldown--;
            return;
        }
        if (fightTarget != null && fightTarget.getLocation().distanceSquared(b.getLocation()) < 2.7 * 2.7) {
            b.lookAt(fightTarget.getX(), fightTarget.getY() + fightTarget.getHeight() * 0.8, fightTarget.getZ(), LookAnchor.EYES);
            b.swingMainHand();
            fightTarget.damage(plugin.getConfig().getDouble("actions.attack-damage", 5), b);
            attackCooldown = 12;
        }
    }

    private static boolean blockedAhead(Location pos, Vector dir) {
        Block ahead = pos.clone().add(dir.clone().multiply(0.8)).getBlock();
        Block aboveAhead = ahead.getRelative(0, 1, 0);
        return ahead.getType().isSolid() && !aboveAhead.getType().isSolid();
    }

    private void lookTick(Mannequin b) {
        if ((mode == Mode.FIGHT && fightTarget != null) || mode == Mode.MINE) {
            return; // attackTick / mineTick aim themselves
        }
        Player look = null;
        if (focus != null && System.currentTimeMillis() < focusUntil) {
            Player p = Bukkit.getPlayer(focus);
            if (p != null && p.getWorld().equals(b.getWorld()) && p.getLocation().distanceSquared(b.getLocation()) < 32 * 32) {
                look = p;
            }
        }
        if (look == null) {
            double best = 10 * 10;
            for (Player p : b.getWorld().getPlayers()) {
                double d = p.getLocation().distanceSquared(b.getLocation());
                if (d < best) {
                    best = d;
                    look = p;
                }
            }
        }
        if (look != null) {
            Location eye = look.getEyeLocation();
            b.lookAt(eye.getX(), eye.getY(), eye.getZ(), LookAnchor.EYES);
        }
    }

    private static LivingEntity findEnemy(Mannequin b, double radius) {
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity e : b.getNearbyEntities(radius, 6, radius)) {
            if (e instanceof Enemy && e instanceof LivingEntity living && !living.isDead()) {
                double d = living.getLocation().distanceSquared(b.getLocation());
                if (d < bestDistance) {
                    bestDistance = d;
                    best = living;
                }
            }
        }
        return best;
    }

    private static boolean anyPlayerNear(Location location, double radius) {
        for (Player p : location.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(location) < radius * radius) {
                return true;
            }
        }
        return false;
    }
}
