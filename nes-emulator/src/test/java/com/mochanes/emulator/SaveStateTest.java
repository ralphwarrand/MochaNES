package com.mochanes.emulator;

import com.mochanes.emulator.gui.Display;
import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class SaveStateTest {

    private static class DummyDisplay extends Display {
        public DummyDisplay() {
            super(true);
        }

        @Override
        public void refresh() {
        }

        @Override
        public void setPixel(int x, int y, int color) {
        }
    }

    @Test
    public void testSaveLoadState() throws IOException {
        String romPath = "src/test/resources/nestest.nes"; // Assuming standard test ROM availability
        // If nestest doesn't exist, we can use a dummy 16KB PRG ROM
        // Let's create a temp ROM file for robustness if needed, but usually tests have
        // resources.
        // For this environment, I'll assume we can't easily rely on external files, so
        // I'll create a dummy memory dump?
        // No, NES constructor needs a file. Let's create a temporary ROM.

        File tempRom = File.createTempFile("test_rom", ".nes");
        createDummyRom(tempRom);

        NES nes = new NES(new DummyDisplay());
        nes.loadROM(tempRom.getAbsolutePath());

        // 1. Advance State
        CPU cpu = nes.getCpu();
        // Run some instructions (NOPs mostly if empty ROM)
        for (int i = 0; i < 1000; i++) {
            cpu.executeNextInstruction();
            nes.getPpu().tick();
            nes.getPpu().tick();
            nes.getPpu().tick();
            nes.getApu().tick();
        }

        long cyclesBefore = cpu.getTotalCycles();
        int pcBefore = cpu.PC;
        int ramVal = nes.getMemory().read(0x0010);

        // modify RAM to be sure
        nes.getMemory().write(0x0020, 0x42);

        // 2. Save State
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        nes.saveState(dos);
        dos.close();
        byte[] stateData = baos.toByteArray();

        // 3. Advance further (mess up state)
        for (int i = 0; i < 100; i++) {
            cpu.executeNextInstruction();
        }
        nes.getMemory().write(0x0020, 0x99); // Overwrite

        assertNotEquals(cyclesBefore, cpu.getTotalCycles());
        assertNotEquals(0x42, nes.getMemory().read(0x0020));

        // 4. Load State
        ByteArrayInputStream bais = new ByteArrayInputStream(stateData);
        DataInputStream dis = new DataInputStream(bais);
        nes.loadState(dis);

        // 5. Verify
        assertEquals("Cycles should match", cyclesBefore, cpu.getTotalCycles());
        assertEquals("PC should match", pcBefore, cpu.PC);
        assertEquals("RAM check 1", ramVal, nes.getMemory().read(0x0010));
        assertEquals("RAM check 2", 0x42, nes.getMemory().read(0x0020));

        tempRom.delete();
    }

    private void createDummyRom(File file) throws IOException {
        byte[] header = new byte[] {
                'N', 'E', 'S', 0x1A,
                1, // 1x 16KB PRG
                1, // 1x 8KB CHR
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0
        };
        byte[] prg = new byte[16384];
        // RESET Vector at 0xFFFC
        prg[0x3FFC] = 0x00;
        prg[0x3FFD] = (byte) 0x80; // 0x8000
        byte[] chr = new byte[8192];

        FileOutputStream fos = new FileOutputStream(file);
        fos.write(header);
        fos.write(prg);
        fos.write(chr);
        fos.close();
    }
}
