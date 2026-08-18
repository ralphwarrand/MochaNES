package com.mochanes.emulator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Greyscale and colour emphasis, the two things $2001 does to colour.
 *
 * <p>Both were unimplemented: the mask's bit 0 and bits 5-7 were read for
 * rendering enables and nothing else, so a game flashing the screen or tinting
 * it for a damage or underwater effect drew no differently from normal.
 */
public class PaletteTintTest {

    /** Renders one flat frame and returns the colour of the middle pixel. */
    private static int backdrop(int maskValue, int paletteEntry) {
        PPU ppu = new PPU(null);
        ppu.writeRegister(0x2001, maskValue);
        // $3F00 is the backdrop, which is what a blank screen shows.
        ppu.writeVram(0x3F00, paletteEntry);
        return ppu.backdropColour();
    }

    @Test
    public void withoutEmphasisOrGreyscaleTheEntryIsUnchanged() {
        int plain = backdrop(0x00, 0x21);   // a mid blue
        assertEquals("an untinted entry must come through exactly",
                0x4C9AEC, plain & 0xFFFFFF);
    }

    @Test
    public void greyscaleForcesTheGreyColumn() {
        // $21 and $31 are different blues; greyscale maps both onto $20/$30.
        assertEquals(backdrop(0x01, 0x21) & 0xFFFFFF, backdrop(0x01, 0x25) & 0xFFFFFF);
        assertEquals("greyscale must select the entry $x0",
                backdrop(0x00, 0x20) & 0xFFFFFF, backdrop(0x01, 0x21) & 0xFFFFFF);
    }

    @Test
    public void emphasisAttenuatesTheOtherChannels() {
        int plain = backdrop(0x00, 0x30);          // near-white, so all channels move
        int red = backdrop(0x20, 0x30);            // emphasise red
        int blue = backdrop(0x80, 0x30);           // emphasise blue

        assertEquals("emphasising red must leave red alone",
                (plain >> 16) & 0xFF, (red >> 16) & 0xFF);
        assertTrue("emphasising red must dim green", ((red >> 8) & 0xFF) < ((plain >> 8) & 0xFF));
        assertTrue("emphasising red must dim blue", (red & 0xFF) < (plain & 0xFF));

        assertEquals("emphasising blue must leave blue alone", plain & 0xFF, blue & 0xFF);
        assertTrue("emphasising blue must dim red", ((blue >> 16) & 0xFF) < ((plain >> 16) & 0xFF));
    }

    @Test
    public void allThreeEmphasisBitsDimThePicture() {
        int plain = backdrop(0x00, 0x30);
        int all = backdrop(0xE0, 0x30);
        assertTrue("setting every emphasis bit attenuates rather than cancelling out",
                ((all >> 16) & 0xFF) < ((plain >> 16) & 0xFF)
                        && ((all >> 8) & 0xFF) < ((plain >> 8) & 0xFF)
                        && (all & 0xFF) < (plain & 0xFF));
    }

    @Test
    public void changingTheMaskRetintsEntriesWrittenEarlier() {
        PPU ppu = new PPU(null);
        ppu.writeVram(0x3F00, 0x30);
        int plain = ppu.backdropColour();
        ppu.writeRegister(0x2001, 0x20);
        assertTrue("a mask write must re-tint the palette already loaded",
                ppu.backdropColour() != plain);
    }
}
