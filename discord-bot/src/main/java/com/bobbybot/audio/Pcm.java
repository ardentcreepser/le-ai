package com.bobbybot.audio;

/**
 * Audio conversions. All audio is 16-bit signed PCM.
 * <ul>
 *   <li>Discord gives and takes 48 kHz <b>stereo, big-endian</b> (3840 bytes per 20 ms).</li>
 *   <li>Gemini wants 16 kHz mono little-endian in, and sends 24 kHz mono little-endian out.</li>
 * </ul>
 */
public final class Pcm {

    /** Bytes in 20 ms of Discord audio. */
    public static final int DISCORD_FRAME_BYTES = 3840;

    private Pcm() {
    }

    /** Discord 48 kHz stereo big-endian -> 16 kHz mono samples (averages left/right, then every 3 samples). */
    public static short[] discordToGemini(byte[] stereoBE) {
        int frames = stereoBE.length / 4;
        short[] out = new short[frames / 3];
        for (int i = 0; i < out.length; i++) {
            int sum = 0;
            for (int k = 0; k < 3; k++) {
                int f = (i * 3 + k) * 4;
                short left = (short) ((stereoBE[f] << 8) | (stereoBE[f + 1] & 0xFF));
                short right = (short) ((stereoBE[f + 2] << 8) | (stereoBE[f + 3] & 0xFF));
                sum += left + right;
            }
            out[i] = (short) (sum / 6);
        }
        return out;
    }

    /** 48 kHz mono samples -> Discord's 48 kHz stereo big-endian bytes (same sound in both ears). */
    public static byte[] monoToDiscord(short[] mono48k) {
        byte[] out = new byte[mono48k.length * 4];
        for (int i = 0; i < mono48k.length; i++) {
            byte hi = (byte) ((mono48k[i] >> 8) & 0xFF);
            byte lo = (byte) (mono48k[i] & 0xFF);
            out[i * 4] = hi;
            out[i * 4 + 1] = lo;
            out[i * 4 + 2] = hi;
            out[i * 4 + 3] = lo;
        }
        return out;
    }

    public static byte[] toBytesLE(short[] samples, int length) {
        byte[] out = new byte[length * 2];
        for (int i = 0; i < length; i++) {
            out[i * 2] = (byte) (samples[i] & 0xFF);
            out[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return out;
    }

    public static short[] fromBytesLE(byte[] bytes) {
        short[] out = new short[bytes.length / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
        }
        return out;
    }

    public static short clip(double value) {
        if (value > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) value;
    }
}
