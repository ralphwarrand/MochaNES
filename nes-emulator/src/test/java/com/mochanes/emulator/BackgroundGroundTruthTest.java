package com.mochanes.emulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Checks the background against an independently computed picture.
 *
 * <p>The scroll invariants next door compare one frame with the next shifted by
 * a pixel. That catches anything which varies with scroll phase, but it is
 * blind to a fault that is the same in both frames - an attribute applied to
 * the wrong tile looks identical before and after a one-pixel shift, so it
 * passes. This test instead works out what each pixel should be from the
 * nametable, the attribute table, the pattern data and the scroll, and compares
 * that with what the PPU drew, so a constant misalignment has nowhere to hide.
 *
 * <p>The inputs are read back out of the PPU rather than predicted from the ROM
 * source: what is under test is how they are combined into pixels, not what the
 * test ROM put in them.
 */
public class BackgroundGroundTruthTest {

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

    /** Fills CHR, a nametable and the palette, then holds a fixed scroll. */
    private static byte[] staticBackgroundRom(int scrollX) {
        RomBuilder a = new RomBuilder();
        a.sei().cld().ldxImm(0xFF).txs();
        a.label("warm1").bitAbs(0x2002).bpl("warm1");
        a.label("warm2").bitAbs(0x2002).bpl("warm2");

        a.ldaImm(0x3F).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00);
        a.label("pal").txa().adcImm(0x01).staAbs(0x2007).inx().cpxImm(0x20).bne("pal");

        a.ldaImm(0x00).staAbs(0x2006).staAbs(0x2006);
        a.ldxImm(0x00).ldyImm(0x00);
        a.label("chr").tya().adcImm(0x07).staAbs(0x2007).iny().bne("chr");
        a.inx().cpxImm(0x20).bne("chr");

