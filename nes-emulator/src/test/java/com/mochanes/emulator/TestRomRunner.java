package com.mochanes.emulator;

import com.mochanes.emulator.gui.Display;

import java.io.File;
import java.io.IOException;

/**
 * Runs a blargg-style NES test ROM headlessly and reports its result.
 *
 * These ROMs communicate through PRG-RAM: {@code $6001-$6003} holds the
 * signature {@code DE B0 61} once output is valid, {@code $6000} holds the
 * status ({@code 0x80} while running, {@code 0x81} when the ROM wants a reset,
 * otherwise the final result code where 0 means pass), and {@code $6004}
 * onwards is a NUL-terminated ASCII message.
 */
public final class TestRomRunner {

    /** NTSC CPU cycles per frame, used to bound how long a ROM may run. */
    private static final long CYCLES_PER_FRAME = 29781;

    private static final int STATUS_RUNNING = 0x80;
    private static final int STATUS_NEEDS_RESET = 0x81;

    private TestRomRunner() {
    }

    /** Outcome of a test ROM run. */
    public static final class Result {
        public final int status;
        public final String message;
        public final boolean completed;

        Result(int status, String message, boolean completed) {
            this.status = status;
            this.message = message;
            this.completed = completed;
        }

        public boolean passed() {
            return completed && status == 0;
        }

        @Override
        public String toString() {
            if (!completed) {
                return "did not finish (no valid result signature); last message: \"" + message + "\"";
            }
            return (status == 0 ? "PASS" : "FAIL status=" + status) + " \"" + message + "\"";
        }
    }

    /**
     * Locates a ROM under the repository's {@code roms/} directory. Tests run
     * with the module as the working directory, but the ROMs live at the repo
     * root, so both layouts are checked. Returns null when absent - the ROM set
     * is gitignored and will not exist in a fresh clone or in CI.
     */
    public static File findRom(String relativePath) {
        File[] candidates = {
                new File(relativePath),
                new File("../" + relativePath),
                new File("../../" + relativePath)
        };
        for (File f : candidates) {
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    public static Result run(File rom, int maxFrames) throws IOException {
        Display display = new Display(true);
        NES nes = new NES(display);
        nes.loadROM(rom.getPath());
        nes.reset();
        nes.getApu().setMuted(true);

        Memory mem = nes.getMemory();
        CPU cpu = nes.getCpu();

        int status = -1;
        boolean sawSignature = false;

        for (int frame = 0; frame < maxFrames; frame++) {
            long target = cpu.getTotalCycles() + CYCLES_PER_FRAME;
            while (cpu.getTotalCycles() < target) {
                nes.stepInstruction();
            }

            if (mem.peek(0x6001) == 0xDE && mem.peek(0x6002) == 0xB0 && mem.peek(0x6003) == 0x61) {
                sawSignature = true;
                status = mem.peek(0x6000);
                if (status == STATUS_NEEDS_RESET) {
                    // The ROM asks for a soft reset to continue; honour it.
                    nes.reset();
                } else if (status < STATUS_RUNNING) {
                    return new Result(status, readMessage(mem), true);
                }
            }
        }

        return new Result(status, sawSignature ? readMessage(mem) : "", false);
    }

    private static String readMessage(Memory mem) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 512; i++) {
            int c = mem.peek(0x6004 + i);
            if (c == 0) {
                break;
            }
            sb.append((char) c);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
