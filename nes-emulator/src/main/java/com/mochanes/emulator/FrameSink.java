package com.mochanes.emulator;

/**
 * Where the PPU puts the picture it draws.
 *
 * <p>The PPU needs exactly two things from a display: somewhere to put a pixel,
 * and a signal that the frame is complete. Depending on this interface rather
 * than on the Swing front-end keeps the emulation core free of any UI or
 * platform dependency, so it can be driven by a desktop window, a headless test
 * harness, or a canvas in a browser.
 *
 * <p>Implementations must tolerate being called from the emulation thread.
 */
public interface FrameSink {

    /**
     * Writes one pixel of the current frame.
     *
     * @param x     column, 0-255
     * @param y     scanline, 0-239
     * @param color packed 0xRRGGBB
     */
    void setPixel(int x, int y, int color);

    /** Signals that a frame is complete and can be presented. */
    void refresh();
}
