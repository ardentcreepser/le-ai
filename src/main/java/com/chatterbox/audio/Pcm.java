package com.chatterbox.audio;

/**
 * Tiny audio helpers. Minecraft voice chat uses 48 kHz mono, Gemini wants 16 kHz in and sends 24 kHz out.
 * All audio is 16-bit signed PCM, little-endian when it is turned into bytes.
 */
public final class Pcm {

    private Pcm() {
    }

    /** 48 kHz -> 16 kHz by averaging every 3 samples (also acts as a simple low-pass filter). */
    public static short[] downsample48to16(short[] in) {
        short[] out = new short[in.length / 3];
        for (int i = 0; i < out.length; i++) {
            int j = i * 3;
            out[i] = (short) ((in[j] + in[j + 1] + in[j + 2]) / 3);
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
