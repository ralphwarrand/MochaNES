package com.mochanes.emulator;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * Runs the blargg accuracy test ROMs and asserts they pass.
 *
 * The ROM set lives under {@code roms/} which is gitignored, so each case is
 * skipped (not failed) when its ROM is missing - a fresh clone or CI checkout
 * will not have them.
 *
 * Only suites the emulator currently passes are listed here, so a regression
 * shows up as a failure. Suites that are known to fail are recorded in
 * {@link #KNOWN_FAILING} rather than being silently dropped.
 */
@RunWith(Parameterized.class)
public class BlarggTestRomTest {

    /**
     * Cases that do not pass yet, kept here as documentation.
     *
     * ppu_vbl_nmi and mmc3 4-scanline_timing need cycle-accurate PPU timing: the
     * emulator runs a whole CPU instruction and then catches the PPU up, so
     * sub-instruction NMI timing and exact IRQ placement within a scanline are
     * not representable. instr_timing needs exact per-instruction cycle counts.
     *
     * mmc3 6-MMC3_alt is excluded by design, not as a defect: it and 5-MMC3 test
     * the two mutually exclusive MMC3 revisions, and this emulator implements the
     * rev B behaviour that 5-MMC3 checks.
     */
    static final List<String> KNOWN_FAILING = Arrays.asList(
            // Need the VBlank flag sampled at an exact dot within the CPU's read
            // cycle. The clock is now bus-driven and instruction timing is exact
            // (verified by instr_timing), but the PPU is still stepped in whole
            // 3-dot groups per CPU cycle, so a read cannot land mid-cycle.
            "roms/test/ppu_vbl_nmi/rom_singles/  (except 04-nmi_control)",
            "roms/test/cpu_interrupts_v2/rom_singles/  (except 1-cli_latency)",
            "roms/test/mmc3_test_2/rom_singles/4-scanline_timing.nes",
            "roms/test/mmc3_test_2/rom_singles/6-MMC3_alt.nes");

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> roms() {
        List<Object[]> cases = new ArrayList<>();

        String[] instrTests = {
                "01-basics", "02-implied", "03-immediate", "04-zero_page", "05-zp_xy",
                "06-absolute", "07-abs_xy", "08-ind_x", "09-ind_y", "10-branches",
                "11-stack", "12-jmp_jsr", "13-rts", "14-rti", "15-brk", "16-special"
        };
        for (String t : instrTests) {
            cases.add(new Object[] { "instr_test-v5/" + t,
                    "roms/test/instr_test-v5/rom_singles/" + t + ".nes", 2400 });
        }

        String[] miscTests = {
                "01-abs_x_wrap", "02-branch_wrap", "03-dummy_reads", "04-dummy_reads_apu"
        };
        for (String t : miscTests) {
            cases.add(new Object[] { "instr_misc/" + t,
                    "roms/test/instr_misc/rom_singles/" + t + ".nes", 2400 });
        }

        cases.add(new Object[] { "instr_timing/2-branch_timing",
                "roms/test/instr_timing/rom_singles/2-branch_timing.nes", 2400 });
        // Exact per-instruction cycle counts. Slow: it sweeps every opcode.
        cases.add(new Object[] { "instr_timing/1-instr_timing",
                "roms/test/instr_timing/rom_singles/1-instr_timing.nes", 6000 });

        // MMC3 mapper and scanline-IRQ behaviour.
        String[] mmc3Tests = { "1-clocking", "2-details", "3-A12_clocking", "5-MMC3" };
        for (String t : mmc3Tests) {
            cases.add(new Object[] { "mmc3_test_2/" + t,
                    "roms/test/mmc3_test_2/rom_singles/" + t + ".nes", 2400 });
        }

        // PPU open-bus decay and executing from I/O space.
        cases.add(new Object[] { "ppu_open_bus",
                "roms/test/ppu_open_bus/ppu_open_bus.nes", 4000 });
        cases.add(new Object[] { "cpu_exec_space/ppuio",
                "roms/test/cpu_exec_space/test_cpu_exec_space_ppuio.nes", 3000 });
        cases.add(new Object[] { "cpu_exec_space/apu",
                "roms/test/cpu_exec_space/test_cpu_exec_space_apu.nes", 3000 });
        cases.add(new Object[] { "cpu_dummy_writes/oam",
                "roms/test/cpu_dummy_writes/cpu_dummy_writes_oam.nes", 3000 });
        cases.add(new Object[] { "oam_read",
                "roms/test/oam_read/oam_read.nes", 3000 });
        cases.add(new Object[] { "oam_stress",
                "roms/test/oam_stress/oam_stress.nes", 6000 });
        // Exercises $2007 buffering across every VRAM region. Slow but thorough.
        cases.add(new Object[] { "ppu_read_buffer",
                "roms/test/ppu_read_buffer/test_ppu_read_buffer.nes", 20000 });

        // Interrupt-disable latency: CLI/SEI/PLP take effect one instruction late.
        cases.add(new Object[] { "cpu_interrupts/1-cli_latency",
                "roms/test/cpu_interrupts_v2/rom_singles/1-cli_latency.nes", 4000 });

        // APU length counter and IRQ flag basics.
        String[] apuTests = { "1-len_ctr", "2-len_table", "3-irq_flag" };
        for (String t : apuTests) {
            cases.add(new Object[] { "apu_test/" + t,
                    "roms/test/apu_test/rom_singles/" + t + ".nes", 3000 });
        }

        return cases;
    }

    private final String name;
    private final String romPath;
    private final int maxFrames;

    public BlarggTestRomTest(String name, String romPath, int maxFrames) {
        this.name = name;
        this.romPath = romPath;
        this.maxFrames = maxFrames;
    }

    @Test
    public void passesAccuracyTest() throws Exception {
        File rom = TestRomRunner.findRom(romPath);
        Assume.assumeTrue("test ROM not present: " + romPath, rom != null);

        TestRomRunner.Result result = TestRomRunner.run(rom, maxFrames);
        assertTrue(name + ": " + result, result.passed());
    }
}
