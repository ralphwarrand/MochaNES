package com.mochanes.emulator.gui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import com.mochanes.emulator.AudioSink;

/**
 * Desktop audio output, backed by a {@code javax.sound} playback line.
 *
 * <p>This is also the emulator's clock: {@link #write} blocks once the device
 * buffer is full, which is what paces the emulation thread to roughly 60Hz.
 */
public final class JavaSoundSink implements AudioSink {

    /**
     * ~93ms of slack. The emulation thread paces itself by blocking in
     * {@code write}, so this buffer is all that absorbs jitter from GC or a slow
     * repaint; at 4096 bytes (46ms, under three frames) any hitch drained it and
     * produced an audible dropout.
     */
    private static final int BUFFER_BYTES = 8192;

    private final SourceDataLine line;

    private JavaSoundSink(SourceDataLine line) {
        this.line = line;
    }

    /**
     * Opens the default output device, falling back to {@link AudioSink#SILENT}
     * if the machine has none.
     *
     * <p>Note that the silent fallback does not block, so a caller relying on
     * audio for frame pacing must provide its own when this happens.
     */
    public static AudioSink openOrSilent() {
        try {
            AudioFormat format = new AudioFormat(44100, 16, 1, true, false);
            SourceDataLine line = AudioSystem.getSourceDataLine(format);
            line.open(format, BUFFER_BYTES);
            line.start();
            return new JavaSoundSink(line);
        } catch (LineUnavailableException | IllegalArgumentException e) {
            // A machine with no sound card - a CI runner, or a headless box -
            // reports it as IllegalArgumentException from getSourceDataLine
            // rather than LineUnavailableException. Audio is optional, so carry
            // on silently instead of taking the emulator down with us.
            System.err.println("[APU] No audio output available; running silently (" + e.getMessage() + ")");
            return AudioSink.SILENT;
        }
    }

    @Override
    public void write(byte[] samples, int offset, int length) {
        line.write(samples, offset, length);
    }

    @Override
    public void close() {
        try {
            line.stop();
            line.flush();
            line.close();
        } catch (RuntimeException e) {
            // Already closed or unavailable; nothing useful to do.
        }
    }
}
