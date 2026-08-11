package com.mochanes.emulator;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.IOException;

public class FastCloneTest {

    @Test
    public void testFastCloneCorrectness() throws IOException {
        // 1. Setup
        // Create Dummy ROM
        byte[] dummyRom = new byte[16 + 32768 + 8192]; // Header + PRG + CHR
        dummyRom[0] = 'N';
        dummyRom[1] = 'E';
        dummyRom[2] = 'S';
        dummyRom[3] = 0x1A;
        dummyRom[4] = 2; // 2x16KB PRG
        dummyRom[5] = 1; // 1x8KB CHR
        java.nio.file.Files.write(java.nio.file.Paths.get("fastclone_test.nes"), dummyRom);

        NES source = new NES(null);
        source.loadROM("fastclone_test.nes");

        // Mutate State
        source.getCpu().reset();
        for (int i = 0; i < 100; i++)
            source.getCpu().executeNextInstruction();
        source.getMemory().write(0x0000, 0x42); // RAM mutation

        // 2. Clone using Fast Strategy (Target must be pre-allocated)
        NES target = new NES(null);
        target.loadROM("fastclone_test.nes"); // Init structure

        // ** THE CLONE **
        source.fastCloneTo(target);

        // 3. Verify
        assertEquals("PC should match", source.getCpu().PC, target.getCpu().PC);
        assertEquals("Cycles should match", source.getCpu().getTotalCycles(), target.getCpu().getTotalCycles());
        assertEquals("RAM should match", 0x42, target.getMemory().read(0x0000));

        // 4. Verify Independence
        source.getMemory().write(0x0000, 0xFF);
        assertEquals("Target should be independent", 0x42, target.getMemory().read(0x0000));

        java.nio.file.Files.delete(java.nio.file.Paths.get("fastclone_test.nes"));
    }

    @Test
    public void benchmarkFastClone() throws IOException {
        // Create Dummy ROM
        byte[] dummyRom = new byte[16 + 32768 + 8192];
        dummyRom[0] = 'N';
        dummyRom[1] = 'E';
        dummyRom[2] = 'S';
        dummyRom[3] = 0x1A;
        dummyRom[4] = 2;
        dummyRom[5] = 1;
        java.nio.file.Files.write(java.nio.file.Paths.get("bench.nes"), dummyRom);

        NES source = new NES(null);
        source.loadROM("bench.nes");

        NES target = new NES(null);
        target.loadROM("bench.nes");

        int iterations = 10000;

        // Warmup
        for (int i = 0; i < 1000; i++)
            source.fastCloneTo(target);

        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            source.fastCloneTo(target);
        }
        long duration = System.nanoTime() - start;

        System.out.println("FastClone x " + iterations + ": " + (duration / 1_000_000.0) + " ms");
        System.out.println("Average: " + (duration / (double) iterations) + " ns");

        java.nio.file.Files.delete(java.nio.file.Paths.get("bench.nes"));
    }
}
