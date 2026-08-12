package com.mochanes.emulator;

/**
 * Where the APU sends finished audio.
 *
 * <p>Samples arrive as signed 16-bit little-endian mono at 44.1kHz.
 *
 * <p>Depending on this rather than on {@code javax.sound} keeps the emulation
 * core free of any platform audio API, so the same APU can drive a desktop
 * mixer line, a Web Audio node, or nothing at all.
 *
 * <p><b>Pacing:</b> the emulation thread paces itself by blocking in
 * {@link #write}. A sink that never blocks - {@link #SILENT}, or a browser
 * sink that queues asynchronously - leaves the emulator with no frame timing
 * of its own, and the caller must supply it.
 */
public interface AudioSink {

    /** Writes finished samples, blocking while the device is busy. */
    void write(byte[] samples, int offset, int length);

    /** Releases the underlying device. Must tolerate being called twice. */
    void close();

    /** Discards everything written. Never blocks, so it provides no pacing. */
    AudioSink SILENT = new AudioSink() {
        @Override
        public void write(byte[] samples, int offset, int length) {
            // Deliberately empty.
        }

        @Override
        public void close() {
            // Nothing to release.
        }
    };
}
