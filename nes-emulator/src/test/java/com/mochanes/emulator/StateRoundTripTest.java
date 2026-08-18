package com.mochanes.emulator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;

/**
 * A saved state must reload into a machine that carries on identically.
 *
 * <p>This is what makes a state worth sharing: if the reloaded machine drifts,
 * a state captured at an interesting moment cannot be used to study it. The
 * check runs the same number of frames from both and compares the pictures,
 * which catches anything left out of the snapshot - a latch, a mapper register,
 * a scroll position - because any of them changes what gets drawn.
 */
public class StateRoundTripTest {

    private static final class Capture implements FrameSink {
        final int[] pixels = new int[256 * 240];
        int frames;

        @Override
        public void setPixel(int x, int y, int color) {
            if (x >= 0 && x < 256 && y >= 0 && y < 240) {
                pixels[y * 256 + x] = color;
            }
        }

        @Override
        public void refresh() {
            frames++;
        }
    }

    private static void run(NES nes, Capture capture, int frames) {
        int target = capture.frames + frames;
        int guard = 20_000_000;
        while (capture.frames < target && guard-- > 0) {
            nes.stepInstruction();
        }
        assertTrue("emulator stalled", guard > 0);
    }

    private void roundTrip(byte[] rom, String label) throws Exception {
        Capture liveCapture = new Capture();
        NES live = new NES(liveCapture, AudioSink.SILENT);
        live.loadROM(rom);
        live.reset();
        run(live, liveCapture, 30);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            live.saveState(out);
        }

        run(live, liveCapture, 20);
        int[] expected = liveCapture.pixels.clone();

        Capture restoredCapture = new Capture();
        NES restored = new NES(restoredCapture, AudioSink.SILENT);
        restored.loadROM(rom);
        restored.reset();
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            restored.loadState(in);
        }
        run(restored, restoredCapture, 20);

        assertArrayEquals(label + ": the reloaded machine drew a different picture",
                expected, restoredCapture.pixels);
    }

    @Test
    public void stateReloadsIdenticallyOnAnAssembledRom() throws Exception {
        roundTrip(RomBuilderRoms.scrollingBackground(), "assembled ROM");
    }

    /** The mapper these bugs live in, when its ROM is present. */
    @Test
    public void stateReloadsIdenticallyOnMmc3() throws Exception {
        File rom = TestRomRunner.findRom("roms/MMC3/kirby.nes");
        Assume.assumeTrue("MMC3 ROM not present", rom != null);
        roundTrip(java.nio.file.Files.readAllBytes(rom.toPath()), "MMC3");
    }
}
