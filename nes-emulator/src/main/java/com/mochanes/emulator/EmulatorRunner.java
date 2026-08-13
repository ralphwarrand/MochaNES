package com.mochanes.emulator;

public class EmulatorRunner implements Runnable {

    /** The console's own frame rate. Not 60: NTSC is 60.0988Hz. */
    public static final double NES_FPS = 60.0988;

    private final NES nes;
    private volatile boolean running = false;
    private volatile boolean paused = false;
    private Thread thread;

    private volatile long framePeriodNs = (long) (1_000_000_000.0 / NES_FPS);
    /** When the next frame is due, on the monotonic clock. */
    private long nextFrameNs;

    public EmulatorRunner(NES nes) {
        this.nes = nes;
    }

    /**
     * Sets the rate frames are produced at.
     *
     * <p>Supplied by the caller rather than read here, so this class stays free
     * of any windowing API. Passing the display's refresh rate, when it is
     * close to {@link #NES_FPS}, keeps emulated frames in step with the screen:
     * a console frame every refresh, instead of the two rates slipping past one
     * another and losing a frame every few seconds.
     */
    public void setTargetFps(double fps) {
        if (fps > 1.0 && fps < 1000.0) {
            framePeriodNs = (long) (1_000_000_000.0 / fps);
        }
    }

    public void start() {
        if (running)
            return;
        running = true;
        paused = false;
        thread = new Thread(this, "EmulatorThread");
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            if (thread != null)
                thread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    public void step() {
        if (!paused)
            return; // Only step when paused
        // Execute one instruction
        stepCpu();
    }

    @Override
    public void run() {
        while (running) {
            if (paused) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                nextFrameNs = 0; // resync on resume
                continue;
            }

            // Frame Loop
            // Using same logic as Main.java
            CPU cpu = nes.getCpu();
            PPU ppu = nes.getPpu();
            APU apu = nes.getApu();

            if (cpu == null || ppu == null)
                continue;

            // Generate one frame
            while (!ppu.frameComplete && running && !paused) {
                stepSystem(cpu, ppu, apu);
            }

            ppu.frameComplete = false;
            pace();
        }
    }

    /**
     * Holds the thread until the next frame is due.
     *
     * <p>This used to rely on the APU's blocking writes alone, on the reasoning
     * that the sound card sets the pace. It does set the long-run rate, but
     * only once its buffer is full, and it sets nothing at all when the machine
     * has no audio device - {@code JavaSoundSink} falls back to a sink that
     * never blocks, and the loop then runs as fast as the CPU allows. An
     * explicit deadline gives frames an even spacing either way; audio still
     * corrects the rate over the long run, since a full buffer will simply
     * block on top of this.
     */
    private void pace() {
        long now = System.nanoTime();
        if (nextFrameNs == 0) {
            nextFrameNs = now;
        }
        nextFrameNs += framePeriodNs;

        long remaining = nextFrameNs - now;
        if (remaining > 0) {
            java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        } else if (remaining < -4 * framePeriodNs) {
            // Far enough behind that catching up would mean a burst of frames.
            // Give up the debt and start timing again from here.
            nextFrameNs = now;
        }
    }

    private void stepCpu() {
        CPU cpu = nes.getCpu();
        PPU ppu = nes.getPpu();
        APU apu = nes.getApu();
        if (cpu != null) {
            stepSystem(cpu, ppu, apu);
        }
    }

    // Single system step (CPU instruction + PPU/APU clocking)
    private void stepSystem(CPU cpu, PPU ppu, APU apu) {
        nes.stepInstruction();
    }
}