        // 1KB: 960 tile bytes then 64 attribute bytes, all varying.
        a.ldaImm(0x20).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00).ldyImm(0x00);
        a.label("nt").tya().staAbs(0x2007).iny().bne("nt");
        a.inx().cpxImm(0x04).bne("nt");

        a.ldaImm(0x00).staAbs(0x2000);   // no NMI, pattern table 0
        a.ldaImm(0x0A).staAbs(0x2001);   // background only, left column shown

        a.label("loop");
        a.label("vbl").bitAbs(0x2002).bpl("vbl");
        a.ldaImm(scrollX).staAbs(0x2005).ldaImm(0x00).staAbs(0x2005);
        a.jmp("loop");

        return a.build(false); // horizontal mirroring
    }

    /**
     * Two distinct pages under vertical mirroring, which is the arrangement a
     * horizontally scrolling game uses. With horizontal mirroring the same page
     * is shown twice, so picking the wrong one cannot be seen.
     */
    private static byte[] twoPageRom(int scrollX, int scrollY) {
        RomBuilder a = new RomBuilder();
        a.sei().cld().ldxImm(0xFF).txs();
        a.label("warm1").bitAbs(0x2002).bpl("warm1");
        a.label("warm2").bitAbs(0x2002).bpl("warm2");

        a.ldaImm(0x3F).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00);
        a.label("pal").txa().adcImm(0x01).staAbs(0x2007).inx().cpxImm(0x20).bne("pal");

        a.ldaImm(0x00).staAbs(0x2006).staAbs(0x2006);
        a.ldxImm(0x00).ldyImm(0x00);
        a.label("chr").tya().adcImm(0x07).staAbs(0x2007).iny().bne("chr");
        a.inx().cpxImm(0x20).bne("chr");

        // $2000: tile = y. $2400: tile = y AND 0x5A, so both the tiles and the
        // attribute bytes at the end of each page differ between the two.
        a.ldaImm(0x20).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00).ldyImm(0x00);
        a.label("nt0").tya().staAbs(0x2007).iny().bne("nt0");
        a.inx().cpxImm(0x04).bne("nt0");

        a.ldaImm(0x24).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00).ldyImm(0x00);
        a.label("nt1").tya().andImm(0x5A).staAbs(0x2007).iny().bne("nt1");
        a.inx().cpxImm(0x04).bne("nt1");

        // $2005 only carries the low eight bits of the scroll; which page the
        // screen starts on is the base-nametable bit in $2000.
        a.ldaImm((scrollX >= 256) ? 0x01 : 0x00).staAbs(0x2000);
        a.ldaImm(0x0A).staAbs(0x2001);

        a.label("loop");
        a.label("vbl").bitAbs(0x2002).bpl("vbl");
        a.ldaImm(scrollX & 0xFF).staAbs(0x2005).ldaImm(scrollY).staAbs(0x2005);
        a.jmp("loop");

        return a.build(true); // vertical mirroring: the two pages differ
    }

    /**
     * Sets one scroll at the top of the frame and a different one part-way
     * down, the way a game splits its status bar from its playfield.
     *
     * <p>The delay is a plain counting loop rather than a sprite-0 hit or an
     * IRQ: where the split lands does not need to be known, only that it lands
     * somewhere in the middle, because the check below asks each row to match
     * one scroll or the other rather than asking for the split at a given line.
     */
    private static byte[] midFrameSplitRom(int scrollTop, int scrollBottom, int delayOuter) {
        RomBuilder a = new RomBuilder();
        a.sei().cld().ldxImm(0xFF).txs();
        a.label("warm1").bitAbs(0x2002).bpl("warm1");
        a.label("warm2").bitAbs(0x2002).bpl("warm2");

        a.ldaImm(0x3F).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00);
        a.label("pal").txa().adcImm(0x01).staAbs(0x2007).inx().cpxImm(0x20).bne("pal");

        a.ldaImm(0x00).staAbs(0x2006).staAbs(0x2006);
        a.ldxImm(0x00).ldyImm(0x00);
        a.label("chr").tya().adcImm(0x07).staAbs(0x2007).iny().bne("chr");
        a.inx().cpxImm(0x20).bne("chr");

        a.ldaImm(0x20).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00).ldyImm(0x00);
        a.label("nt0").tya().staAbs(0x2007).iny().bne("nt0");
        a.inx().cpxImm(0x04).bne("nt0");

        a.ldaImm(0x24).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00).ldyImm(0x00);
        a.label("nt1").tya().andImm(0x5A).staAbs(0x2007).iny().bne("nt1");
        a.inx().cpxImm(0x04).bne("nt1");

        a.ldaImm(0x00).staAbs(0x2000);
        a.ldaImm(0x0A).staAbs(0x2001);

        a.label("loop");
        a.label("vbl").bitAbs(0x2002).bpl("vbl");
        a.ldaImm(scrollTop & 0xFF).staAbs(0x2005).ldaImm(0x00).staAbs(0x2005);

        // Count into the middle of the picture, then change the scroll.
        a.ldyImm(0x00);
        a.label("outer");
        a.ldxImm(0x00);
        a.label("inner").inx().cpxImm(0x00).bne("inner");
        a.iny().cpyImm(delayOuter).bne("outer");

        a.ldaImm(scrollBottom & 0xFF).staAbs(0x2005).ldaImm(0x00).staAbs(0x2005);
        a.jmp("loop");

        return a.build(true);
    }

    /** Two 8KB CHR banks whose tiles differ, so a wrong bank is visible. */
    private static byte[] twoChrBanks() {
        byte[] chr = new byte[16384];
        for (int i = 0; i < 8192; i++) {
            chr[i] = (byte) (i * 7 + (i >> 4));            // bank 0
            chr[8192 + i] = (byte) ~(i * 5 + (i >> 3));    // bank 1
        }
        return chr;
    }

    /**
     * CNROM board that swaps CHR bank part-way down the frame.
     *
     * <p>The nearest stand-in for what an MMC3 game does at its status bar:
     * the pattern data under the bottom of the picture is not the data under
     * the top. Any error in when the switch takes effect shows up as a band of
     * rows drawn from the wrong bank.
     */
    private static byte[] midFrameChrRom(int delayOuter) {
        RomBuilder a = new RomBuilder();
        a.sei().cld().ldxImm(0xFF).txs();
        a.label("warm1").bitAbs(0x2002).bpl("warm1");
        a.label("warm2").bitAbs(0x2002).bpl("warm2");

        a.ldaImm(0x3F).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00);
        a.label("pal").txa().adcImm(0x01).staAbs(0x2007).inx().cpxImm(0x20).bne("pal");

        // CHR is ROM here, so only the nametable needs filling.
        a.ldaImm(0x20).staAbs(0x2006).ldaImm(0x00).staAbs(0x2006);
        a.ldxImm(0x00).ldyImm(0x00);
        a.label("nt0").tya().staAbs(0x2007).iny().bne("nt0");
        a.inx().cpxImm(0x04).bne("nt0");

        a.ldaImm(0x00).staAbs(0x2000);
        a.ldaImm(0x0A).staAbs(0x2001);

        a.label("loop");
        a.label("vbl").bitAbs(0x2002).bpl("vbl");
        a.ldaImm(0x00).staAbs(0x8000);   // top of the frame uses bank 0
        a.ldaImm(0x00).staAbs(0x2005).ldaImm(0x00).staAbs(0x2005);

        a.ldyImm(0x00);
        a.label("outer");
        a.ldxImm(0x00);
        a.label("inner").inx().cpxImm(0x00).bne("inner");
        a.iny().cpyImm(delayOuter).bne("outer");

        a.ldaImm(0x01).staAbs(0x8000);   // and the bottom uses bank 1
        a.jmp("loop");

        return a.build(false, 3, twoChrBanks());
    }

    /** As {@link #expectedPixel}, but with pattern data taken from {@code chr}. */
    private static int expectedPixelFromBank(PPU ppu, byte[] chr, int bank, int px, int py) {
        int tx = px >> 3;
        int ty = py >> 3;
        int tile = ppu.readVram(0x2000 + ty * 32 + tx);
        int attr = ppu.readVram(0x2000 + 0x3C0 + (ty >> 2) * 8 + (tx >> 2));
        int shift = ((ty & 0x02) << 1) | (tx & 0x02);
        int palette = (attr >> shift) & 0x03;

        int base = bank * 8192 + tile * 16 + (py & 7);
        int lo = chr[base] & 0xFF;
        int hi = chr[base + 8] & 0xFF;
        int bit = 7 - (px & 7);
        int value = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);

        int index = value == 0 ? ppu.readVram(0x3F00)
                : ppu.readVram(0x3F00 + palette * 4 + value);
        return PPU.PALETTE_LOOKUP[index & 0x3F] & 0xFFFFFF;
    }

    private static int expectedPixel(PPU ppu, int scrollX, int px, int py) {
        return expectedPixel(ppu, scrollX, 0, px, py);
    }

    private static int expectedPixel(PPU ppu, int scrollX, int scrollY, int px, int py) {
        int nx = (scrollX + px) & 0x1FF;
        // The vertical nametable space is 480 rows: 30 tile rows per page.
        int ny = (scrollY + py) % 480;
        int ntBase = 0x2000 + ((nx & 0x100) != 0 ? 0x400 : 0) + (ny >= 240 ? 0x800 : 0);
        int tx = (nx & 0xFF) >> 3;
        int ty = (ny % 240) >> 3;

        int tile = ppu.readVram(ntBase + ty * 32 + tx);
        int attr = ppu.readVram(ntBase + 0x3C0 + (ty >> 2) * 8 + (tx >> 2));
        int shift = ((ty & 0x02) << 1) | (tx & 0x02);
        int palette = (attr >> shift) & 0x03;

        int row = (ny % 240) & 7;
        int lo = ppu.readVram(tile * 16 + row);
        int hi = ppu.readVram(tile * 16 + row + 8);
        int bit = 7 - (nx & 7);
        int value = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);

        int index = value == 0 ? ppu.readVram(0x3F00)
                : ppu.readVram(0x3F00 + palette * 4 + value);
        return PPU.PALETTE_LOOKUP[index & 0x3F] & 0xFFFFFF;
    }

    private void checkScroll(int scrollX) throws Exception {
        check(staticBackgroundRom(scrollX), scrollX, 0, "one page");
    }

    private void checkTwoPage(int scrollX, int scrollY) throws Exception {
        check(twoPageRom(scrollX, scrollY), scrollX, scrollY, "two pages");
    }

    private void check(byte[] rom, int scrollX, int scrollY, String what) throws Exception {
        Capture capture = new Capture();
        NES nes = new NES(capture, AudioSink.SILENT);
        nes.loadROM(rom);
        nes.reset();

        int guard = 12_000_000;
        // Well past setup: the ROM writes 8KB of CHR and 2KB of nametable
        // before it enables rendering, which takes several frames.
        while (capture.frames < 40 && guard-- > 0) {
            nes.stepInstruction();
        }

        PPU ppu = nes.getPpu();
        int wrong = 0;
        int firstX = -1, firstY = -1;
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 256; x++) {
                int want = expectedPixel(ppu, scrollX, scrollY, x, y);
                if ((capture.pixels[y * 256 + x] & 0xFFFFFF) != want) {
                    if (wrong == 0) {
                        firstX = x;
                        firstY = y;
                    }
                    wrong++;
                }
            }
        }
        assertEquals(what + ", scroll x=" + scrollX + " y=" + scrollY + ": " + wrong
                + " pixels differ from the expected background, first at x=" + firstX
                + " y=" + firstY, 0, wrong);
    }

    @Test
    public void backgroundMatchesItsNametableAndAttributes() throws Exception {
        // Every fine-X phase, plus offsets that put a tile and an attribute
        // boundary in different places relative to the screen edge.
        for (int scroll : new int[] { 0, 1, 3, 7, 8, 15, 16, 17, 31, 32, 33, 64, 130 }) {
            checkScroll(scroll);
        }
    }

    /**
     * A scroll changed part-way down the frame must leave every row internally
     * consistent.
     *
     * <p>This is the arrangement the games use for a status bar, and it is the
     * one case the tests above cannot reach: they hold the scroll still for a
     * whole frame. A row that took its tiles from one scroll position and its
     * attributes from another - the reported symptom, a shape half in the
     * wrong palette - matches neither expectation and fails here.
     */
    @Test
    public void everyRowIsConsistentAcrossAMidFrameScrollChange() throws Exception {
        int top = 0, bottom = 152;

        Capture capture = new Capture();
        NES nes = new NES(capture, AudioSink.SILENT);
        nes.loadROM(midFrameSplitRom(top, bottom, 8));
        nes.reset();

        int guard = 12_000_000;
        while (capture.frames < 40 && guard-- > 0) {
            nes.stepInstruction();
        }

        PPU ppu = nes.getPpu();
        int topRows = 0, bottomRows = 0, transitions = 0;
        Boolean previous = null;
        StringBuilder bad = new StringBuilder();

        for (int y = 0; y < 240; y++) {
            boolean matchesTop = true, matchesBottom = true;
            for (int x = 0; x < 256; x++) {
                int got = capture.pixels[y * 256 + x] & 0xFFFFFF;
                if (got != expectedPixel(ppu, top, 0, x, y)) {
                    matchesTop = false;
                }
                if (got != expectedPixel(ppu, bottom, 0, x, y)) {
                    matchesBottom = false;
                }
                if (!matchesTop && !matchesBottom) {
                    break;
                }
            }
            if (!matchesTop && !matchesBottom) {
                if (bad.length() < 120) {
                    bad.append(' ').append(y);
                }
                continue;
            }
            if (matchesTop) {
                topRows++;
            }
            if (matchesBottom && !matchesTop) {
                bottomRows++;
            }
            boolean isBottom = matchesBottom && !matchesTop;
            if (previous != null && previous != isBottom) {
                transitions++;
            }
            previous = isBottom;
        }

        assertEquals("rows matching neither scroll position (mixed tiles and"
                + " attributes):" + bad, 0, bad.length());
        assertTrue("no rows used the top scroll; the split ROM did not set up",
                topRows > 20);
        assertTrue("no rows used the bottom scroll; the mid-frame write had no"
                + " effect, so this proves nothing", bottomRows > 20);
        assertEquals("the picture changed scroll more than once", 1, transitions);
    }

    /**
     * A CHR bank switched part-way along a scanline splits that row, not the
     * one before it.
     *
     * <p>Kirby writes its CHR banks mid-scanline - measured at dots 43, 99, 145
     * and 246 on different lines - so the graphics change while the row is
     * being fetched. Tiles reach the screen two behind where they are fetched,
     * and a row's first two come from the previous row's fetch phase, so an
     * error of a tile or two here lands at the left-hand edge of the picture.
     */
    @Test
    public void aMidScanlineChrSwitchSplitsTheRowItLandsOn() throws Exception {
        byte[] chr = twoChrBanks();

        Capture capture = new Capture();
        NES nes = new NES(capture, AudioSink.SILENT);
        nes.loadROM(midFrameChrRom(8));
        nes.reset();

        int guard = 12_000_000;
        while (capture.frames < 40 && guard-- > 0) {
            nes.stepInstruction();
        }

        PPU ppu = nes.getPpu();

        // No tile may be assembled from both banks, and once a row has changed
        // over it must not change back. Comparing whole tiles rather than the
        // first differing pixel matters: the two banks can happen to agree on
        // the first pixels of a tile, so a differing pixel is not the boundary.
        int mixedTiles = 0, reverted = 0, switchedRows = 0;
        StringBuilder detail = new StringBuilder();

        for (int y = 0; y < 240; y++) {
            boolean sawBank1 = false, wentBack = false, sawBank0After = false;
            for (int tile = 0; tile < 32; tile++) {
                boolean only0 = false, only1 = false;
                for (int i = 0; i < 8; i++) {
                    int x = tile * 8 + i;
                    int got = capture.pixels[y * 256 + x] & 0xFFFFFF;
                    boolean is0 = got == expectedPixelFromBank(ppu, chr, 0, x, y);
                    boolean is1 = got == expectedPixelFromBank(ppu, chr, 1, x, y);
                    if (is0 && !is1) {
                        only0 = true;
                    }
                    if (is1 && !is0) {
                        only1 = true;
                    }
                }
                if (only0 && only1) {
                    mixedTiles++;
                    if (detail.length() < 100) {
                        detail.append(" y=").append(y).append(",tile=").append(tile);
                    }
                }
                if (only1) {
                    sawBank1 = true;
                }
                if (only0 && sawBank1) {
                    sawBank0After = true;
                }
            }
            if (sawBank0After) {
                wentBack = true;
                reverted++;
            }
            if (sawBank1 && !wentBack) {
                switchedRows++;
            }
        }

        assertEquals("tiles assembled from both CHR banks:" + detail, 0, mixedTiles);
        assertEquals("rows that went back to the first bank after changing over, so a"
                + " tile reached the wrong column", 0, reverted);
        assertTrue("no row changed bank part-way along, so this proves nothing",
                switchedRows > 0);
    }

    /**
     * A CHR bank switched part-way down the frame must split the picture
     * cleanly: rows from one bank, then rows from the other, and no row drawn
     * from a mixture of the two.
     */
    @Test
    public void everyRowIsConsistentAcrossAMidFrameChrBankSwitch() throws Exception {
        byte[] chr = twoChrBanks();

        Capture capture = new Capture();
        NES nes = new NES(capture, AudioSink.SILENT);
        nes.loadROM(midFrameChrRom(8));
        nes.reset();

        int guard = 12_000_000;
        while (capture.frames < 40 && guard-- > 0) {
            nes.stepInstruction();
        }

        PPU ppu = nes.getPpu();
        int bank0Rows = 0, bank1Rows = 0, transitions = 0;
        Boolean previous = null;
        StringBuilder bad = new StringBuilder();

        for (int y = 0; y < 240; y++) {
            boolean matches0 = true, matches1 = true;
            for (int x = 0; x < 256; x++) {
                int got = capture.pixels[y * 256 + x] & 0xFFFFFF;
                if (got != expectedPixelFromBank(ppu, chr, 0, x, y)) {
                    matches0 = false;
                }
                if (got != expectedPixelFromBank(ppu, chr, 1, x, y)) {
                    matches1 = false;
                }
                if (!matches0 && !matches1) {
                    break;
                }
            }
            if (!matches0 && !matches1) {
                if (bad.length() < 120) {
                    bad.append(' ').append(y);
                }
                continue;
            }
            boolean isBank1 = matches1 && !matches0;
            if (matches0) {
                bank0Rows++;
            }
            if (isBank1) {
                bank1Rows++;
            }
            if (previous != null && previous != isBank1) {
                transitions++;
            }
            previous = isBank1;
        }

        assertEquals("rows drawn from a mixture of both CHR banks:" + bad, 0, bad.length());
        assertTrue("no rows used the first bank", bank0Rows > 20);
        assertTrue("no rows used the second bank; the mid-frame switch had no"
                + " effect, so this proves nothing", bank1Rows > 20);
        assertEquals("the picture changed bank more than once", 1, transitions);
    }

    /** The same, across the seam between two different nametables. */
    @Test
    public void backgroundIsCorrectAcrossTheNametableSeam() throws Exception {
        for (int scroll : new int[] { 0, 7, 120, 248, 249, 252, 255, 256, 257, 264, 300, 400, 504 }) {
            checkTwoPage(scroll, 0);
        }
    }

    /** And with the picture scrolled vertically as well. */
    @Test
    public void backgroundIsCorrectWhenScrolledVertically() throws Exception {
        for (int y : new int[] { 0, 1, 7, 8, 15, 16, 31, 32, 64, 120, 208, 239 }) {
            checkTwoPage(96, y);
        }
    }
}
