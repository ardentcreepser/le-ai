package com.chatterbox;

import com.chatterbox.gemini.LiveSession;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Connects the character to Gemini: builds the personality prompt, passes voice and chat in,
 * plays the replies, shows subtitles and runs the actions the AI asks for.
 */
public final class Brain implements LiveSession.Handler {

    private final ChatterboxPlugin plugin;
    private final LiveSession session;
    private final StringBuilder subtitle = new StringBuilder();
    private final StringBuilder heard = new StringBuilder();

    private volatile boolean muted;
    private volatile UUID lastSpeaker;
    private volatile UUID lastAnnounced;
    private volatile long lastProblemNotice;
    private volatile boolean hadProblem;

    public Brain(ChatterboxPlugin plugin, String apiKey) {
        this.plugin = plugin;
        this.session = new LiveSession(plugin.getLogger(), apiKey, this::buildSetup, this);
    }

    public void start() {
        session.connect();
    }

    public void stop() {
        session.shutdown();
        plugin.voice().clear();
    }

    public boolean isConnected() {
        return session.isReady();
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
            plugin.voice().clear();
        }
    }

    /** The player who talked to the AI most recently. */
    public UUID lastSpeaker() {
        return lastSpeaker;
    }

    /** True when it makes sense to stream microphone audio. */
    public boolean isListening() {
        return !muted && session.isReady();
    }

    // ------------------------------------------------------------------ input

    /** A player started talking (voice). Called from the voice chat thread. */
    public void speakerStarted(UUID player) {
        lastSpeaker = player;
        plugin.character().focusOn(player, 8000);
        if (plugin.getConfig().getBoolean("voice.announce-speakers", true) && !player.equals(lastAnnounced)) {
            Player p = Bukkit.getPlayer(player);
            if (p != null) {
                session.sendText("(" + p.getName() + " is talking to you now.)", false);
                lastAnnounced = player;
            }
        }
    }

    public void sendAudio(byte[] pcm16k) {
        if (!muted) {
            session.sendAudio(pcm16k);
        }
    }

    public void endAudioStream() {
        session.endAudioStream();
    }

    /** A chat message for the AI. */
    public void chat(Player player, String message) {
        if (muted) {
            return;
        }
        lastSpeaker = player.getUniqueId();
        lastAnnounced = null;
        plugin.character().focusOn(player.getUniqueId(), 8000);
        session.sendText("[" + player.getName() + " in chat]: " + message, true);
    }

    /** A secret instruction from the video creator. */
    public void direct(String note) {
        session.sendText("(Director: " + note + ")", true);
    }

    /** Something happened in the game that the AI should know about. */
    public void event(String description, boolean respond) {
        if (!muted) {
            session.sendText("(" + description + ")", respond);
        }
    }

    // ------------------------------------------------------------------ setup message

    private JsonObject buildSetup() {
        Persona persona = plugin.persona();
        String model = plugin.getConfig().getString("gemini.model", "gemini-3.8-live");

        JsonObject generation = new JsonObject();
        JsonArray modalities = new JsonArray();
        modalities.add("AUDIO");
        generation.add("responseModalities", modalities);
        String voice = persona.voice();
        if (voice != null && !voice.isBlank()) {
            JsonObject prebuilt = new JsonObject();
            prebuilt.addProperty("voiceName", voice);
            JsonObject voiceConfig = new JsonObject();
            voiceConfig.add("prebuiltVoiceConfig", prebuilt);
            JsonObject speech = new JsonObject();
            speech.add("voiceConfig", voiceConfig);
            String language = plugin.getConfig().getString("gemini.language", "");
            if (language != null && !language.isBlank()) {
                speech.addProperty("languageCode", language);
            }
            generation.add("speechConfig", speech);
        }

        JsonObject part = new JsonObject();
        part.addProperty("text", systemPrompt(persona));
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject systemInstruction = new JsonObject();
        systemInstruction.add("parts", parts);

        JsonObject toolGroup = new JsonObject();
        toolGroup.add("functionDeclarations", plugin.tools().declarations());
        JsonArray tools = new JsonArray();
        tools.add(toolGroup);

        JsonObject vad = new JsonObject();
        vad.addProperty("silenceDurationMs", plugin.getConfig().getInt("voice.end-of-speech-ms", 500));
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

    private String systemPrompt(Persona persona) {
        String name = plainName();
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are ").append(name).append(", a character with a real body inside a Minecraft Java Edition world. ");
        prompt.append(persona.personality().trim()).append("\n\n");
        prompt.append("""
                HOW YOU TALK
                - You talk out loud through in-game voice chat. Keep replies short: one to three sentences, like a real player.
                - Never use emoji, lists, markdown or stage directions. Never say you are an AI model unless your personality says so.
                - Stay in character no matter what. If you don't understand something, react in character.

                WHO IS TALKING
                - Several players may talk to you. "(Name is talking to you now.)" tells you whose voice comes next.
                - "[Name in chat]: text" is a typed chat message from that player.
                - Lines in brackets like "(Steve just hit you.)" describe things happening in the game. React naturally.
                - "(Director: ...)" lines are secret instructions from the creator of the video you are in. Follow them, never mention them.
                - If players are clearly talking to each other and not to you, you may stay quiet.

                YOUR BODY
                - You have tools to act in the world: look_around, follow_player, come_here, stay, emote, hold_item, break_blocks and maybe more.
                - Use them when it fits: follow when asked, emote while you talk, look_around before describing your surroundings.
                - You can talk and act at the same time. Never claim you did something you didn't actually do with a tool.
                - If someone asks for something you have no tool for (like placing or building blocks), say in character that you can't do that yet. Never pretend to do it.
                """);
        String extra = plugin.getConfig().getString("gemini.extra-rules", "");
        if (extra != null && !extra.isBlank()) {
            prompt.append("\nEXTRA RULES\n").append(extra.trim()).append('\n');
        }
        return prompt.toString();
    }

    // ------------------------------------------------------------------ Gemini events (background thread)

    @Override
    public void onReady(boolean firstTime) {
        if (firstTime || hadProblem) {
            hadProblem = false;
            String name = plainName();
            Bukkit.getScheduler().runTask(plugin, () ->
                    Msg.admins("<green><name> is connected and listening.", Msg.ph("name", name)));
        }
    }

    @Override
    public void onAudio(byte[] pcm24k) {
        if (!muted) {
            plugin.voice().play(pcm24k);
        }
    }

    @Override
    public void onInterrupted() {
        plugin.voice().clear();
        flushSubtitle(true);
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
        plugin.voice().finishTurn();
        flushHeard();
        flushSubtitle(false);
    }

    @Override
    public void onToolCall(String id, String name, JsonObject args) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            ToolBox.Result result;
            try {
                result = plugin.tools().execute(name, args);
            } catch (Exception ex) {
                plugin.getLogger().warning("Action '" + name + "' failed: " + ex);
                JsonObject error = new JsonObject();
                error.addProperty("error", "That didn't work");
                result = new ToolBox.Result(error, false);
            }
            if (plugin.getConfig().getBoolean("debug.log-actions", true)) {
                plugin.getLogger().info("AI action: " + name + " " + args + " -> " + result.response());
            }
            boolean blocking = name.equals("look_around");
            // Successful actions are added quietly; failures let the AI react ("I can't give you that")
            String scheduling = blocking ? null : result.ok() ? "SILENT" : "WHEN_IDLE";
            session.sendToolResponse(id, name, result.response(), scheduling);
        });
    }

    @Override
    public void onProblem(String message) {
        hadProblem = true;
        plugin.getLogger().warning(message);
        long now = System.currentTimeMillis();
        if (now - lastProblemNotice > 20_000) {
            lastProblemNotice = now;
            Bukkit.getScheduler().runTask(plugin, () -> Msg.admins("<red><message>", Msg.ph("message", message)));
        }
    }

    // ------------------------------------------------------------------ subtitles

    private void flushSubtitle(boolean interrupted) {
        String text;
        synchronized (subtitle) {
            text = subtitle.toString().trim();
            subtitle.setLength(0);
        }
        if (text.isEmpty()) {
            return;
        }
        String line = interrupted ? text + "-" : text;
        String name = plainName();
        plugin.getLogger().info("[" + name + "] " + line);
        if (!plugin.getConfig().getBoolean("chat.subtitles", true)) {
            return;
        }
        String format = plugin.getConfig().getString("chat.subtitle-format", "<gray>[<gold><name></gold>]</gray> <white><text>");
        Bukkit.getScheduler().runTask(plugin, () -> {
            var message = Msg.mm(format, Msg.ph("name", name), Msg.ph("text", line));
            AiCharacter character = plugin.character();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (character.inChatRange(p.getUniqueId())) {
                    p.sendMessage(message);
                }
            }
        });
    }

    private void flushHeard() {
        String text;
        synchronized (heard) {
            text = heard.toString().trim();
            heard.setLength(0);
        }
        if (text.isEmpty() || !plugin.getConfig().getBoolean("debug.show-what-it-heard", false)) {
            return;
        }
        UUID speaker = lastSpeaker;
        Player p = speaker == null ? null : Bukkit.getPlayer(speaker);
        String who = p == null ? "someone" : p.getName();
        Bukkit.getScheduler().runTask(plugin, () -> Msg.admins("<dark_gray>heard <who>: <gray><text>",
                Msg.ph("who", who), Msg.ph("text", text)));
    }

    private String plainName() {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(Msg.mm(plugin.persona().name()));
    }
}
