package com.bobbybot;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.GuildJoinEvent;
import net.dv8tion.jda.api.events.guild.GuildReadyEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Everything Discord tells the bot: messages, slash commands, people joining and leaving voice. */
public final class Listener extends ListenerAdapter {

    private final BobbyBot bot;
    private final SlashCommands commands;

    public Listener(BobbyBot bot) {
        this.bot = bot;
        this.commands = new SlashCommands(bot);
    }

    // ------------------------------------------------------------------ servers

    @Override
    public void onGuildReady(GuildReadyEvent event) {
        bot.registerCommands(event.getGuild());
    }

    @Override
    public void onGuildJoin(GuildJoinEvent event) {
        bot.registerCommands(event.getGuild());
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        commands.handle(event);
    }

    // ------------------------------------------------------------------ text messages

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        Message message = event.getMessage();
        User author = event.getAuthor();
        User self = event.getJDA().getSelfUser();
        if (author.isBot() || author.getIdLong() == self.getIdLong() || message.isWebhookMessage()) {
            return;
        }
        MessageType type = message.getType();
        if (type != MessageType.DEFAULT && type != MessageType.INLINE_REPLY) {
            return;
        }
        Config config = bot.config();

        if (!event.isFromGuild()) {
            if (config.bool("chat.allow-dms", true)) {
                bot.dmRoom(author).textMessage(message, author.getEffectiveName(), "DMs");
            }
            return;
        }

        String mode = config.string("chat.respond-to", "mention").toLowerCase(Locale.ROOT).trim();
        if (mode.equals("off")) {
            return;
        }
        Guild guild = event.getGuild();
        Room existing = bot.existingRoom(guild);
        long channelId = event.getChannel().getIdLong();
        boolean addressed = mode.equals("all")
                || message.getMentions().isMentioned(self, Message.MentionType.USER)
                || isReplyToBobby(message, self)
                || mentionsName(message.getContentRaw(), config.string("bobby.name", "Bobby"))
                || inChatChannel(config.strings("chat.chat-channels"), channelId)
                || (existing != null && existing.voiceChannelId() == channelId); // the text chat inside his voice channel
        if (!addressed) {
            return;
        }
        Member member = event.getMember();
        String name = member != null ? member.getEffectiveName() : author.getEffectiveName();
        bot.room(guild).textMessage(message, name, "#" + event.getChannel().getName());
    }

    private static boolean isReplyToBobby(Message message, User self) {
        Message referenced = message.getReferencedMessage();
        return referenced != null && referenced.getAuthor().getIdLong() == self.getIdLong();
    }

    private static boolean mentionsName(String content, String name) {
        if (name.isBlank()) {
            return false;
        }
        return Pattern.compile("(?<![\\w])" + Pattern.quote(name) + "(?![\\w])", Pattern.CASE_INSENSITIVE)
                .matcher(content).find();
    }

    private static boolean inChatChannel(List<String> ids, long channelId) {
        String id = Long.toUnsignedString(channelId);
        return ids.contains(id);
    }

    // ------------------------------------------------------------------ voice channels

    @Override
    public void onGuildVoiceUpdate(GuildVoiceUpdateEvent event) {
        Room room = bot.existingRoom(event.getGuild());
        if (room == null) {
            return;
        }
        Member member = event.getMember();
        AudioChannelUnion joined = event.getChannelJoined();
        AudioChannelUnion left = event.getChannelLeft();

        if (member.getIdLong() == event.getJDA().getSelfUser().getIdLong()) {
            if (joined == null) {
                room.voiceGone("You got disconnected from the voice channel.");
            } else if (left != null && joined.getIdLong() != room.voiceChannelId()) {
                room.voiceMoved(joined);
            }
            return;
        }

        long mine = room.voiceChannelId();
        if (mine == 0 || member.getUser().isBot()) {
            return;
        }
        boolean cameIn = joined != null && joined.getIdLong() == mine && (left == null || left.getIdLong() != mine);
        boolean wentOut = left != null && left.getIdLong() == mine && (joined == null || joined.getIdLong() != mine);
        if (cameIn) {
            room.someoneJoinedVoice(member);
        } else if (wentOut) {
            boolean alone = left.getMembers().stream().noneMatch(m -> !m.getUser().isBot());
            if (alone && bot.config().bool("voice.leave-when-alone", true)) {
                room.leaveVoice("Everyone left the voice channel, so you left too.");
            } else {
                room.someoneLeftVoice(member);
            }
        }
    }
}
