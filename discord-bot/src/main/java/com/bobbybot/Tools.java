package com.bobbybot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.SoundboardSound;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.messages.MessagePollBuilder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The things Bobby can do in Discord. Gemini calls these as "functions" while it talks.
 * Every action can be switched off in config.yml under "actions".
 */
public final class Tools {

    /** What happened, sent back to the AI. */
    public record Result(JsonObject response, boolean ok) {
        static Result ok(String message) {
            JsonObject r = new JsonObject();
            r.addProperty("result", message);
            return new Result(r, true);
        }

        static Result fail(String error) {
            JsonObject r = new JsonObject();
            r.addProperty("error", error);
            return new Result(r, false);
        }
    }

    /** Tools the AI waits for, because it needs the answer to keep talking. */
    private static final Set<String> BLOCKING = Set.of("look_around", "random");
    private static final int MESSAGES_PER_MINUTE = 5;

    private final BobbyBot bot;
    private final Map<String, Deque<Long>> sent = new ConcurrentHashMap<>();
    private volatile List<SoundboardSound> defaultSounds = List.of();

    public Tools(BobbyBot bot) {
        this.bot = bot;
    }

    public static boolean isBlocking(String name) {
        return BLOCKING.contains(name);
    }

    public void setDefaultSounds(List<SoundboardSound> sounds) {
        defaultSounds = sounds == null ? List.of() : List.copyOf(sounds);
    }

    private Config cfg() {
        return bot.config();
    }

    // ------------------------------------------------------------------ declarations (what the AI sees)

    public JsonArray declarations(Room room) {
        JsonArray list = new JsonArray();
        boolean server = !room.isDm();
        list.add(fn("look_around", "See what's going on right now: who is in the voice channels, and the latest "
                + "messages in the text channel you're looking at. Use it before describing who is around or what "
                + "people wrote.", true));
        list.add(fn("random", "Get a truly random result: flip a coin, roll a dice, pick a number or pick one of "
                        + "several options. Use it whenever someone asks you to decide randomly.", true,
                enumParam("kind", "What to do", List.of("coin", "dice", "number", "pick")),
                param("max", "INTEGER", "Highest number for dice (default 6) or number (default 100)."),
                arrayParam("options", "The options to pick from (only for pick).")));
        if (cfg().bool("actions.allow-messages", true)) {
            list.add(fn("send_message", "Type a text message in the chat. Use it for things that are hard to say "
                            + "out loud, like lists, links or pinging someone (write @Name to ping them).", false,
                    required(param("text", "STRING", "The message to send.")),
                    server ? param("channel", "STRING", "Text channel name. Leave empty for the channel you're looking at.") : null));
        }
        if (cfg().bool("actions.allow-reactions", true)) {
            list.add(fn("react", "Put an emoji reaction on someone's message.", false,
                    required(param("emoji", "STRING", "ONE emoji character, like 😂 or 👍 (not a name).")),
                    param("person", "STRING", "Whose latest message to react to. Leave empty for the message you're answering.")));
        }
        if (server && cfg().bool("actions.allow-status", true)) {
            list.add(fn("set_status", "Change what shows under your name in Discord, like \"Playing Minecraft\".", false,
                    enumParam("type", "Kind of status. clear removes it.",
                            List.of("playing", "listening", "watching", "competing", "custom", "clear")),
                    param("text", "STRING", "What you're doing, e.g. Minecraft.")));
        }
        if (server && cfg().bool("actions.allow-nickname", true)) {
            list.add(fn("change_nickname", "Change your nickname in this server.", false,
                    param("nickname", "STRING", "The new nickname (max 32 characters). Empty = your normal name.")));
        }
        if (server && cfg().bool("actions.allow-polls", true)) {
            list.add(fn("create_poll", "Start a Discord poll in the text channel so people can vote.", false,
                    required(param("question", "STRING", "The poll question.")),
                    required(arrayParam("answers", "2 to 10 short answers people can vote for.")),
                    param("hours", "INTEGER", "How long the poll stays open (1 to 768, default 24).")));
        }
        if (server && cfg().bool("actions.allow-voice-control", true)) {
            list.add(fn("join_voice", "Join a voice channel so you can talk out loud with people.", false,
                    param("channel", "STRING", "Voice channel name. Leave empty to join whoever asked.")));
            list.add(fn("leave_voice", "Leave the voice channel. Say bye first!", false));
        }
        if (server && cfg().bool("actions.allow-soundboard", true)) {
            List<String> names = soundNames(room.guild());
            if (!names.isEmpty()) {
                list.add(fn("play_soundboard", "Play a soundboard sound in your voice channel (only works while "
                                + "you're in one). Available sounds: " + String.join(", ", names) + ".", false,
                        required(param("sound", "STRING", "The sound's name."))));
            }
        }
        return list;
    }

