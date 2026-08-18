package com.mochanes.emulator;

import static org.junit.Assert.assertEquals;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * Sprite 0 hit and sprite overflow, from the 2005 test ROMs.
 *
 * <p>These were sitting unused in {@code roms/test} because they predate the
 * $6000 result protocol the rest of the suite uses: they report on screen and
 * leave a code in zero page $F8. {@link TestRomRunner#runLegacy} reads that,
 * which is all they needed to become regression cover.
 *
 * <p>Sprite 0 hit is what games use to split the screen, so it decides whether
 * a status bar lands on the right scanline.
 */
@RunWith(Parameterized.class)
public class SpriteAccuracyTest {

    /**
     * Not passing yet, with the failure each one reports.
     *
     * <p>{@code overflow/2.Details} code 5 - the flag should be set when a
     * sprite's Y coordinate is 239. {@code overflow/3.Timing} code 5 - the flag
     * is set too late for the first scanline. {@code overflow/4.Obscure} code 2
     * - the hardware's overflow bug, where eight sprites on a line followed by
     * one that is not makes the PPU read the wrong byte of each later sprite as
     * its Y coordinate. Evaluation here is the straightforward version, which
     * finds the same sprites but not the same false positives.
     */
    static final List<String> KNOWN_FAILING = Arrays.asList(
            "sprite_overflow_tests/2.Details",
            "sprite_overflow_tests/3.Timing",
            "sprite_overflow_tests/4.Obscure");

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> roms() {
        List<Object[]> cases = new ArrayList<>();
        String[] hit = { "01.basics", "02.alignment", "03.corners", "04.flip", "05.left_clip",
                "06.right_edge", "07.screen_bottom", "08.double_height", "09.timing_basics",
                "10.timing_order", "11.edge_timing" };
        for (String t : hit) {
            cases.add(new Object[] { "sprite_hit/" + t,
                    "roms/test/sprite_hit_tests_2005.10.05/" + t + ".nes" });
        }
        for (String t : new String[] { "1.Basics", "5.Emulator" }) {
            cases.add(new Object[] { "sprite_overflow/" + t,
                    "roms/test/sprite_overflow_tests/" + t + ".nes" });
        }
        return cases;
    }

    private final String name;
    private final String romPath;

    public SpriteAccuracyTest(String name, String romPath) {
        this.name = name;
        this.romPath = romPath;
    }

    @Test
    public void reportsAPass() throws Exception {
        File rom = TestRomRunner.findRom(romPath);
        Assume.assumeTrue("test ROM not present: " + romPath, rom != null);
        int code = TestRomRunner.runLegacy(rom, 240);
        assertEquals(name + ": ROM reported failure code " + code
                + " (see its readme for what that number means)", 1, code);
    }
}
