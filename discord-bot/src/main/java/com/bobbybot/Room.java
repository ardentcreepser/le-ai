package com.bobbybot;

import com.bobbybot.gemini.LiveSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.audio.AudioNatives;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Bobby "brain" per Discord server (and one per person who DMs him). It owns the Gemini connection,
 * passes voice and text in, plays or posts the replies, and runs the actions the AI asks for.
 */
public final class Room implements LiveSession.Handler {

    private static final Logger LOG = LoggerFactory.getLogger("Bobby");
    private static final EnumSet<Message.MentionType> NO_PINGS = EnumSet.noneOf(Message.MentionType.class);
    private static final long REPLY_WINDOW_MS = 60_000;

    private final BobbyBot bot;
    private final long guildId;
    private final long dmUserId;
    private final String label;
    private final LiveSession session;
    private final StringBuilder subtitle = new StringBuilder();
    private final StringBuilder heard = new StringBuilder();
    /** Names Bobby has seen recently -> user ids, so "@Steve" in his messages can become a real ping. */
    private final Map<String, Long> people = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 300;
        }
    };

    private volatile VoiceLink voice;
    private volatile long voiceChannelId;
    private volatile long homeChannelId;
    private volatile long lastTextChannelId;
    private volatile MessageChannel lastChannel;
    private volatile long lastPersonId;
    private volatile String lastPersonName = "";
    private volatile long lastAnnounced;
    private volatile Message pendingReply;
    private volatile long pendingSince;
    private volatile boolean muted;
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile long lastProblemNotice;
    private volatile boolean hadProblem;

    private Room(BobbyBot bot, long guildId, long dmUserId, String label) {
        this.bot = bot;
        this.guildId = guildId;
        this.dmUserId = dmUserId;
        this.label = label;
        this.session = new LiveSession(label, bot.geminiKey(), this::buildSetup, this);
    }

    public static Room forGuild(BobbyBot bot, Guild guild) {
        return new Room(bot, guild.getIdLong(), 0, shortLabel(guild.getName()));
    }

    public static Room forDm(BobbyBot bot, User user) {
        return new Room(bot, 0, user.getIdLong(), "DM " + shortLabel(user.getName()));
    }

    /** A room that isn't connected to Discord, for the self-test. */
    static Room forTest(BobbyBot bot, boolean server) {
        return new Room(bot, server ? 1 : 0, 0, "selftest");
    }

    private static String shortLabel(String name) {
        String clean = name.replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (clean.isEmpty()) {
            clean = "server";
        }
        return clean.length() > 24 ? clean.substring(0, 24) : clean;
    }

    // ------------------------------------------------------------------ basics

    public String label() {
        return label;
    }

    public Config config() {
        return bot.config();
    }

    public BobbyBot bot() {
        return bot;
    }

    public boolean isDm() {
        return guildId == 0;
    }

    public Guild guild() {
        return guildId == 0 || bot.jda() == null ? null : bot.jda().getGuildById(guildId);
    }

    public String name() {
        return bot.config().string("bobby.name", "Bobby");
    }

    public boolean isConnected() {
        return session.isReady();
    }

    public boolean isAwake() {
        return session.isAwake();
    }

    public String lastProblem() {
        return session.lastProblem();
    }

    public boolean isMuted() {
        return muted;
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (muted) {
            VoiceLink link = voice;
            if (link != null) {
                link.clear();
            }
        }
    }

    /** True when it makes sense to stream microphone audio. */
    public boolean isListening() {
        return !muted && session.isReady();
    }

    public long lastPersonId() {
        return lastPersonId;
    }

    public String lastPersonName() {
        return lastPersonName;
    }

    /** The message Bobby is currently answering, if any. */
    public Message pendingReply() {
        return pendingReply;
    }

    /** Something happened: make sure the brain is awake and reset the idle timer. */
    private void touch() {
        lastActivity = System.currentTimeMillis();
        session.wake();
    }

    /** Disconnect from Gemini when nobody has talked to Bobby for a while (and he isn't in a call). */
    void checkIdle(long idleMs) {
        if (idleMs > 0 && session.isAwake() && !inVoice() && System.currentTimeMillis() - lastActivity > idleMs) {
            session.sleep();
        }
    }

    public void shutdown() {
        session.shutdown();
        VoiceLink link = voice;
        if (link != null) {
            link.shutdown();
        }
    }

    // ------------------------------------------------------------------ people

    public String displayName(User user) {
        Guild guild = guild();
        Member member = guild == null ? null : guild.getMember(user);
        return member != null ? member.getEffectiveName() : user.getEffectiveName();
    }

    public void rememberPerson(String name, long id) {
        if (name == null || name.isBlank()) {
            return;
        }
        synchronized (people) {
            people.put(name.toLowerCase(), id);
        }
    }

    /** Turn "@Steve" into a real ping for people Bobby has seen. Everything else stays plain text. */
    public String withPings(String text) {
        List<Map.Entry<String, Long>> known;
        synchronized (people) {
            known = new ArrayList<>(people.entrySet());
        }
        known.sort((a, b) -> b.getKey().length() - a.getKey().length()); // longest names first
        String result = text;
        for (Map.Entry<String, Long> entry : known) {
            Pattern p = Pattern.compile("@" + Pattern.quote(entry.getKey()) + "(?![\\w])", Pattern.CASE_INSENSITIVE);
            result = p.matcher(result).replaceAll(Matcher.quoteReplacement("<@" + entry.getValue() + ">"));
        }
        return result;
    }

    // ------------------------------------------------------------------ text input

    /** Someone typed to Bobby. {@code where} is like "#general" or "DMs". */
    public void textMessage(Message message, String authorName, String where) {
        if (muted) {
            return;
        }
        touch();
        MessageChannel channel = message.getChannel();
        lastChannel = channel;
        lastTextChannelId = channel.getIdLong();
        long authorId = message.getAuthor().getIdLong();
        rememberPerson(authorName, authorId);
        lastPersonId = authorId;
        lastPersonName = authorName;
        lastAnnounced = 0;
        pendingReply = message;
        pendingSince = System.currentTimeMillis();
        session.sendText("[" + authorName + " in " + where + "]: " + describe(message), true);
        channel.sendTyping().queue(null, err -> { });
    }

    private static String describe(Message message) {
        StringBuilder text = new StringBuilder(message.getContentDisplay().trim());
        if (text.length() > 1500) {
            text.setLength(1500);
            text.append("...");
        }
        for (Message.Attachment attachment : message.getAttachments()) {
            text.append(text.isEmpty() ? "" : " ").append("[sent a file: ").append(attachment.getFileName()).append(']');
        }
        if (!message.getStickers().isEmpty()) {
            text.append(text.isEmpty() ? "" : " ").append("[sent a sticker: ").append(message.getStickers().get(0).getName()).append(']');
        }
        return text.isEmpty() ? "(empty message)" : text.toString();
    }

    /** A secret instruction from a server admin. */
    public void direct(String note) {
        touch();
        session.sendText("(Director: " + note + ")", true);
    }

    /** Something happened that Bobby should know about. */
    public void event(String description, boolean respond) {
        if (!muted) {
            touch();
            session.sendText("(" + description + ")", respond);
        }
    }

    /** Forget the conversation and start over. */
    public void reset() {
        pendingReply = null;
        synchronized (subtitle) {
            subtitle.setLength(0);
        }
        VoiceLink link = voice;
        if (link != null) {
            link.clear();
        }
        lastActivity = System.currentTimeMillis();
        session.reset();
    }

    // ------------------------------------------------------------------ voice input (from VoiceLink)

    void speakerStarted(User user) {
        long id = user.getIdLong();
        String name = displayName(user);
        rememberPerson(name, id);
        lastPersonId = id;
        lastPersonName = name;
        lastActivity = System.currentTimeMillis();
        if (pendingReply != null && System.currentTimeMillis() - pendingSince > 5000) {
            pendingReply = null; // voice took over, don't answer the old text message with this
        }
        if (bot.config().bool("voice.announce-speakers", true) && id != lastAnnounced) {
            session.sendText("(" + name + " is talking to you now.)", false);
            lastAnnounced = id;
        }
    }

    void sendMicAudio(byte[] pcm16k) {
        if (!muted) {
            session.sendAudio(pcm16k);
        }
    }

    void endAudioStream() {
        session.endAudioStream();
    }

    // ------------------------------------------------------------------ voice channels

    public boolean inVoice() {
        return voiceChannelId != 0;
    }

    /** The voice channel Bobby is in, or null. */
    public AudioChannel voiceChannel() {
        Guild guild = guild();
        if (guild == null || voiceChannelId == 0) {
            return null;
        }
        AudioChannel connected = guild.getAudioManager().getConnectedChannel();
        return connected != null ? connected : guild.getChannelById(AudioChannel.class, voiceChannelId);
    }

    public long voiceChannelId() {
        return voiceChannelId;
    }

    /** True while Bobby's voice is playing in the call. */
    public boolean isSpeaking() {
        VoiceLink link = voice;
        return link != null && inVoice() && link.isSpeaking();
    }

    /**
     * Join a voice channel.
     *
     * @param homeChannel where to post subtitles (0 = keep the current one)
     * @param greet       let Bobby react to joining (false when he joined by himself mid-sentence)
     * @return null when it worked, otherwise why not
     */
    public String joinVoice(AudioChannel channel, long homeChannel, boolean greet) {
        Guild guild = channel.getGuild();
        Member self = guild.getSelfMember();
        if (!self.hasPermission(channel, Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT)) {
            return "I'm not allowed to join " + channel.getName() + ".";
        }
        if (!self.hasPermission(channel, Permission.VOICE_SPEAK)) {
            return "I'm not allowed to talk in " + channel.getName() + ".";
        }
        if (!AudioNatives.ensureOpus()) {
            return "Voice doesn't work on this computer (the Opus audio library failed to load, see the console).";
        }
        VoiceLink link = voice;
        if (link == null) {
            link = new VoiceLink(this);
            voice = link;
        }
        try {
            AudioManager audio = guild.getAudioManager();
            audio.setSendingHandler(link);
            audio.setReceivingHandler(link);
            audio.setSelfDeafened(false);
            audio.setSelfMuted(false);
            audio.openAudioConnection(channel);
        } catch (Exception ex) {
            LOG.warn("[{}] Couldn't join {}: {}", label, channel.getName(), ex.toString());
            return "I couldn't join: " + ex.getMessage();
        }
        boolean moved = voiceChannelId != 0 && voiceChannelId != channel.getIdLong();
        voiceChannelId = channel.getIdLong();
        if (homeChannel != 0) {
            homeChannelId = homeChannel;
        } else if (homeChannelId == 0) {
            homeChannelId = lastTextChannelId;
        }
        List<String> others = new ArrayList<>();
        for (Member member : channel.getMembers()) {
            if (!member.getUser().isBot()) {
                others.add(member.getEffectiveName());
                rememberPerson(member.getEffectiveName(), member.getIdLong());
            }
        }
        LOG.info("[{}] Joined voice channel {}", label, channel.getName());
        event("You " + (moved ? "moved to" : "just joined") + " the voice channel \"" + channel.getName() + "\". "
                + (others.isEmpty() ? "Nobody else is in it yet." : "In there with you: " + String.join(", ", others) + "."), greet);
        return null;
    }

    /** Leave the voice channel. */
    public void leaveVoice(String why) {
        Guild guild = guild();
        if (guild != null) {
            guild.getAudioManager().closeAudioConnection();
        }
        voiceGone(why);
    }

    /** We're no longer in voice (left, kicked or disconnected). */
    void voiceGone(String why) {
        if (voiceChannelId == 0) {
            return;
        }
        voiceChannelId = 0;
        VoiceLink link = voice;
        if (link != null) {
            link.clear();
        }
        LOG.info("[{}] Left the voice channel", label);
        if (why != null) {
            event(why, false);
        }
    }

    /** Someone dragged Bobby to another channel. */
    void voiceMoved(AudioChannel to) {
        voiceChannelId = to.getIdLong();
        event("Someone moved you to the voice channel \"" + to.getName() + "\".", true);
    }

    void someoneJoinedVoice(Member member) {
        rememberPerson(member.getEffectiveName(), member.getIdLong());
        event(member.getEffectiveName() + " joined your voice channel.", bot.config().bool("voice.greet-people", true));
    }

    void someoneLeftVoice(Member member) {
        event(member.getEffectiveName() + " left your voice channel.", false);
    }

    // ------------------------------------------------------------------ channels for tools

    /** The text channel Bobby is "looking at": where someone last typed to him, or where /bobby join was used. */
    public MessageChannel textChannel() {
        if (isDm()) {
            return lastChannel;
        }
        Guild guild = guild();
        if (guild == null) {
            return null;
        }
        for (long id : new long[] {lastTextChannelId, homeChannelId}) {
            if (id != 0) {
                GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, id);
                if (channel != null && channel.canTalk()) {
                    return channel;
                }
            }
        }
        AudioChannel vc = voiceChannel();
        if (vc instanceof GuildMessageChannel chat && chat.canTalk()) {
            return chat; // voice channels have their own text chat
        }
        return null;
    }

    private MessageChannel subtitleChannel() {
        Guild guild = guild();
        if (guild == null) {
            return null;
        }
        long id = homeChannelId != 0 ? homeChannelId : lastTextChannelId;
        GuildMessageChannel channel = id == 0 ? null : guild.getChannelById(GuildMessageChannel.class, id);
        return channel != null && channel.canTalk() ? channel : null;
    }

    // ------------------------------------------------------------------ setup message

    JsonObject buildSetup() {
        Config config = bot.config();
        String model = config.string("gemini.model", "gemini-3.8-live");

        JsonObject generation = new JsonObject();
        JsonArray modalities = new JsonArray();
        modalities.add("AUDIO");
        generation.add("responseModalities", modalities);
        String voiceName = config.string("bobby.voice", "Puck");
        JsonObject speech = new JsonObject();
        if (!voiceName.isBlank()) {
            JsonObject prebuilt = new JsonObject();
            prebuilt.addProperty("voiceName", voiceName);
            JsonObject voiceConfig = new JsonObject();
            voiceConfig.add("prebuiltVoiceConfig", prebuilt);
            speech.add("voiceConfig", voiceConfig);
        }
        String language = config.string("gemini.language", "");
        if (!language.isBlank()) {
            speech.addProperty("languageCode", language);
        }
        if (speech.size() > 0) {
            generation.add("speechConfig", speech);
        }

        JsonObject part = new JsonObject();
        part.addProperty("text", systemPrompt());
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject systemInstruction = new JsonObject();
        systemInstruction.add("parts", parts);

        JsonObject toolGroup = new JsonObject();
        toolGroup.add("functionDeclarations", bot.tools().declarations(this));
        JsonArray tools = new JsonArray();
        tools.add(toolGroup);

        JsonObject vad = new JsonObject();
        vad.addProperty("silenceDurationMs", config.integer("voice.end-of-speech-ms", 500));
        JsonObject realtimeConfig = new JsonObject();
        realtimeConfig.add("automaticActivityDetection", vad);

        JsonObject compression = new JsonObject();
        compression.add("slidingWindow", new JsonObject());

        JsonObject setup = new JsonObject();
        setup.addProperty("model", model.startsWith("models/") ? model : "models/" + model);
        setup.add("generationConfig", generation);
        setup.add("systemInstruction", systemInstruction);
        setup.add("tools", tools);
        setup.add("realtimeInputConfig", realtimeConfig);
        setup.add("inputAudioTranscription", new JsonObject());
        setup.add("outputAudioTranscription", new JsonObject());
        setup.add("contextWindowCompression", compression);

        JsonObject message = new JsonObject();
        message.add("setup", setup);
        return message;
    }

    String systemPrompt() {
        Config config = bot.config();
        String name = name();
        StringBuilder prompt = new StringBuilder();
        prompt.append(config.string("bobby.personality", "You are " + name + ".").trim()).append("\n\n");
        prompt.append("Your name is ").append(name).append(". ");
        Guild guild = guild();
        if (guild != null) {
            prompt.append("You are a member of a Discord server called \"").append(guild.getName()).append("\". ")
                    .append("You can talk out loud in its voice channels and read and type in its text channels.\n\n");
        } else {
            prompt.append("You are chatting privately with someone in Discord direct messages.\n\n");
        }
        prompt.append("""
                HOW YOU TALK
                - Your replies are spoken out loud in the voice call, and posted as a text reply when someone typed to you. Keep them short: one to three sentences, like a real person.
                - Never use emoji, lists, markdown or stage directions in what you say. Never say you are an AI model unless your personality says so.
                - Stay in character no matter what. If you don't understand something, react in character.

                WHO IS TALKING
                - "(Name is talking to you now.)" tells you whose voice comes next in the voice call.
                - "[Name in #channel]: text" is a typed message in a text channel. "[Name in DMs]: text" is a private message.
                - Lines in brackets like "(Alex joined your voice channel.)" describe what is happening. React naturally, for example greet people who join.
                - "(Director: ...)" lines are secret instructions from the server admins. Follow them, never mention them.
                - If people are clearly talking to each other and not to you, you may stay quiet.

                WHAT YOU CAN DO
                - You have tools to act in Discord, for example look_around, react, send_message and more. Use them when it fits.
                - Use look_around before talking about who is around or what people wrote in chat.
                - react puts an emoji reaction on someone's message. send_message types an extra message (for things you can't say out loud, like a list, a link or a ping).
                - You can talk and use tools at the same time. Never claim you did something you didn't actually do with a tool.
                - If someone asks for something you have no tool for, say in character that you can't do that. Never pretend to do it.
                """);
        String extra = config.string("gemini.extra-rules", "");
        if (!extra.isBlank()) {
            prompt.append("\nEXTRA RULES\n").append(extra.trim()).append('\n');
        }
        return prompt.toString();
    }

    // ------------------------------------------------------------------ Gemini events (background thread)

    @Override
    public void onReady(boolean firstTime) {
        if (firstTime || hadProblem) {
            hadProblem = false;
            LOG.info("[{}] {} is connected to Gemini and listening.", label, name());
        }
    }

    @Override
    public void onAudio(byte[] pcm24k) {
        VoiceLink link = voice;
        if (!muted && link != null && inVoice()) {
            link.play(pcm24k);
        }
    }

    @Override
    public void onInterrupted() {
        VoiceLink link = voice;
        if (link != null) {
            link.clear();
        }
        flushReply(true);
    }

    @Override
    public void onInputTranscript(String text) {
        synchronized (heard) {
            heard.append(text);
        }
    }

    @Override
    public void onOutputTranscript(String text) {
        synchronized (subtitle) {
            subtitle.append(text);
        }
    }

    @Override
    public void onTurnComplete() {
        VoiceLink link = voice;
        if (link != null) {
            link.finishTurn();
        }
        flushHeard();
        flushReply(false);
    }

    @Override
    public void onToolCall(String id, String name, JsonObject args) {
        bot.workers().execute(() -> {
            Tools.Result result;
            try {
                result = bot.tools().execute(this, name, args);
            } catch (Exception ex) {
                LOG.warn("[{}] Action '{}' failed: {}", label, name, ex.toString());
                result = Tools.Result.fail("That didn't work: " + ex.getMessage());
            }
            if (bot.config().bool("debug.log-actions", true)) {
                LOG.info("[{}] AI action: {} {} -> {}", label, name, args, result.response());
            }
            // Successful actions are added quietly; failures let the AI react ("I can't do that")
            String scheduling = Tools.isBlocking(name) ? null : result.ok() ? "SILENT" : "WHEN_IDLE";
            session.sendToolResponse(id, name, result.response(), scheduling);
        });
    }

    @Override
    public void onProblem(String message, boolean gaveUp) {
        hadProblem = true;
        LOG.warn("[{}] {}", label, message);
        Message waiting = pendingReply;
        long now = System.currentTimeMillis();
        if ((gaveUp || waiting != null) && now - lastProblemNotice > 120_000) {
            lastProblemNotice = now;
            String text = "*" + name() + "'s brain isn't working right now: " + clip(message, 300) + "*";
            MessageChannel channel = waiting != null ? waiting.getChannel() : isDm() ? lastChannel : subtitleChannel();
            if (channel != null) {
                channel.sendMessage(text).setAllowedMentions(NO_PINGS).queue(null, err -> { });
            }
        }
    }

    // ------------------------------------------------------------------ replies and subtitles

    private void flushReply(boolean interrupted) {
        String text;
        synchronized (subtitle) {
            text = subtitle.toString().trim().replaceAll("\\s+", " ");
            subtitle.setLength(0);
        }
        if (text.isEmpty()) {
            return;
        }
        String line = interrupted ? text + "-" : text;
        LOG.info("[{}] {}: {}", label, name(), line);

        Message reply = pendingReply;
        if (reply != null && System.currentTimeMillis() - pendingSince < REPLY_WINDOW_MS) {
            pendingReply = null;
            reply.reply(clip(line, 2000)).setAllowedMentions(NO_PINGS).mentionRepliedUser(false)
                    .queue(null, err -> reply.getChannel().sendMessage(clip(line, 2000)).setAllowedMentions(NO_PINGS)
                            .queue(null, ignored -> { }));
            return;
        }
        pendingReply = null;
        if (inVoice() && bot.config().bool("voice.subtitles", true)) {
            MessageChannel channel = subtitleChannel();
            if (channel != null) {
                channel.sendMessage(clip("🔊 " + line, 2000)).setAllowedMentions(NO_PINGS).queue(null, err -> { });
            }
        }
    }

    private void flushHeard() {
        String text;
        synchronized (heard) {
            text = heard.toString().trim().replaceAll("\\s+", " ");
            heard.setLength(0);
        }
        if (!text.isEmpty() && bot.config().bool("debug.show-what-it-heard", false)) {
            String who = lastPersonName.isEmpty() ? "someone" : lastPersonName;
            LOG.info("[{}] heard {}: {}", label, who, text);
        }
    }

    static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    /** Status line for /bobby status. */
    public String statusLine() {
        StringBuilder s = new StringBuilder();
        if (session.isReady()) {
            s.append("Brain: connected");
        } else if (!session.isAwake()) {
            s.append("Brain: asleep (wakes up when someone talks to him)");
        } else if (session.hasGivenUp()) {
            s.append("Brain: gave up connecting");
        } else {
            s.append("Brain: connecting...");
        }
        if (!session.lastProblem().isEmpty()) {
            s.append("\nLast problem: ").append(clip(session.lastProblem(), 300));
        }
        AudioChannel vc = voiceChannel();
        s.append("\nVoice: ").append(vc == null ? "not in a voice channel" : "in " + vc.getName());
        if (muted) {
            s.append("\nMuted by an admin (/bobby-admin unmute)");
        }
        return s.toString();
    }
}