    private static JsonObject fn(String name, String description, boolean blocking, JsonObject... params) {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("description", description);
        // BLOCKING = the AI waits for the answer (needed to "see"); the rest run while it keeps talking
        fn.addProperty("behavior", blocking ? "BLOCKING" : "NON_BLOCKING");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (JsonObject p : params) {
            if (p == null) {
                continue;
            }
            String pName = p.remove("__name").getAsString();
            boolean req = p.has("__required") && p.remove("__required").getAsBoolean();
            properties.add(pName, p);
            if (req) {
                required.add(pName);
            }
        }
        if (properties.size() > 0) {
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

    private static JsonObject arrayParam(String name, String description) {
        JsonObject p = param(name, "ARRAY", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "STRING");
        p.add("items", items);
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

    // ------------------------------------------------------------------ execution (worker thread)

    public Result execute(Room room, String name, JsonObject args) {
        return switch (name) {
            case "look_around" -> lookAround(room);
            case "random" -> random(args);
            case "send_message" -> allowed("allow-messages") ? sendMessage(room, args) : off();
            case "react" -> allowed("allow-reactions") ? react(room, args) : off();
            case "set_status" -> allowed("allow-status") && !room.isDm() ? setStatus(args) : off();
            case "change_nickname" -> allowed("allow-nickname") && !room.isDm() ? changeNickname(room, args) : off();
            case "create_poll" -> allowed("allow-polls") && !room.isDm() ? createPoll(room, args) : off();
            case "join_voice" -> allowed("allow-voice-control") && !room.isDm() ? joinVoice(room, args) : off();
            case "leave_voice" -> allowed("allow-voice-control") && !room.isDm() ? leaveVoice(room) : off();
            case "play_soundboard" -> allowed("allow-soundboard") && !room.isDm() ? playSound(room, args) : off();
            default -> Result.fail("Unknown action " + name);
        };
    }

    private boolean allowed(String key) {
        return cfg().bool("actions." + key, true);
    }

    private static Result off() {
        return Result.fail("You're not allowed to do that here.");
    }

    // ---- look_around

    private Result lookAround(Room room) {
        JsonObject out = new JsonObject();
        out.addProperty("time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE HH:mm", Locale.ENGLISH)));
        Guild guild = room.guild();
        if (guild != null) {
            out.addProperty("server", guild.getName());
            AudioChannel mine = room.voiceChannel();
            out.addProperty("your_voice_channel", mine == null ? "none, you are not in a voice call" : mine.getName());
            JsonArray calls = new JsonArray();
            List<AudioChannel> channels = new ArrayList<>(guild.getVoiceChannels());
            channels.addAll(guild.getStageChannels());
            for (AudioChannel channel : channels) {
                JsonArray people = new JsonArray();
                for (Member member : channel.getMembers()) {
                    if (member.getIdLong() == guild.getSelfMember().getIdLong()) {
                        continue;
                    }
                    GuildVoiceState state = member.getVoiceState();
                    String extra = member.getUser().isBot() ? " (a bot)"
                            : state != null && state.isSelfDeafened() ? " (deafened)"
                            : state != null && state.isSelfMuted() ? " (muted)" : "";
                    people.add(member.getEffectiveName() + extra);
                }
                if (!people.isEmpty()) {
                    JsonObject call = new JsonObject();
                    call.addProperty("channel", channel.getName());
                    call.add("people", people);
                    calls.add(call);
                }
            }
            out.add("voice_channels_with_people", calls);
        }
        MessageChannel text = room.textChannel();
        if (text == null) {
            out.addProperty("text_channel", "none yet");
        } else {
            out.addProperty("text_channel", room.isDm() ? "your DMs" : "#" + text.getName());
            boolean canRead = !(text instanceof GuildMessageChannel g)
                    || g.getGuild().getSelfMember().hasPermission(g, Permission.MESSAGE_HISTORY);
            if (canRead) {
                try {
                    List<Message> history = new ArrayList<>(text.getHistory().retrievePast(10).complete());
                    Collections.reverse(history); // oldest first
                    JsonArray recent = new JsonArray();
                    long self = bot.jda().getSelfUser().getIdLong();
                    for (Message m : history) {
                        String who = m.getAuthor().getIdLong() == self ? "you"
                                : m.getMember() != null ? m.getMember().getEffectiveName() : m.getAuthor().getEffectiveName();
                        String content = m.getContentDisplay().replace('\n', ' ').trim();
                        if (content.isEmpty() && !m.getAttachments().isEmpty()) {
                            content = "[file: " + m.getAttachments().get(0).getFileName() + "]";
                        }
                        if (content.isEmpty() && m.getPoll() != null) {
                            content = "[poll: " + m.getPoll().getQuestion().getText() + "]";
                        }
                        recent.add(who + ": " + Room.clip(content, 200));
                    }
                    out.add("latest_messages", recent);
                } catch (Exception ex) {
                    out.addProperty("latest_messages", "couldn't read them");
                }
            }
        }
        return new Result(out, true);
    }

    // ---- random

    private static Result random(JsonObject args) {
        String kind = str(args, "kind", "coin").toLowerCase(Locale.ROOT);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        return switch (kind) {
            case "dice" -> Result.ok("Rolled a " + (1 + rng.nextInt(Math.max(2, num(args, "max", 6)))));
            case "number" -> Result.ok("The number is " + (1 + rng.nextInt(Math.max(2, num(args, "max", 100)))));
            case "pick" -> {
                List<String> options = list(args, "options");
                yield options.isEmpty() ? Result.fail("No options given to pick from")
                        : Result.ok("Picked: " + options.get(rng.nextInt(options.size())));
            }
            default -> Result.ok(rng.nextBoolean() ? "Heads" : "Tails");
        };
    }

    // ---- send_message

    private Result sendMessage(Room room, JsonObject args) {
        String text = str(args, "text", "").trim();
        if (text.isEmpty()) {
            return Result.fail("The message was empty");
        }
        if (!takeMessageSlot(room)) {
            return Result.fail("You're sending messages too fast, wait a bit.");
        }
        MessageChannel channel = room.textChannel();
        String wanted = str(args, "channel", "").replace("#", "").trim();
        Guild guild = room.guild();
        if (guild != null && !wanted.isEmpty()) {
            channel = null;
            for (TextChannel candidate : guild.getTextChannels()) {
                if (candidate.getName().equalsIgnoreCase(wanted) && candidate.canTalk()) {
                    channel = candidate;
                    break;
                }
            }
            if (channel == null) {
                return Result.fail("There's no text channel called " + wanted + " that you can write in.");
            }
        }
        if (channel == null) {
            return Result.fail("You don't have a text channel to write in yet. Someone has to type to you first.");
        }
        channel.sendMessage(Room.clip(room.withPings(text), 2000))
                .setAllowedMentions(EnumSet.of(Message.MentionType.USER))
                .complete();
        return Result.ok("Sent in " + (room.isDm() ? "DMs" : "#" + channel.getName()));
    }

    private boolean takeMessageSlot(Room room) {
        Deque<Long> times = sent.computeIfAbsent(room.label(), k -> new ArrayDeque<>());
        synchronized (times) {
            long now = System.currentTimeMillis();
            while (!times.isEmpty() && now - times.peekFirst() > 60_000) {
                times.pollFirst();
            }
            if (times.size() >= MESSAGES_PER_MINUTE) {
                return false;
            }
            times.addLast(now);
            return true;
        }
    }

    // ---- react

    private Result react(Room room, JsonObject args) {
        String emoji = str(args, "emoji", "").trim();
        if (emoji.isEmpty() || emoji.chars().allMatch(c -> c < 128)) {
            return Result.fail("Use a real emoji character like 😂, not a name.");
        }
        Message target = null;
        String person = str(args, "person", "").trim().toLowerCase(Locale.ROOT);
        Message answering = room.pendingReply();
        if (person.isEmpty() && answering != null) {
            target = answering;
        } else {
            MessageChannel channel = answering != null ? answering.getChannel() : room.textChannel();
            if (channel == null) {
                return Result.fail("You're not looking at any text channel.");
            }
            long self = bot.jda().getSelfUser().getIdLong();
            for (Message m : channel.getHistory().retrievePast(20).complete()) {
                if (m.getAuthor().getIdLong() == self) {
                    continue;
                }
                String name = m.getMember() != null ? m.getMember().getEffectiveName() : m.getAuthor().getEffectiveName();
                if (person.isEmpty() || name.toLowerCase(Locale.ROOT).contains(person)
                        || m.getAuthor().getName().toLowerCase(Locale.ROOT).contains(person)) {
                    target = m;
                    break;
                }
            }
        }
        if (target == null) {
            return Result.fail(person.isEmpty() ? "There's no message to react to." : "Couldn't find a recent message from " + person + ".");
        }
        try {
            target.addReaction(Emoji.fromUnicode(emoji)).complete();
        } catch (Exception ex) {
            return Result.fail("Discord didn't accept that emoji. Use exactly one normal emoji character.");
        }
        return Result.ok("Reacted with " + emoji);
    }

    // ---- set_status

    private Result setStatus(JsonObject args) {
        String type = str(args, "type", "playing").toLowerCase(Locale.ROOT);
        String text = Room.clip(str(args, "text", "").trim(), 128);
        if (!type.equals("clear") && text.isEmpty()) {
            return Result.fail("Say what the status should be.");
        }
        Activity activity = switch (type) {
            case "clear" -> null;
            case "listening" -> Activity.listening(text);
            case "watching" -> Activity.watching(text);
            case "competing" -> Activity.competing(text);
            case "custom" -> Activity.customStatus(text);
            default -> Activity.playing(text);
        };
        bot.jda().getPresence().setActivity(activity);
        return Result.ok(activity == null ? "Status cleared" : "Status is now: " + type + " " + text);
    }

    // ---- change_nickname

    private Result changeNickname(Room room, JsonObject args) {
        Guild guild = room.guild();
        if (guild == null) {
            return off();
        }
        if (!guild.getSelfMember().hasPermission(Permission.NICKNAME_CHANGE)) {
            return Result.fail("You don't have permission to change your nickname here.");
        }
        String nickname = str(args, "nickname", "").trim();
        if (nickname.length() > 32) {
            nickname = nickname.substring(0, 32);
        }
        guild.getSelfMember().modifyNickname(nickname.isEmpty() ? null : nickname).complete();
        return Result.ok(nickname.isEmpty() ? "Nickname reset" : "Your nickname is now " + nickname);
    }

    // ---- create_poll

    private Result createPoll(Room room, JsonObject args) {
        MessageChannel channel = room.textChannel();
        if (!(channel instanceof GuildMessageChannel guildChannel)) {
            return Result.fail("You don't have a text channel to post the poll in. Someone has to type to you first.");
        }
        if (!guildChannel.getGuild().getSelfMember().hasPermission(guildChannel, Permission.MESSAGE_SEND_POLLS)) {
            return Result.fail("You're not allowed to make polls in #" + guildChannel.getName());
        }
        String question = Room.clip(str(args, "question", "").trim(), 300);
        List<String> answers = list(args, "answers");
        if (question.isEmpty() || answers.size() < 2) {
            return Result.fail("A poll needs a question and at least 2 answers.");
        }
        int hours = Math.max(1, Math.min(768, num(args, "hours", 24)));
        MessagePollBuilder poll = net.dv8tion.jda.api.utils.messages.MessagePollData.builder(question)
                .setDuration(Duration.ofHours(hours));
        for (String answer : answers.subList(0, Math.min(10, answers.size()))) {
            poll.addAnswer(Room.clip(answer, 55));
        }
        guildChannel.sendMessagePoll(poll.build()).complete();
        return Result.ok("Poll posted in #" + guildChannel.getName() + " for " + hours + " hours");
    }

    // ---- join_voice / leave_voice

    private Result joinVoice(Room room, JsonObject args) {
        Guild guild = room.guild();
        if (guild == null) {
            return off();
        }
        String wanted = str(args, "channel", "").trim();
        AudioChannel target = null;
        if (!wanted.isEmpty()) {
            List<AudioChannel> channels = new ArrayList<>(guild.getVoiceChannels());
            channels.addAll(guild.getStageChannels());
            for (AudioChannel c : channels) {
                if (c.getName().equalsIgnoreCase(wanted)) {
                    target = c;
                    break;
                }
            }
            if (target == null) {
                for (AudioChannel c : channels) {
                    if (c.getName().toLowerCase(Locale.ROOT).contains(wanted.toLowerCase(Locale.ROOT))) {
                        target = c;
                        break;
                    }
                }
            }
            if (target == null) {
                return Result.fail("There's no voice channel called " + wanted);
            }
        } else {
            long asker = room.lastPersonId();
            Member member = asker == 0 ? null : guild.getMemberById(asker);
            GuildVoiceState state = member == null ? null : member.getVoiceState();
            target = state == null ? null : state.getChannel();
            if (target == null) {
                String who = room.lastPersonName().isEmpty() ? "They" : room.lastPersonName();
                return Result.fail(who + " isn't in a voice channel. Ask them to join one first, or say which channel.");
            }
        }
        if (target.getIdLong() == room.voiceChannelId()) {
            return Result.ok("You're already in " + target.getName());
        }
        String error = room.joinVoice(target, 0, false);
        return error == null ? Result.ok("Joined " + target.getName()) : Result.fail(error);
    }

    private Result leaveVoice(Room room) {
        if (!room.inVoice()) {
            return Result.fail("You're not in a voice channel.");
        }
        // let Bobby finish saying bye before hanging up
        long start = System.currentTimeMillis();
        try {
            TimeUnit.MILLISECONDS.sleep(1500);
            while (room.isSpeaking() && System.currentTimeMillis() - start < 12_000) {
                TimeUnit.MILLISECONDS.sleep(200);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        room.leaveVoice(null);
        return Result.ok("Left the voice channel");
    }

    // ---- play_soundboard

    private List<SoundboardSound> sounds(Guild guild) {
        List<SoundboardSound> all = new ArrayList<>();
        if (guild != null) {
            for (SoundboardSound s : guild.getSoundboardSounds()) {
                if (s.isAvailable()) {
                    all.add(s);
                }
            }
        }
        all.addAll(defaultSounds);
        return all;
    }

    private List<String> soundNames(Guild guild) {
        List<String> names = new ArrayList<>();
        for (SoundboardSound s : sounds(guild)) {
            if (!names.contains(s.getName())) {
                names.add(s.getName());
            }
            if (names.size() >= 60) {
                break;
            }
        }
        return names;
    }

    private Result playSound(Room room, JsonObject args) {
        AudioChannel channel = room.voiceChannel();
        if (!(channel instanceof VoiceChannel voiceChannel)) {
            return Result.fail("You have to be in a normal voice channel to play sounds.");
        }
        String wanted = str(args, "sound", "").trim().toLowerCase(Locale.ROOT);
        List<SoundboardSound> all = sounds(room.guild());
        SoundboardSound pick = null;
        for (SoundboardSound s : all) {
            if (s.getName().equalsIgnoreCase(wanted)) {
                pick = s;
                break;
            }
        }
        if (pick == null && !wanted.isEmpty()) {
            for (SoundboardSound s : all) {
                if (s.getName().toLowerCase(Locale.ROOT).contains(wanted) || wanted.contains(s.getName().toLowerCase(Locale.ROOT))) {
                    pick = s;
                    break;
                }
            }
        }
        if (pick == null) {
            return Result.fail("There's no sound called " + wanted);
        }
        Guild from = pick.getGuild();
        try {
            voiceChannel.sendSoundboardSound(pick, from == null ? null : from.getId()).complete();
        } catch (Exception ex) {
            return Result.fail("Discord wouldn't play it: " + ex.getMessage());
        }
        return Result.ok("Played " + pick.getName());
    }

    // ------------------------------------------------------------------ argument helpers

    private static String str(JsonObject args, String key, String fallback) {
        JsonElement el = args.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : fallback;
    }

    private static int num(JsonObject args, String key, int fallback) {
        JsonElement el = args.get(key);
        try {
            return el != null && el.isJsonPrimitive() ? (int) Math.round(el.getAsDouble()) : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static List<String> list(JsonObject args, String key) {
        List<String> out = new ArrayList<>();
        JsonElement el = args.get(key);
        if (el != null && el.isJsonArray()) {
            for (JsonElement item : el.getAsJsonArray()) {
                if (item.isJsonPrimitive() && !item.getAsString().isBlank()) {
                    out.add(item.getAsString().trim());
                }
            }
        } else if (el != null && el.isJsonPrimitive()) {
            for (String part : el.getAsString().split(",")) {
                if (!part.isBlank()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }
}
