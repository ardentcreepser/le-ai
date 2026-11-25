package com.bobbybot;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.session.ShutdownEvent;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.CloseCode;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bobby, the AI Discord bot. Run with: java -jar BobbyBot.jar
 */
public final class BobbyBot {

    private static final Logger LOG = LoggerFactory.getLogger("BobbyBot");

    /** What Bobby needs in a server. These end up in the invite link. */
    static final List<Permission> PERMISSIONS = List.of(
            Permission.VIEW_CHANNEL,
            Permission.MESSAGE_SEND,
            Permission.MESSAGE_SEND_IN_THREADS,
            Permission.MESSAGE_HISTORY,
            Permission.MESSAGE_ADD_REACTION,
            Permission.MESSAGE_SEND_POLLS,
            Permission.VOICE_CONNECT,
            Permission.VOICE_SPEAK,
            Permission.VOICE_USE_SOUNDBOARD,
            Permission.NICKNAME_CHANGE);

    private final Config config;
    private final String geminiKey;
    private final Tools tools;
    private final Map<Long, Room> guildRooms = new ConcurrentHashMap<>();
    private final Map<Long, Room> dmRooms = new ConcurrentHashMap<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> daemon(r, "Bobby-Action"));
    private final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "Bobby-Idle"));
    private volatile JDA jda;

    BobbyBot(Config config, String geminiKey) {
        this.config = config;
        this.geminiKey = geminiKey;
        this.tools = new Tools(this);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ------------------------------------------------------------------ startup

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--selftest")) {
                System.exit(SelfTest.run());
            }
        }

        System.out.println();
        System.out.println("  ____        _     _           ____        _   ");
        System.out.println(" | __ )  ___ | |__ | |__  _   _| __ )  ___ | |_ ");
        System.out.println(" |  _ \\ / _ \\| '_ \\| '_ \\| | | |  _ \\ / _ \\| __|");
        System.out.println(" | |_) | (_) | |_) | |_) | |_| | |_) | (_) | |_ ");
        System.out.println(" |____/ \\___/|_.__/|_.__/ \\__, |____/ \\___/ \\__|");
        System.out.println("                          |___/                 ");
        System.out.println();

        Path file = Path.of("config.yml").toAbsolutePath();
        if (Config.writeDefaultIfMissing(file)) {
            System.out.println("First start! I made a settings file for you:");
            System.out.println("  " + file);
            System.out.println();
            System.out.println("Open it, paste your Discord bot token and your Gemini API key, save it,");
            System.out.println("then start the bot again.");
            System.exit(0);
        }

        Config config = Config.load(file);
        String token = config.discordToken();
        String key = config.geminiKey();
        if (token == null) {
            System.out.println("Missing Discord token: paste it into config.yml under discord > token.");
            System.out.println("Get it from https://discord.com/developers/applications > your app > Bot > Reset Token.");
            System.exit(1);
        }
        if (key == null) {
            System.out.println("Missing Gemini API key: paste it into config.yml under gemini > api-key.");
            System.out.println("Get a free one at https://aistudio.google.com/apikey");
            System.exit(1);
        }

        BobbyBot bot = new BobbyBot(config, key);
        bot.start(token);
    }

    private void start(String token) throws InterruptedException {
        AudioModuleConfig audio = new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory());
        String status = config.string("bobby.status", "").trim();

        JDABuilder builder = JDABuilder.createLight(token, EnumSet.of(
                        GatewayIntent.GUILD_MESSAGES,
                        GatewayIntent.MESSAGE_CONTENT,
                        GatewayIntent.GUILD_VOICE_STATES,
                        GatewayIntent.GUILD_EXPRESSIONS,
                        GatewayIntent.DIRECT_MESSAGES))
                .enableCache(CacheFlag.VOICE_STATE, CacheFlag.SOUNDBOARD_SOUNDS)
                .setMemberCachePolicy(MemberCachePolicy.VOICE)
                .setAudioModuleConfig(audio)
                .addEventListeners(new Listener(this), new StartupProblems());
        if (!status.isEmpty()) {
            builder.setActivity(Activity.playing(status));
        }

        try {
            jda = builder.build();
        } catch (InvalidTokenException | IllegalArgumentException ex) {
            System.out.println("Discord didn't accept the bot token in config.yml. Copy it again from the Developer Portal");
            System.out.println("(Bot > Reset Token) and paste it between the quotes.");
            System.exit(1);
            return;
        }
        try {
            jda.awaitReady();
        } catch (IllegalStateException ex) {
            // StartupProblems already explained what went wrong
            System.exit(1);
            return;
        }

        jda.setRequiredScopes("applications.commands");
        String invite = jda.getInviteUrl(PERMISSIONS);
        LOG.info("Logged in as {}. {} is in {} server(s).", jda.getSelfUser().getName(), config.string("bobby.name", "Bobby"),
                jda.getGuilds().size());
        LOG.info("Invite link (open it to add Bobby to a server):");
        LOG.info(invite);
        if (jda.getGuilds().isEmpty()) {
            LOG.info("Bobby isn't in any server yet. Open the invite link above first.");
        }

        jda.retrieveDefaultSoundboardSounds().queue(tools::setDefaultSounds,
                err -> LOG.warn("Couldn't load Discord's default soundboard sounds: {}", err.toString()));

        long idleMs = TimeUnit.MINUTES.toMillis(Math.max(0, config.integer("gemini.idle-minutes", 10)));
        housekeeping.scheduleAtFixedRate(() -> {
            try {
                guildRooms.values().forEach(r -> r.checkIdle(idleMs));
                dmRooms.values().forEach(r -> r.checkIdle(idleMs));
            } catch (Exception ex) {
                LOG.warn("Idle check failed: {}", ex.toString());
            }
        }, 30, 30, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "Bobby-Shutdown"));
        LOG.info("Ready! Type /bobby join in your server while you're in a voice channel, or @mention {} in chat.",
                config.string("bobby.name", "Bobby"));
        LOG.info("Close this window (or press Ctrl+C) to stop the bot.");
    }

    private void shutdown() {
        LOG.info("Shutting down...");
        guildRooms.values().forEach(Room::shutdown);
        dmRooms.values().forEach(Room::shutdown);
        housekeeping.shutdownNow();
        workers.shutdownNow();
        JDA current = jda;
        if (current != null) {
            current.shutdown();
            try {
                current.awaitShutdown(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Explains the two most common setup mistakes in plain words. */
    private static final class StartupProblems extends ListenerAdapter {
        @Override
        public void onShutdown(ShutdownEvent event) {
            CloseCode code = event.getCloseCode();
            if (code == CloseCode.DISALLOWED_INTENTS) {
                LOG.error("Discord refused to connect: the MESSAGE CONTENT INTENT is switched off.");
                LOG.error("Fix: Developer Portal > your app > Bot > turn on \"Message Content Intent\" > Save. Then start again.");
            } else if (code == CloseCode.AUTHENTICATION_FAILED) {
                LOG.error("Discord didn't accept the bot token. Copy it again (Bot > Reset Token) into config.yml.");
            }
        }
    }

    // ------------------------------------------------------------------ shared stuff

    void registerCommands(Guild guild) {
        guild.updateCommands().addCommands(SlashCommands.definitions(config.string("bobby.name", "Bobby"))).queue(
                ok -> LOG.info("Slash commands ready in {}", guild.getName()),
                err -> LOG.warn("Couldn't add /bobby commands in {}: {}. Re-invite the bot with the link above.",
                        guild.getName(), err.getMessage()));
    }

    public Room room(Guild guild) {
        return guildRooms.computeIfAbsent(guild.getIdLong(), id -> Room.forGuild(this, guild));
    }

    public Room existingRoom(Guild guild) {
        return guild == null ? null : guildRooms.get(guild.getIdLong());
    }

    public Room dmRoom(User user) {
        return dmRooms.computeIfAbsent(user.getIdLong(), id -> Room.forDm(this, user));
    }

    public Config config() {
        return config;
    }

    public JDA jda() {
        return jda;
    }

    public Tools tools() {
        return tools;
    }

    public ExecutorService workers() {
        return workers;
    }

    String geminiKey() {
        return geminiKey;
    }
}
