package com.chatterbox.audio;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Collects the AI's voice (24 kHz from Gemini), converts it to 48 kHz and cuts it into
 * 20 ms frames of 960 samples, which is what Simple Voice Chat plays.
 */
public final class OutputBuffer {

    public static final int FRAME = 960;

    private final ConcurrentLinkedQueue<short[]> frames = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private short[] partial = new short[FRAME];
    private int partialLength;
    private short last;
    private volatile double gain = 1.0;

    public void setGain(double gain) {
        this.gain = gain;
    }

    /** Add Gemini audio (16-bit little-endian PCM at 24 kHz). */
    public void push(byte[] pcm24k) {
        short[] samples = Pcm.fromBytesLE(pcm24k);
        double g = gain;
        synchronized (lock) {
            for (short raw : samples) {
                short current = g == 1.0 ? raw : Pcm.clip(raw * g);
                // 24 kHz -> 48 kHz: a point halfway to the new sample, then the sample itself
                emit((short) ((last + current) / 2));
                emit(current);
                last = current;
            }
        }
    }

    private void emit(short value) {
        partial[partialLength++] = value;
        if (partialLength == FRAME) {
            frames.add(partial);
            partial = new short[FRAME];
            partialLength = 0;
        }
    }

    /** Push out the last half-filled frame (padded with silence) at the end of a reply. */
    public void flush() {
        synchronized (lock) {
            if (partialLength > 0) {
                frames.add(partial);
                partial = new short[FRAME];
                partialLength = 0;
            }
            last = 0;
        }
    }

    public short[] poll() {
        return frames.poll();
    }

    public boolean hasFrames() {
        return !frames.isEmpty();
    }

    public void clear() {
        synchronized (lock) {
            frames.clear();
            partial = new short[FRAME];
            partialLength = 0;
            last = 0;
        }
    }
}
