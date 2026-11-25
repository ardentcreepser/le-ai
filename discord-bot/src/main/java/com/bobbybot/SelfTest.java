package com.bobbybot;

import club.minnced.discord.jdave.ffi.LibDave;
import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import com.bobbybot.audio.OutputBuffer;
import com.bobbybot.audio.Pcm;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.dv8tion.jda.api.audio.AudioNatives;

/**
 * java -jar BobbyBot.jar --selftest
 * Checks everything that can be checked without Discord or Gemini: the native audio and encryption
 * libraries for this computer, the audio conversions and the messages we would send to Gemini.
 */
final class SelfTest {

    private static int failures;

    private SelfTest() {
    }

    static int run() {
        System.out.println("BobbyBot self-test (" + System.getProperty("os.name") + ", " + System.getProperty("os.arch")
                + ", Java " + Runtime.version() + ")");

        check("Opus audio library loads", () -> AudioNatives.ensureOpus());
        check("DAVE voice encryption library loads", () -> {
            short version = LibDave.getMaxSupportedProtocolVersion();
            System.out.println("      DAVE protocol version " + version);
            return version > 0;
        });
        check("DAVE session factory", () -> new JDaveSessionFactory() != null);

        check("Default config", () -> {
            Config config = Config.defaults();
            return config.string("bobby.name", "").equals("Bobby")
                    && config.string("discord.token", "").equals(Config.TOKEN_PLACEHOLDER)
                    && config.string("gemini.api-key", "").equals(Config.KEY_PLACEHOLDER);
        });

        check("Discord audio -> Gemini (48 kHz stereo -> 16 kHz mono)", () -> {
            byte[] frame = new byte[Pcm.DISCORD_FRAME_BYTES];
            for (int i = 0; i < 960; i++) {
                short s = (short) (Math.sin(i * 2 * Math.PI * 440 / 48000) * 10000);
                frame[i * 4] = (byte) (s >> 8);
                frame[i * 4 + 1] = (byte) s;
                frame[i * 4 + 2] = (byte) (s >> 8);
                frame[i * 4 + 3] = (byte) s;
            }
            short[] out = Pcm.discordToGemini(frame);
            int peak = 0;
            for (short s : out) {
                peak = Math.max(peak, Math.abs(s));
            }
            return out.length == 320 && peak > 9000 && peak <= 10000;
        });

        check("Gemini audio -> Discord (24 kHz mono -> 48 kHz stereo)", () -> {
            OutputBuffer buffer = new OutputBuffer();
            short[] in = new short[480]; // 20 ms at 24 kHz
            for (int i = 0; i < in.length; i++) {
                in[i] = (short) (i * 50);
            }
            buffer.push(Pcm.toBytesLE(in, in.length));
            short[] frame = buffer.poll();
            if (frame == null || frame.length != OutputBuffer.FRAME || buffer.hasFrames()) {
                return false;
            }
            byte[] discord = Pcm.monoToDiscord(frame);
            short lastLeft = (short) ((discord[discord.length - 4] << 8) | (discord[discord.length - 3] & 0xFF));
            short lastRight = (short) ((discord[discord.length - 2] << 8) | (discord[discord.length - 1] & 0xFF));
            return discord.length == Pcm.DISCORD_FRAME_BYTES && lastLeft == in[479] && lastRight == in[479];
        });

        BobbyBot bot = new BobbyBot(Config.defaults(), "selftest");
        for (boolean server : new boolean[] {true, false}) {
            String kind = server ? "server" : "DM";
            check("Gemini setup message (" + kind + ")", () -> {
                Room room = Room.forTest(bot, server);
                try {
                    JsonObject setup = JsonParser.parseString(room.buildSetup().toString()).getAsJsonObject()
                            .getAsJsonObject("setup");
                    boolean ok = setup.get("model").getAsString().startsWith("models/")
                            && setup.getAsJsonObject("systemInstruction").toString().contains("Bobby");
                    JsonArray tools = setup.getAsJsonArray("tools").get(0).getAsJsonObject()
                            .getAsJsonArray("functionDeclarations");
                    StringBuilder names = new StringBuilder();
                    for (JsonElement el : tools) {
                        JsonObject fn = el.getAsJsonObject();
                        names.append(fn.get("name").getAsString()).append(' ');
                        ok &= fn.has("description") && fn.has("behavior");
                        if (fn.has("parameters")) {
                            JsonObject params = fn.getAsJsonObject("parameters");
                            ok &= params.get("type").getAsString().equals("OBJECT") && params.has("properties");
                            for (var p : params.getAsJsonObject("properties").entrySet()) {
                                JsonObject schema = p.getValue().getAsJsonObject();
                                ok &= !schema.has("__name") && !schema.has("__required") && schema.has("type");
                                if (schema.get("type").getAsString().equals("ARRAY")) {
                                    ok &= schema.has("items");
                                }
                            }
                        }
                    }
                    System.out.println("      tools: " + names.toString().trim());
                    return ok && tools.size() >= (server ? 9 : 4);
                } finally {
                    room.shutdown();
                }
            });
        }

        check("Slash command definitions", () -> SlashCommands.definitions("Bobby").size() == 2);

        System.out.println(failures == 0 ? "All checks passed." : failures + " check(s) FAILED.");
        return failures == 0 ? 0 : 1;
    }

    private interface Check {
        boolean run() throws Throwable;
    }

    private static void check(String name, Check check) {
        boolean ok;
        String detail = "";
        try {
            ok = check.run();
        } catch (Throwable t) {
            ok = false;
            detail = " (" + t + ")";
        }
        System.out.println((ok ? "  [OK]   " : "  [FAIL] ") + name + detail);
        if (!ok) {
            failures++;
        }
    }
}
