package com.chatterbox.voice;

import com.chatterbox.Brain;
import com.chatterbox.ChatterboxPlugin;
import com.chatterbox.audio.OutputBuffer;
import com.chatterbox.audio.Pcm;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import org.bukkit.entity.Mannequin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The bridge to Simple Voice Chat:
 * - EARS: grabs the microphone audio of players near the character and streams it to Gemini.
 * - MOUTH: plays Gemini's voice from the character's position, so it fades with distance like a real player.
 * Only loaded when Simple Voice Chat is installed.
 */
public final class VoiceBridge implements VoicechatPlugin, VoiceOutput {

    public static final String CATEGORY = "chatterbox";
    private static final short[] SILENCE = new short[OutputBuffer.FRAME];
    /** Send microphone audio to Gemini in 100 ms chunks (1600 samples at 16 kHz). */
    private static final int MIC_CHUNK = 1600;

    private final ChatterboxPlugin plugin;
    private final OutputBuffer output = new OutputBuffer();
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Chatterbox-Voice");
        t.setDaemon(true);
        return t;
    });

    private volatile VoicechatServerApi api;
    private final Object playerLock = new Object();
    private AudioPlayer player;
    private EntityAudioChannel channel;
    private UUID channelEntity;
    private volatile long lastFrameAt;
    private volatile boolean turnFinished = true;

    // microphone state (guarded by micLock)
    private final Object micLock = new Object();
    private final short[] micBuffer = new short[MIC_CHUNK * 4];
    private int micLength;
    private UUID speaker;
    private long speakerLastPacket;

    // settings
    private volatile float distance;
    private volatile boolean ignoreMicWhileSpeaking;
    private volatile int silencePaddingMs;

    public VoiceBridge(ChatterboxPlugin plugin) {
        this.plugin = plugin;
        reload();
        timer.scheduleAtFixedRate(this::checkEndOfSpeech, 50, 50, TimeUnit.MILLISECONDS);
    }

    @Override
    public void reload() {
        distance = (float) plugin.getConfig().getDouble("voice.speaking-range", 32);
        ignoreMicWhileSpeaking = plugin.getConfig().getBoolean("voice.ignore-mic-while-speaking", true);
        silencePaddingMs = plugin.getConfig().getInt("voice.silence-padding-ms", 300);
        output.setGain(plugin.getConfig().getDouble("voice.volume", 1.0));
        synchronized (playerLock) {
            if (channel != null) {
                channel.setDistance(distance);
            }
        }
    }

    @Override
    public void shutdown() {
        timer.shutdownNow();
        clear();
        synchronized (playerLock) {
            if (player != null) {
                player.stopPlaying();
            }
        }
        decoders.values().forEach(OpusDecoder::close);
        decoders.clear();
    }

    // ------------------------------------------------------------------ VoicechatPlugin

    @Override
    public String getPluginId() {
        return "chatterbox";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
        registration.registerEvent(PlayerDisconnectedEvent.class, event -> {
            OpusDecoder decoder = decoders.remove(event.getPlayerUuid());
            if (decoder != null) {
                decoder.close();
            }
        });
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        api = event.getVoicechat();
        api.registerVolumeCategory(api.volumeCategoryBuilder()
                .setId(CATEGORY)
                .setName("AI Character")
                .setDescription("How loud the Chatterbox AI character is")
                .build());
        plugin.getLogger().info("Hooked into Simple Voice Chat.");
    }

    // ------------------------------------------------------------------ EARS

    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        if (sender == null) {
            return;
        }
        UUID id = sender.getPlayer().getUuid();
        Brain brain = plugin.brain();
        if (brain == null || !brain.isListening() || !plugin.character().canHear(id)) {
            return;
        }
        if (ignoreMicWhileSpeaking && isSpeaking()) {
            return; // avoids the AI hearing itself through someone's speakers
        }
        byte[] opus = event.getPacket().getOpusEncodedData();
        long now = System.currentTimeMillis();

        synchronized (micLock) {
            if (speaker != null && !speaker.equals(id)) {
                if (now - speakerLastPacket < 700) {
                    return; // someone else is talking, one at a time
                }
                finishUtterance(brain);
            }
            if (speaker == null) {
                speaker = id;
                brain.speakerStarted(id);
            }
            speakerLastPacket = now;
            if (opus == null || opus.length == 0) {
                finishUtterance(brain); // end-of-speech packet
                return;
            }
            OpusDecoder decoder = decoders.computeIfAbsent(id, k -> event.getVoicechat().createDecoder());
            short[] pcm48 = decoder.decode(opus);
            short[] pcm16 = Pcm.downsample48to16(pcm48);
            for (short sample : pcm16) {
                micBuffer[micLength++] = sample;
                if (micLength == MIC_CHUNK) {
                    brain.sendAudio(Pcm.toBytesLE(micBuffer, micLength));
                    micLength = 0;
                }
            }
        }
    }

    private void checkEndOfSpeech() {
        try {
            synchronized (micLock) {
                if (speaker != null && System.currentTimeMillis() - speakerLastPacket > 300) {
                    Brain brain = plugin.brain();
                    if (brain != null) {
                        finishUtterance(brain);
                    } else {
                        speaker = null;
                    }
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Voice timer error: " + ex);
        }
    }

    /** The current speaker stopped: send what's left plus a little silence, then mark the end. Holds micLock. */
    private void finishUtterance(Brain brain) {
        if (micLength > 0) {
            brain.sendAudio(Pcm.toBytesLE(micBuffer, micLength));
            micLength = 0;
        }
        int padding = 16 * Math.max(0, silencePaddingMs); // samples of silence at 16 kHz
        if (padding > 0) {
            brain.sendAudio(new byte[padding * 2]);
        }
        brain.endAudioStream();
        speaker = null;
    }

    // ------------------------------------------------------------------ MOUTH

    @Override
    public void play(byte[] pcm24k) {
        turnFinished = false;
        output.push(pcm24k);
        ensurePlaying();
    }

    @Override
    public void finishTurn() {
        turnFinished = true;
        output.flush();
        ensurePlaying();
    }

    @Override
    public void clear() {
        output.clear();
        lastFrameAt = 0;
    }

    @Override
    public boolean isSpeaking() {
        return output.hasFrames() || System.currentTimeMillis() - lastFrameAt < 600;
    }

    private void ensurePlaying() {
        VoicechatServerApi voicechat = api;
        Mannequin body = plugin.character().body();
        if (voicechat == null || body == null || !output.hasFrames()) {
            return;
        }
        synchronized (playerLock) {
            if (channel == null || channel.isClosed() || !body.getUniqueId().equals(channelEntity)) {
                channel = voicechat.createEntityAudioChannel(UUID.randomUUID(), voicechat.fromEntity(body));
                if (channel == null) {
                    return;
                }
                channel.setDistance(distance);
                channel.setCategory(CATEGORY);
                channelEntity = body.getUniqueId();
                if (player != null) {
                    player.stopPlaying();
                    player = null;
                }
            }
            if (player == null || player.isStopped()) {
                OpusEncoder encoder = voicechat.createEncoder();
                lastFrameAt = System.currentTimeMillis();
                player = voicechat.createAudioPlayer(channel, encoder, this::nextFrame);
                player.setOnStopped(encoder::close);
                player.startPlaying();
            }
        }
    }

    /** Called by Simple Voice Chat every 20 ms while playing. Returning null stops the player. */
    private short[] nextFrame() {
        short[] frame = output.poll();
        long now = System.currentTimeMillis();
        if (frame != null) {
            lastFrameAt = now;
            return frame;
        }
        // Gemini's audio sometimes arrives in bursts: bridge short gaps with silence mid-sentence
        long allowedGap = turnFinished ? 200 : 1500;
        return now - lastFrameAt < allowedGap ? SILENCE : null;
    }
}
