
package com.mochanes.ai;

import com.mochanes.emulator.NES;
import com.mochanes.emulator.gui.Display;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import com.mochanes.emulator.performance.Metrics;

public class HeadlessNES {
    private NES nes;
    private final HeadlessDisplay display;

    public HeadlessNES(String romPath) {
        this.display = new HeadlessDisplay();
        this.nes = new NES(display);
        // Enable Fast PPU Mode (Direct Buffer Access)
        this.nes.getPpu().setFastRendering(display.getBuffer());

        try {
            this.nes.loadROM(romPath);
            // Mute APU for headless execution
            this.nes.getApu().setMuted(true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load ROM for Ghost", e);
        }
    }

    public void runFrame() {
        // Run enough cycles for one frame (~29780 CPU cycles)
        // Or reuse EmulatorRunner logic if accessible, but simple loop is fine for AI
        int cycles = 0;
        int maxCycles = 500000; // Safety limit (increased to allow full frame)
        // Frame is approx 89342 PPU cycles / 3 = 29780 CPU cycles
        long lastTotalCycles = nes.getCpu().getTotalCycles();

        // Reset Frame Complete flag to ensure we run a full frame
        nes.getPpu().frameComplete = false;

        while (!nes.getPpu().frameComplete) {
            long startCPU = Metrics.start();
            nes.getCpu().executeNextInstruction();
            Metrics.measure("NES.CPU.Step", startCPU);

            // Wiring: NMI/IRQ (Crucial for games like Zelda)
            if (!nes.getCpu().isDmaActive()) {
                nes.getCpu().setNMI(nes.getPpu().nmiOccurred);
            }

            // Enable APU IRQ for determinism with ReplayRunner
            if (nes.getApu().irqActive && !nes.getCpu().isDmaActive()) {
                nes.getCpu().irq();
            }

            long currentTotalCycles = nes.getCpu().getTotalCycles();
            long cyclesToRun = currentTotalCycles - lastTotalCycles;
            lastTotalCycles = currentTotalCycles;

            long startPPU = Metrics.start();
            nes.getPpu().step((int) (cyclesToRun * 3));
            Metrics.measure("NES.PPU.Catchup", startPPU);

            nes.getApu().tick((int) cyclesToRun);

            if (nes.getPpu().frameComplete)
                break;

            cycles += cyclesToRun;

            if (cycles > maxCycles) {
                System.err.println("HeadlessNES Warning: Frame timeout reached (" + cycles
                        + " cycles). Forcing frame completion.");
                break;
            }
        }
        // display.resetFrameReady(); // No longer needed as we use PPU flag
    }

    public void saveState(java.io.File file) throws java.io.IOException {
        try (java.io.DataOutputStream dos = new java.io.DataOutputStream(new java.io.FileOutputStream(file))) {
            nes.saveState(dos);
        }
    }

    public void loadState(java.io.File file) throws java.io.IOException {
        try (java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.FileInputStream(file))) {
            nes.loadState(dis);
        }
    }

    public int[] getScreenBuffer() {
        return display.getBuffer();
    }

    public int readCpuRam(int address) {
        return nes.getMemory().read(address);
    }

    public void setRenderingEnabled(boolean enabled) {
        nes.getPpu().renderingEnabled = enabled;
    }

    public void setControllerState(int buttons) {
        // Map integer bitfield to Controller
        // 0: A, 1: B, 2: Select, 3: Start, 4: Up, 5: Down, 6: Left, 7: Right
        nes.getController().setButtonPressed(0, (buttons & 1) != 0); // A
        nes.getController().setButtonPressed(1, (buttons & 2) != 0); // B
        nes.getController().setButtonPressed(2, (buttons & 4) != 0); // Select
        nes.getController().setButtonPressed(3, (buttons & 8) != 0); // Start
        nes.getController().setButtonPressed(4, (buttons & 16) != 0); // Up
        nes.getController().setButtonPressed(5, (buttons & 32) != 0); // Down
        nes.getController().setButtonPressed(6, (buttons & 64) != 0); // Left
        nes.getController().setButtonPressed(7, (buttons & 128) != 0); // Right
    }

    private static class HeadlessDisplay extends Display {
        private boolean frameReady = false;

        public HeadlessDisplay() {
            super(true); // Headless mode
        }

        @Override
        public void refresh() {
            frameReady = true;
            // No repaint needed
        }

        public boolean isFrameReady() {
            return frameReady;
        }

        public void resetFrameReady() {
            frameReady = false;
        }

        // We need to get the buffer. Display stores it in 'pixels'.
        public int[] getBuffer() {
            return getPixels();
        }
    }

    public HeadlessNES copy() {
        // Create new blank instance
        HeadlessNES newGhost = new HeadlessNES(); // Private scaffold constructor
        newGhost.nes = this.nes.copy(newGhost.display);

        // RE-ENABLE Fast PPU on the copied NES (since NES.copy creates new PPU)
        newGhost.nes.getPpu().setFastRendering(newGhost.display.getBuffer());

        // Copy Display Buffer
        int[] src = this.display.getPixels();
        int[] dst = newGhost.display.getPixels();
        System.arraycopy(src, 0, dst, 0, src.length);

        return newGhost;
    }

    private HeadlessNES() {
        this.display = new HeadlessDisplay();
        // Placeholder, will be overwritten
        this.nes = null;
    }

    public void loadState(HeadlessNES source) {
        // Ensure we have a valid NES instance structure
        // Since we reuse instances, this.nes should clearly be initialized
        // But for safety/first-time use in pool:
        if (this.nes == null) {
            // If we are an empty shell, we must behave like a copy first
            // But loadState assumes structural equivalence.
            // The pool factory should ensure structure.
            // For now, if null, we panic or do full copy.
            // Let's assume initialized.
            throw new RuntimeException("Target HeadlessNES not initialized for loadState");
        }

        // 1. Copy NES State
        this.nes.loadState(source.nes); // Zero Allocation Logic

        // 2. Copy Display Buffer (Fast Array Copy)
        int[] src = source.display.getPixels();
        int[] dst = this.display.getPixels();
        if (src.length != dst.length) {
            // Should not happen for same resolution
            System.err.println("Display buffer mismatch during loadState");
        } else {
            System.arraycopy(src, 0, dst, 0, src.length);
        }
    }
}
