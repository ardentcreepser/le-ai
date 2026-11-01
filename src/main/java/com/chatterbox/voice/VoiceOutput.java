package com.chatterbox.voice;

/**
 * The AI's "mouth". Implemented by {@link VoiceBridge} when Simple Voice Chat is installed.
 * Kept as an interface so the plugin still loads (in text-only mode) without Simple Voice Chat.
 */
public interface VoiceOutput {

    /** Queue Gemini audio (24 kHz, 16-bit little-endian PCM) to be played from the character. */
    void play(byte[] pcm24k);

    /** The AI finished a reply. */
    void finishTurn();

    /** Stop talking immediately (someone interrupted). */
    void clear();

    /** True while the character is still playing audio. */
    boolean isSpeaking();

    /** Read new settings from the config. */
    void reload();

    /** Stop background threads. */
    default void shutdown() {
    }

    /** Silent stand-in used when Simple Voice Chat isn't installed. */
    VoiceOutput NONE = new VoiceOutput() {
        @Override
        public void play(byte[] pcm24k) {
        }

        @Override
        public void finishTurn() {
        }

        @Override
        public void clear() {
        }

        @Override
        public boolean isSpeaking() {
            return false;
        }

        @Override
        public void reload() {
        }
    };
}
