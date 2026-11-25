package com.bobbybot;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

import java.util.List;

/**
 * /bobby join | leave | status            - for everyone
 * /bobby-admin direct | reset | mute | unmute - for people with "Manage Server"
 */
public final class SlashCommands {

    private final BobbyBot bot;

    public SlashCommands(BobbyBot bot) {
        this.bot = bot;
    }

    public static List<CommandData> definitions(String name) {
        CommandData everyone = Commands.slash("bobby", "Talk to " + name)
                .addSubcommands(
                        new SubcommandData("join", name + " joins your voice channel")
                                .addOptions(new OptionData(OptionType.CHANNEL, "channel", "A different voice channel", false)
                                        .setChannelTypes(ChannelType.VOICE, ChannelType.STAGE)),
                        new SubcommandData("leave", name + " leaves the voice channel"),
                        new SubcommandData("status", "Check if " + name + "'s brain is working"));
        CommandData admin = Commands.slash("bobby-admin", "Control " + name + " (admins only)")
                .addSubcommands(
                        new SubcommandData("direct", "Secretly tell " + name + " what to do or say next")
                                .addOption(OptionType.STRING, "instruction", "e.g. \"Pretend you just saw a ghost\"", true),
                        new SubcommandData("reset", name + " forgets the whole conversation"),
                        new SubcommandData("mute", name + " stops listening and talking"),
                        new SubcommandData("unmute", name + " listens and talks again"))
                .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.MANAGE_SERVER));
        return List.of(everyone, admin);
    }

    public void handle(SlashCommandInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null) {
            event.reply("Use this in a server.").setEphemeral(true).queue();
            return;
        }
        Room room = bot.room(guild);
        String name = room.name();
        switch (event.getFullCommandName()) {
            case "bobby join" -> {
                AudioChannel target = null;
                OptionMapping option = event.getOption("channel");
                if (option != null) {
                    if (option.getAsChannel() instanceof AudioChannel audio) {
                        target = audio;
                    }
                } else {
                    Member member = event.getMember();
                    Member cached = member == null ? null : guild.getMemberById(member.getIdLong());
                    GuildVoiceState state = cached != null ? cached.getVoiceState() : member == null ? null : member.getVoiceState();
                    target = state == null ? null : state.getChannel();
                }
                if (target == null) {
                    event.reply("Join a voice channel first (or pick one in the command).").setEphemeral(true).queue();
                    return;
                }
                if (event.getMember() != null) {
                    room.rememberPerson(event.getMember().getEffectiveName(), event.getMember().getIdLong());
                }
                String error = room.joinVoice(target, event.getChannel().getIdLong(), true);
                if (error != null) {
                    event.reply(error).setEphemeral(true).queue();
                } else {
                    boolean subs = bot.config().bool("voice.subtitles", true);
                    event.reply(name + " is joining **" + target.getName() + "**!"
                            + (subs ? " What he says will show up here." : "")).queue();
                }
            }
            case "bobby leave" -> {
                if (!room.inVoice()) {
                    event.reply(name + " isn't in a voice channel.").setEphemeral(true).queue();
                    return;
                }
                room.leaveVoice("Someone used a command to make you leave the voice channel.");
                event.reply(name + " left the voice channel.").queue();
            }
            case "bobby status" -> event.reply(room.statusLine()).setEphemeral(true).queue();
            case "bobby-admin direct" -> {
                OptionMapping option = event.getOption("instruction");
                String note = option == null ? "" : option.getAsString().trim();
                if (note.isEmpty()) {
                    event.reply("Type an instruction.").setEphemeral(true).queue();
                    return;
                }
                room.direct(note);
                event.reply("Sent. Only you can see this.").setEphemeral(true).queue();
            }
            case "bobby-admin reset" -> {
                room.reset();
                event.reply(name + " forgot the conversation and is starting fresh.").setEphemeral(true).queue();
            }
            case "bobby-admin mute" -> {
                room.setMuted(true);
                event.reply(name + " is muted: he won't listen, talk or answer messages.").setEphemeral(true).queue();
            }
            case "bobby-admin unmute" -> {
                room.setMuted(false);
                event.reply(name + " is unmuted.").setEphemeral(true).queue();
            }
            default -> event.reply("Unknown command.").setEphemeral(true).queue();
        }
    }
}
