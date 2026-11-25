package com.bobbybot;

import com.bobbybot.audio.OutputBuffer;
import com.bobbybot.audio.Pcm;
import net.dv8tion.jda.api.audio.AudioReceiveHandler;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.audio.UserAudio;
import net.dv8tion.jda.api.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Bobby's ears and mouth in a Discord voice channel.
 * <ul>
 *   <li>EARS: Discord gives us 20 ms of decoded audio per person. We take one speaker at a time and stream
 *   their voice to Gemini at 16 kHz.</li>
 *   <li>MOUTH: Gemini's voice (24 kHz) is upsampled to 48 kHz stereo and handed to Discord 20 ms at a time.</li>
 * </ul>
 */
public final class VoiceLink implements AudioSendHandler, AudioReceiveHandler {

    private static final Logger LOG = LoggerFactory.getLogger("Voice");
    private static final byte[] SILENCE = new byte[Pcm.DISCORD_FRAME_BYTES];
    /** Send microphone audio to Gemini in 100 ms chunks (1600 samples at 16 kHz). */
    private static final int MIC_CHUNK = 1600;
    /** How long someone has to be quiet before somebody else gets the floor. */
    private static final long SPEAKER_LOCK_MS = 700;
    /** How long without packets counts as "stopped talking" (Discord sends nothing while you're quiet). */
    private static final long END_OF_SPEECH_MS = 300;

    private final Room room;
    private final OutputBuffer output = new OutputBuffer();
    private final ScheduledExecutorService timer;

    // mouth
    private volatile long lastFrameAt;
    private volatile boolean turnFinished = true;
    private byte[] nextFrame; // only touched by JDA's audio send thread

    // ears (guarded by micLock)
    private final Object micLock = new Object();
    private final short[] micBuffer = new short[MIC_CHUNK * 4];
    private int micLength;
    private long speaker;
    private long speakerLastPacket;

    // settings
    private volatile boolean ignoreMicWhileSpeaking;
    private volatile boolean ignoreBots;
    private volatile int silencePaddingMs;

    public VoiceLink(Room room) {
        this.room = room;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Voice-" + room.label());
            t.setDaemon(true);
            return t;
        });
        reload(room.config());
        timer.scheduleAtFixedRate(this::checkEndOfSpeech, 50, 50, TimeUnit.MILLISECONDS);
    }

    public void reload(Config config) {
        ignoreMicWhileSpeaking = config.bool("voice.ignore-mic-while-speaking", false);
        ignoreBots = config.bool("voice.ignore-bots", true);
        silencePaddingMs = config.integer("voice.silence-padding-ms", 300);
        output.setGain(config.decimal("voice.volume", 1.0));
    }

    public void shutdown() {
        timer.shutdownNow();
        clear();
    }

    // ------------------------------------------------------------------ EARS

    @Override
    public boolean canReceiveUser() {
        return true;
    }

    @Override
    public void handleUserAudio(UserAudio userAudio) {
        try {
            receive(userAudio);
        } catch (Exception ex) {
            LOG.warn("Problem with incoming voice audio: {}", ex.toString());
        }
    }

    private void receive(UserAudio userAudio) {
        User user = userAudio.getUser();
        if (user.getIdLong() == user.getJDA().getSelfUser().getIdLong() || (ignoreBots && user.isBot())) {
            return;
        }
        if (!room.isListening()) {
            return;
        }
        if (ignoreMicWhileSpeaking && isSpeaking()) {
            return;
        }
        long id = user.getIdLong();
        long now = System.currentTimeMillis();
        synchronized (micLock) {
            if (speaker != 0 && speaker != id) {
                if (now - speakerLastPacket < SPEAKER_LOCK_MS) {
                    return; // someone else is talking, one at a time
                }
                finishUtterance();
            }
            if (speaker == 0) {
                speaker = id;
                room.speakerStarted(user);
            }
            speakerLastPacket = now;
            short[] pcm16 = Pcm.discordToGemini(userAudio.getAudioData(1.0));
            for (short sample : pcm16) {
                micBuffer[micLength++] = sample;
                if (micLength == MIC_CHUNK) {
                    room.sendMicAudio(Pcm.toBytesLE(micBuffer, micLength));
                    micLength = 0;
                }
            }
        }
    }

    private void checkEndOfSpeech() {
        try {
            synchronized (micLock) {
                if (speaker != 0 && System.currentTimeMillis() - speakerLastPacket > END_OF_SPEECH_MS) {
                    finishUtterance();
                }
            }
        } catch (Exception ex) {
            LOG.warn("Voice timer error: {}", ex.toString());
        }
    }

    /** The current speaker stopped: send what's left plus a little silence, then mark the end. Holds micLock. */
    private void finishUtterance() {
        if (micLength > 0) {
            room.sendMicAudio(Pcm.toBytesLE(micBuffer, micLength));
            micLength = 0;
        }
        int padding = 16 * Math.max(0, silencePaddingMs); // samples of silence at 16 kHz
        if (padding > 0) {
            room.sendMicAudio(new byte[padding * 2]);
        }
        room.endAudioStream();
        speaker = 0;
    }

    // ------------------------------------------------------------------ MOUTH

    /** Queue Gemini audio (24 kHz mono little-endian). */
    public void play(byte[] pcm24k) {
        turnFinished = false;
        output.push(pcm24k);
    }

    public void finishTurn() {
        turnFinished = true;
        output.flush();
    }

    /** Stop talking right now (someone interrupted, or Bobby was muted). */
    public void clear() {
        output.clear();
        lastFrameAt = 0;
    }

    public boolean isSpeaking() {
        return output.hasFrames() || System.currentTimeMillis() - lastFrameAt < 600;
    }

    @Override
    public boolean canProvide() {
        if (nextFrame == null) {
            nextFrame = takeFrame();
        }
        return nextFrame != null;
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        byte[] frame = nextFrame != null ? nextFrame : takeFrame();
        nextFrame = null;
        return ByteBuffer.wrap(frame == null ? SILENCE : frame);
    }

    @Override
    public boolean isOpus() {
        return false;
    }

    /** The next 20 ms to play, or null when Bobby has nothing to say. */
    private byte[] takeFrame() {
        short[] frame = output.poll();
        long now = System.currentTimeMillis();
        if (frame != null) {
            lastFrameAt = now;
            return Pcm.monoToDiscord(frame);
        }
        if (lastFrameAt == 0) {
            return null;
        }
        // Gemini's audio sometimes arrives in bursts: bridge short gaps with silence mid-sentence
        long allowedGap = turnFinished ? 200 : 1500;
        return now - lastFrameAt < allowedGap ? SILENCE : null;
    }
}
