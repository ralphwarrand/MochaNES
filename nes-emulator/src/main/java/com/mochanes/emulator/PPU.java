package com.mochanes.emulator;

import com.mochanes.emulator.gui.Display;

public class PPU {
    // Registers (CPU Visible)
    private int ctrl; // $2000
    private int mask; // $2001
    private int status; // $2002
    private int oamAddr; // $2003

    // Loopy's Internal Registers (Scrolling)
    private int v; // Current VRAM address (15 bit)
    private int t; // Temporary VRAM address (15 bit)
    private int x; // Fine X Scroll (3 bit)
    private int w; // Write latch (0 or 1)

    private int bufferData; // Internal buffer for $2007 reads

    // Cycle Management
    private int cycle = 0;
    private int scanline = 0; // 0-261
    public boolean nmiOccurred = false;
    public boolean frameComplete = false;

    // Background Rendering Pipeline (Latches & Shifters)
    private int bgNextTileId;
    private int bgNextTileAttrib;
    private int bgNextTileLsb;
    private int bgNextTileMsb;

    private int bgShifterPatternLo;
    private int bgShifterPatternHi;
    private int bgShifterAttribLo;
    private int bgShifterAttribHi;

    // Memory
    // 4KB so four-screen boards have their own VRAM; the two-screen mirroring
    // modes fold addresses into the low 2KB.
    public final byte[] nametables = new byte[4096];
    public final byte[] paletteRam = new byte[32];
    public final byte[] oam = new byte[256]; // Object Attribute Memory

    private final Display display;

    // Fast Rendering Optimization
    private int[] fastBuffer;

    public void setFastRendering(int[] buffer) {
        this.fastBuffer = buffer;
    }

    // Palette
    private static final int[] PALETTE_LOOKUP = {
            0x545454, 0x001E74, 0x081090, 0x300088, 0x440064, 0x5C0030, 0x540400, 0x3C1800,
            0x202A00, 0x083A00, 0x004000, 0x003C00, 0x00323C, 0x000000, 0x000000, 0x000000,
            0x989698, 0x084CC4, 0x3032EC, 0x5C1E14, 0x8814B0, 0xA01464, 0x982220, 0x783C00,
            0x545A00, 0x287200, 0x087C00, 0x007628, 0x006678, 0x000000, 0x000000, 0x000000,
            0xECEEEC, 0x4C9AEC, 0x787CEC, 0xB062EC, 0xE454EC, 0xEC58B4, 0xEC6A64, 0xD48820,
            0xA0AA00, 0x74C400, 0x4CD020, 0x38CC6C, 0x38B4CC, 0x3C3C3C, 0x000000, 0x000000,
            0xECEEEC, 0xA8CCEC, 0xBCBCEC, 0xD4B2EC, 0xECAEEC, 0xECAED4, 0xECB4B0, 0xE4C490,
            0xCCD278, 0xB4DE78, 0xA8E290, 0x98E2B4, 0xA0D6E4, 0xA0A2A0, 0x000000, 0x000000
    };

    public PPU(Display display) {
        this.display = display;
    }


    // === Register Interfaces ===

    private int ioBus = 0; // PPU Open Bus (last written/read value)

    // The open-bus latch is stray capacitance, not a register: it loses its
    // charge after roughly 600ms unless refreshed by another access.
    private static final long IO_BUS_DECAY_TICKS = 3_221_591L; // ~600ms of PPU dots
    private long ppuTicks = 0;
    // Each bit holds its own charge, so an access that only drives some bits
    // refreshes only those - reading $2002 refreshes bits 7-5 and leaves the
    // rest decaying, and reading a write-only register refreshes nothing.
    private final long[] ioBusBitSetAt = new long[8];

    /** Drives the bits selected by {@code mask} and refreshes only those. */
    private void refreshIoBus(int value, int mask) {
        for (int b = 0; b < 8; b++) {
            int bit = 1 << b;
            if ((mask & bit) != 0) {
                ioBus = (ioBus & ~bit) | (value & bit);
                ioBusBitSetAt[b] = ppuTicks;
            }
        }
    }

    private int decayedIoBus() {
        for (int b = 0; b < 8; b++) {
            if (ppuTicks - ioBusBitSetAt[b] > IO_BUS_DECAY_TICKS) {
                ioBus &= ~(1 << b);
            }
        }
        return ioBus;
    }

    public int readRegister(int addr, int openBus) {
        int res = openBus; // Default to open bus

        switch (addr) {
            case 0x2000: // Write only
            case 0x2001: // Write only
            case 0x2003: // Write only
            case 0x2005: // Write only
            case 0x2006: // Write only
                // These drive the PPU's own decay latch, not the CPU bus: a
                // write to any PPU register followed by a read of a write-only
                // one returns the value written.
                res = decayedIoBus();
                break;

            case 0x2002:
                // Reading right as VBlank is being raised races the flag: one
                // dot before, the flag never sets at all; on the set dot or the
                // one after, it reads set but the NMI is suppressed.
                // tick() runs the dot then advances, so `cycle` is the *next*
                // dot: VBlank (set during dot 241,1) is only visible once cycle
                // has reached 2.
                {
                    if (scanline == 241 && cycle == 1) {
                        suppressVblSet = true; // read lands before the flag sets
                    }
                    if (scanline == 241 && cycle >= 1 && cycle <= 3) {
                        nmiOccurred = false; // reading across the set dot kills the NMI
                    }
                }

                // Only bits 7-5 are driven by the status register; the low five
                // come from - and keep decaying on - the latch.
                res = (status & 0xE0) | (decayedIoBus() & 0x1F);
                refreshIoBus(status, 0xE0);
                status &= ~0x80;
                w = 0;
                break;
            case 0x2004:
                res = oam[oamAddr] & 0xFF;
                // Attribute bytes only implement 5 of their 8 bits; bits 2-4
                // have no storage behind them and always read back as 0.
                if ((oamAddr & 0x03) == 0x02)
                    res &= 0xE3;
                refreshIoBus(res, 0xFF);
                break;
            case 0x2007:
                int data = bufferData;
                // The read buffer is filled from the VRAM bus. In the palette
                // range the palette RAM answers the CPU directly while the bus
                // still carries the nametable byte mirrored underneath it, so
                // that - not the palette entry - is what gets buffered.
                bufferData = ((v & 0x3FFF) >= 0x3F00) ? readVram(v & 0x2FFF) : readVram(v);

                if ((v & 0x3FFF) >= 0x3F00) {
                    // Palette reads bypass the read buffer, but only 6 bits come
                    // from palette RAM - the top two are left over on the latch.
                    data = (decayedIoBus() & 0xC0) | (readVram(v) & 0x3F);
                    refreshIoBus(data, 0x3F);
                } else {
                    refreshIoBus(data, 0xFF);
                }

                v += ((ctrl & 0x04) != 0) ? 32 : 1;
                updateA12(v);
                res = data;
                break;
        }

        return res;
    }

    public void writeRegister(int addr, int val) {
        refreshIoBus(val, 0xFF); // a write drives the whole bus
        switch (addr) {
            case 0x2000: // Control
                boolean nmiWasEnabled = (ctrl & 0x80) != 0;
                ctrl = val;
                boolean nmiNowEnabled = (ctrl & 0x80) != 0;
                // The NMI line is level-driven: enabling it while the VBlank flag
                // is still set asserts immediately, and disabling it releases any
                // pending assertion.
                if (!nmiWasEnabled && nmiNowEnabled && (status & 0x80) != 0) {
                    nmiOccurred = true;
                } else if (!nmiNowEnabled) {
                    nmiOccurred = false;
                }
                // Update NameTable select (t: ...GH..)
                t = (t & 0xF3FF) | ((val & 0x03) << 10);
                break;
            case 0x2001: // Mask
                mask = val;
                break;
            case 0x2003: // OAM Addr
                oamAddr = val;
                break;
            case 0x2004: // OAM Data
                oam[oamAddr] = (byte) val;
                oamAddr = (oamAddr + 1) & 0xFF;
                break;
            case 0x2005: // Scroll
                if (w == 0) {
                    // First Write: Fine X and Coarse X
                    x = val & 0x07;
                    t = (t & 0xFFE0) | ((val >> 3) & 0x1F);
                    w = 1;
                } else {
                    // Second Write: Fine Y and Coarse Y
                    t = (t & 0x8FFF) | ((val & 0x07) << 12);
                    t = (t & 0xFC1F) | ((val & 0xF8) << 2);
                    w = 0;
                }
                break;
            case 0x2006: // Address
                if (w == 0) {
                    // First Write: High Byte
                    t = (t & 0x00FF) | ((val & 0x3F) << 8); // Bit 14 cleared
                    w = 1;
                } else {
                    // Second Write: Low Byte and Update v
                    t = (t & 0xFF00) | val;
                    v = t;
                    w = 0;
                    updateA12(v);
                }
                break;
            case 0x2007: // Data
                writeVram(v, val);
                v += ((ctrl & 0x04) != 0) ? 32 : 1;
                updateA12(v);
                break;
        }
    }

    private int lastA12 = 0;

    /**
     * Clocks the mapper on A12 rising edges caused by CPU-driven VRAM address
     * changes. While rendering, the per-scanline clock in tick() stands in for
     * the PPU's own fetch pattern, so this only applies with rendering off.
     */
    private void updateA12(int addr) {
        int a12 = (addr >> 12) & 0x01;
        if (lastA12 == 0 && a12 == 1 && (mask & 0x18) == 0 && memory != null) {
            memory.clockMapperScanline();
        }
        lastA12 = a12;
    }

    public boolean willNmiFire(int cpuCycles) {
        // NMI enabled?
        if ((ctrl & 0x80) == 0)
            return false;

        // Calculate ticks until VBlank (Scanline 241, Cycle 1)
        long ticksRemaining = -1;

        if (scanline < 241) {
            ticksRemaining = ((240 - scanline) * 341L) + (341 - cycle) + 1;
        } else if (scanline == 261) { // Pre-render wrap-around
            ticksRemaining = (341 - cycle) + (241 * 341L) + 1;
        }

        if (ticksRemaining != -1) {
            return ticksRemaining <= (cpuCycles * 3L);
        }
        return false;
    }

    // === Execution ===

    public void step(int cycles) {
        for (int i = 0; i < cycles; i++) {
            tick();
            if (frameComplete)
                break;
        }
    }

    public void tick() {
        ppuTicks++;
        // --- Background Logic ---
        if (scanline >= 0 && scanline < 240 || scanline == 261) { // Visible or Pre-render
            if (scanline == 0 && cycle == 0 && frameComplete) {
                frameComplete = false; // Start new frame
            }

            if ((mask & 0x18) != 0) { // If rendering enabled
                // Cycle-based fetching
                if ((cycle >= 2 && cycle < 258) || (cycle >= 321 && cycle < 338)) {
                    updateShifters();

                    switch ((cycle - 1) % 8) {
                        case 0:
                            loadBackgroundShifters();
                            // Fetch NT Byte
                            bgNextTileId = readVram(0x2000 | (v & 0x0FFF));
                            break;
                        case 2:
                            // Fetch Attribute Byte
                            // Complex address calc: 0x23C0 + (v.nt << 10) + ((v.y >> 5) << 3) + (v.x >> 5)
                            // But v has specific layout: yyy NN YYYYY XXXXX
                            int addr = 0x23C0 | (v & 0x0C00) | ((v >> 4) & 0x38) | ((v >> 2) & 0x07);
                            bgNextTileAttrib = readVram(addr);
                            // Process Quad
                            if ((v & 0x0040) != 0)
                                bgNextTileAttrib >>= 4; // Top/Bottom
                            if ((v & 0x0002) != 0)
                                bgNextTileAttrib >>= 2; // Left/Right
                            bgNextTileAttrib &= 0x03;
                            break;
                        case 4:
                            // Fetch Lo BG
                            int tableAddr = ((ctrl & 0x10) != 0) ? 0x1000 : 0x0000;
                            bgNextTileLsb = readVram(tableAddr + (bgNextTileId * 16) + ((v >> 12) & 0x07));
                            break;
                        case 6:
                            // Fetch Hi BG
                            int tableAddr2 = ((ctrl & 0x10) != 0) ? 0x1000 : 0x0000;
                            bgNextTileMsb = readVram(tableAddr2 + (bgNextTileId * 16) + ((v >> 12) & 0x07) + 8);
                            break;
                        case 7:
                            incrementScrollX();
                            break;
                    }
                }

                // Vertical Increment
                if (cycle == 256) {
                    incrementScrollY();
                }

                // Horizontal Reset
                if (cycle == 257) {
                    transferAddressX();
                }

                // Vertical Reset (Pre-render only)
                if (scanline == 261 && cycle >= 280 && cycle < 305) {
                    transferAddressY();
                }
            } // End rendering enabled check
        }

        // --- Rendering ---
        if (scanline == 240) {
            // Post-render scanline (idle)
        }

        // Clock the mapper's scanline counter (MMC3 IRQ). On hardware this is
        // driven by A12 rising edges, which during rendering land near the end of
        // each visible scanline's sprite fetches.
        if (cycle == 260 && (mask & 0x18) != 0 && (scanline < 240 || scanline == 261)) {
            if (memory != null)
                memory.clockMapperScanline();
        }

        // Sprite evaluation for the next scanline. Hardware does this during
        // cycles 257-320 of the current line.
        if (cycle == 257 && (scanline < 240 || scanline == 261)) {
            if ((mask & 0x18) != 0) {
                evaluateSprites(scanline == 261 ? 0 : scanline + 1);
            } else {
                spriteCount = 0;
            }
        }

        // VBlank
        if (scanline == 241 && cycle == 1) {
            if (!suppressVblSet) {
                status |= 0x80;
                if ((ctrl & 0x80) != 0)
                    nmiOccurred = true;
            }
            suppressVblSet = false;
            if (display != null)
                display.refresh();
        }

        // Pre-render clear flags
        if (scanline == 261 && cycle == 1) {
            status &= ~(0x80 | 0x40 | 0x20); // Clear VBlank, Sprite 0, Overflow
            nmiOccurred = false;
        }

        // Pixel Output (Visible Area)
        if (scanline < 240 && cycle > 0 && cycle <= 256) {
            // Optimization: If rendering disabled (Turbo Seek), ONLY run if Sprite 0 is on
            // this scanline
            if (!renderingEnabled) {
                // Approximate Sprite 0 check (doesn't account for double height mode perfectly
                // but good enough for hits)
                int sprite0Y = oam[0] & 0xFF;
                // Sprite 0 hit logic usually requires exact overlap, but for skipping:
                // If scanline is NOT near sprite 0, safe to skip.
                // Standard NES sprites are 8 or 16 pixels high.
                // We add 1 scanline delay typical of PPU
                if (scanline >= sprite0Y && scanline < sprite0Y + 16) {
                    renderPixel();
                }
            } else {
                renderPixel();
            }
        }

        // --- End of Cycle ---
        cycle++;

        // On odd frames with rendering enabled the pre-render line is one dot
        // short, which is how the PPU keeps its colour phase aligned. Games
        // that switch rendering mid-frame depend on this being conditional.
        if (scanline == 261 && cycle == 340 && oddFrame && (mask & 0x18) != 0) {
            cycle = 341; // drop the final dot
        }

        if (cycle >= 341) {
            cycle = 0;
            scanline++;
            if (scanline >= 262) {
                scanline = 0;
                oddFrame = !oddFrame;
                frameComplete = true; // Signal Main Loop
            }
        }
    }


    private boolean oddFrame = false;
    /** Set when $2002 is read on the dot before VBlank would be raised. */
    private boolean suppressVblSet = false;

    // === Logic Helpers ===

    public boolean renderingEnabled = true;

    // === Secondary OAM ===
    // Hardware evaluates sprites once per scanline into an 8-entry buffer and
    // fetches their pattern bytes during the same period. Doing the same here
    // replaces a 64-sprite scan (with two VRAM reads each) at every pixel.
    private final int[] spriteX = new int[8];
    private final int[] spriteAttr = new int[8];
    private final int[] spritePatLo = new int[8];
    private final int[] spritePatHi = new int[8];
    private final boolean[] spriteIsZero = new boolean[8];
    private int spriteCount = 0;

    /** Fills secondary OAM for the given scanline. */
    private void evaluateSprites(int line) {
        spriteCount = 0;
        if (line < 0 || line >= 240)
            return;

        int height = ((ctrl & 0x20) != 0) ? 16 : 8;

        for (int i = 0; i < 64; i++) {
            int idx = i * 4;
            int sy = oam[idx] & 0xFF;
            // Sprites are delayed one scanline: OAM Y of N draws on line N+1.
            int row = line - sy - 1;
            if (row < 0 || row >= height)
                continue;

            if (spriteCount == 8) {
                status |= 0x20; // sprite overflow
                break;
            }

            int id = oam[idx + 1] & 0xFF;
            int attr = oam[idx + 2] & 0xFF;

            int tileRow = ((attr & 0x80) != 0) ? (height - 1 - row) : row; // vertical flip

            int patternAddr;
            if (height == 8) {
                patternAddr = ((ctrl & 0x08) != 0 ? 0x1000 : 0x0000) + id * 16 + tileRow;
            } else {
                // 8x16: bank comes from bit 0 of the tile id, and the lower
                // tile sits 8 bytes further on.
                patternAddr = ((id & 0x01) * 0x1000) + ((id & 0xFE) * 16) + tileRow;
                if (tileRow >= 8)
                    patternAddr += 8;
            }

            spriteX[spriteCount] = oam[idx + 3] & 0xFF;
            spriteAttr[spriteCount] = attr;
            spritePatLo[spriteCount] = readVram(patternAddr);
            spritePatHi[spriteCount] = readVram(patternAddr + 8);
            spriteIsZero[spriteCount] = (i == 0);
            spriteCount++;
        }
    }

    private void renderPixel() {
        int bgPixel = 0;
        int bgPalette = 0;
        int px = cycle - 1;

        // Mask bits 1 and 2 blank the leftmost 8 pixels for background and
        // sprites independently. Games rely on this while scrolling to hide the
        // partial tile scrolled in from the left; without it that column shows
        // as garbage along the edge.
        boolean showBgHere = (mask & 0x08) != 0 && ((mask & 0x02) != 0 || px >= 8);
        boolean showSprHere = (mask & 0x10) != 0 && ((mask & 0x04) != 0 || px >= 8);

        // 1. Background
        if (showBgHere) {
            int bitMux = 0x8000 >> x;

            int p0 = (bgShifterPatternLo & bitMux) != 0 ? 1 : 0;
            int p1 = (bgShifterPatternHi & bitMux) != 0 ? 1 : 0;
            bgPixel = (p1 << 1) | p0;

            int pal0 = (bgShifterAttribLo & bitMux) != 0 ? 1 : 0;
            int pal1 = (bgShifterAttribHi & bitMux) != 0 ? 1 : 0;
            bgPalette = (pal1 << 1) | pal0;
        }

        // 2. Sprites
        int sprPixel = 0;
        int sprPalette = 0;
        boolean sprPriority = true;
        boolean sprite0HitPossible = false;

        if (showSprHere) {
            // Walk only the sprites evaluated for this scanline (at most 8),
            // using pattern bytes already fetched in evaluateSprites().
            for (int i = 0; i < spriteCount; i++) {
                int dx = px - spriteX[i];
                if (dx < 0 || dx >= 8)
                    continue;

                int attr = spriteAttr[i];
                int bit = ((attr & 0x40) != 0) ? dx : (7 - dx); // horizontal flip
                int val = ((spritePatLo[i] >> bit) & 1) | (((spritePatHi[i] >> bit) & 1) << 1);

                if (val != 0) {
                    // First opaque sprite in OAM order wins.
                    sprPixel = val;
                    sprPalette = (attr & 0x03) + 4;
                    sprPriority = (attr & 0x20) == 0; // 0: Front
                    sprite0HitPossible = spriteIsZero[i];
                    break;
                }
            }
        }

        // 3. Sprite 0 Hit (LOGIC MUST RUN)
        if (sprite0HitPossible && bgPixel != 0) {
            // Hardware needs both layers enabled, and never reports a hit at
            // x=255 or in a column clipped away by the left-edge mask.
            if ((mask & 0x18) == 0x18 && px != 255) {
                status |= 0x40;
            }
        }

        // SKIP OUTPUT IF DISABLED
        if (!renderingEnabled)
            return;

        // 4. Multiplexer
        int finalPixel;
        int finalPalette;

        if (bgPixel == 0 && sprPixel == 0) {
            // Background Color (Universal)
            finalPixel = 0;
            finalPalette = 0;
        } else if (bgPixel == 0 && sprPixel > 0) {
            finalPixel = sprPixel;
            finalPalette = sprPalette;
        } else if (bgPixel > 0 && sprPixel == 0) {
            finalPixel = bgPixel;
            finalPalette = bgPalette;
        } else {
            // Both
            if (sprPriority) {
                finalPixel = sprPixel;
                finalPalette = sprPalette;
            } else {
                finalPixel = bgPixel;
                finalPalette = bgPalette;
            }
        }

        int colorIndex = readVram(0x3F00 + (finalPalette << 2) + finalPixel);
        int color = PALETTE_LOOKUP[colorIndex & 0x3F];

        if (fastBuffer != null) {
            // Fast Path: Direct Array Access (No Bounds Check, No Virtual Call)
            // Safety: cycle is 1..256 -> index 0..255. scanline is 0..239.
            fastBuffer[scanline * 256 + (cycle - 1)] = color;
        } else {
            // Slow Path: Virtual Display Call
            if (display != null)
                display.setPixel(cycle - 1, scanline, color);
        }
    }

    // === Shifters & Scrolling ===

    private void updateShifters() {
        if ((mask & 0x08) != 0) {
            bgShifterPatternLo <<= 1;
            bgShifterPatternHi <<= 1;
            bgShifterAttribLo <<= 1;
            bgShifterAttribHi <<= 1;
        }
    }

    private void loadBackgroundShifters() {
        bgShifterPatternLo = (bgShifterPatternLo & 0xFF00) | bgNextTileLsb;
        bgShifterPatternHi = (bgShifterPatternHi & 0xFF00) | bgNextTileMsb;

        // Expand Attribute bits to 8-bit width
        bgShifterAttribLo = (bgShifterAttribLo & 0xFF00) | ((bgNextTileAttrib & 0x01) != 0 ? 0xFF : 0x00);
        bgShifterAttribHi = (bgShifterAttribHi & 0xFF00) | ((bgNextTileAttrib & 0x02) != 0 ? 0xFF : 0x00);
    }

    private void incrementScrollX() {
        if ((mask & 0x18) != 0) {
            if ((v & 0x001F) == 31) {
                v &= ~0x001F; // Clear Coarse X
                v ^= 0x0400; // Switch Horizontal Nametable
            } else {
                v++;
            }
        }
    }

    private void incrementScrollY() {
        if ((mask & 0x18) != 0) {
            int fineY = (v & 0x7000) >> 12;
            if (fineY < 7) {
                fineY++;
                v = (v & ~0x7000) | (fineY << 12);
            } else {
                v &= ~0x7000; // Reset fine Y
                int y = (v & 0x03E0) >> 5;
                if (y == 29) {
                    y = 0;
                    v ^= 0x0800; // Switch Vertical Nametable
                } else if (y == 31) {
                    y = 0;
                } else {
                    y++;
                }
                v = (v & ~0x03E0) | (y << 5);
            }
        }
    }

    private void transferAddressX() {
        if ((mask & 0x18) != 0) {
            v = (v & ~0x041F) | (t & 0x041F);
        }
    }

    private void transferAddressY() {
        if ((mask & 0x18) != 0) {
            v = (v & ~0x7BE0) | (t & 0x7BE0);
        }
    }

    private Memory memory;

    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    // === VRAM Access ===
    // Mirroring Modes
    public static final int MIRROR_HORIZONTAL = 0;
    public static final int MIRROR_VERTICAL = 1;
    public static final int MIRROR_ONESCREEN_LO = 2;
    public static final int MIRROR_ONESCREEN_HI = 3;
    public static final int MIRROR_FOUR_SCREEN = 4;

    private int mirroring = MIRROR_VERTICAL; // Default

    public void setMirroring(int mode) {
        this.mirroring = mode;
    }

    public int readVram(int addr) {
        int address = addr & 0x3FFF;
        if (address < 0x2000) {
            // Pattern Tables (Delegated to Memory for Mapper Banking)
            return memory != null ? memory.readChr(address) : 0;
        } else if (address < 0x3F00) {
            // Nametables
            int index = getMirroredAddress(address) & 0x0FFF;
            return nametables[index] & 0xFF;
        } else if (address < 0x4000) {
            // Palettes
            address &= 0x001F;
            if (address == 0x10)
                address = 0x00;
            if (address == 0x14)
                address = 0x04;
            if (address == 0x18)
                address = 0x08;
            if (address == 0x1C)
                address = 0x0C;
            return paletteRam[address] & 0xFF;
        }
        return 0;
    }

    public void writeVram(int addr, int val) {
        int address = addr & 0x3FFF;
        if (address < 0x2000) {
            // Pattern Tables
            if (memory != null)
                memory.writeChr(address, val);
        } else if (address < 0x3F00) {
            int index = getMirroredAddress(address) & 0x0FFF;
            nametables[index] = (byte) val;
        } else if (address < 0x4000) {
            address &= 0x001F;
            if (address == 0x10)
                address = 0x00;
            if (address == 0x14)
                address = 0x04;
            if (address == 0x18)
                address = 0x08;
            if (address == 0x1C)
                address = 0x0C;
            paletteRam[address] = (byte) val;
        }
    }

    private int getMirroredAddress(int addr) {
        int address = addr & 0x0FFF; // 0x0000 - 0x0FFF offset
        int table = address / 0x400; // 0, 1, 2, 3

        switch (mirroring) {
            case MIRROR_HORIZONTAL:
                // [0] [0]
                // [1] [1]
                if (table == 1)
                    address -= 0x400; // Map 1->0
                if (table == 2)
                    address -= 0x400; // Map 2->1
                if (table == 3)
                    address -= 0x800; // Map 3->1
                break;
            case MIRROR_VERTICAL:
                // [0] [1]
                // [0] [1]
                if (table == 2)
                    address -= 0x800; // Map 2->0
                if (table == 3)
                    address -= 0x800; // Map 3->1
                break;
            case MIRROR_ONESCREEN_LO:
                address &= 0x03FF; // Always Table 0
                break;
            case MIRROR_ONESCREEN_HI:
                address = (address & 0x03FF) + 0x400; // Always Table 1
                break;
            case MIRROR_FOUR_SCREEN:
                // Each table has its own RAM; pass the offset straight through.
                break;
        }
        return address;
    }

    // === Debugger Helpers ===

    // Get PPU Palette
    public int getColorFromPaletteRam(int palette, int pixel) {
        int index = paletteRam[palette * 4 + pixel] & 0x3F;
        return PALETTE_LOOKUP[index];
    }

    public int[] getPatternTable(int i, int palette) {
        // i: 0 or 1 (Left or Right Pattern Table)
        // Returns 128x128 pixel array (4096 tiles * 8x8 pixels? No, 256 tiles * 8x8 =
        // 128x128)
        // 16x16 tiles = 256 tiles per table.
        // Each tile is 8x8 pixels.
        // Image size: 128 x 128.

        int[] pixels = new int[128 * 128];

        for (int tileY = 0; tileY < 16; tileY++) {
            for (int tileX = 0; tileX < 16; tileX++) {
                int offset = tileY * 256 + tileX * 16; // Tile Index * 16 bytes
                int tableOffset = i * 4096;

                for (int row = 0; row < 8; row++) {
                    int tileLsb = readVram(tableOffset + offset + row);
                    int tileMsb = readVram(tableOffset + offset + row + 8);

                    for (int col = 0; col < 8; col++) {
                        int pixel = ((tileLsb & 0x01) << 0) | ((tileMsb & 0x01) << 1);
                        tileLsb >>= 1;
                        tileMsb >>= 1;

                        int c = getColorFromPaletteRam(palette, pixel);

                        // Calculate pixel pos in final image
                        int px = tileX * 8 + (7 - col);
                        int py = tileY * 8 + row;

                        pixels[py * 128 + px] = c;
                    }
                }
            }
        }
        return pixels;
    }

    public PPU copy(Display newDisplay) {
        PPU newPPU = new PPU(newDisplay);

        // Registers
        newPPU.ctrl = this.ctrl;
        newPPU.mask = this.mask;
        newPPU.status = this.status;
        newPPU.oamAddr = this.oamAddr;

        // Loopy
        newPPU.v = this.v;
        newPPU.t = this.t;
        newPPU.x = this.x;
        newPPU.w = this.w;
        newPPU.bufferData = this.bufferData;
        newPPU.ioBus = this.ioBus; // PPU Open Bus

        // Cycle
        newPPU.cycle = this.cycle;
        newPPU.scanline = this.scanline;
        newPPU.nmiOccurred = this.nmiOccurred;
        newPPU.frameComplete = this.frameComplete;

        // Shifters & Latches
        newPPU.bgNextTileId = this.bgNextTileId;
        newPPU.bgNextTileAttrib = this.bgNextTileAttrib;
        newPPU.bgNextTileLsb = this.bgNextTileLsb;
        newPPU.bgNextTileMsb = this.bgNextTileMsb;

        newPPU.bgShifterPatternLo = this.bgShifterPatternLo;
        newPPU.bgShifterPatternHi = this.bgShifterPatternHi;
        newPPU.bgShifterAttribLo = this.bgShifterAttribLo;
        newPPU.bgShifterAttribHi = this.bgShifterAttribHi;

        // Arrays
        System.arraycopy(this.nametables, 0, newPPU.nametables, 0, this.nametables.length);
        System.arraycopy(this.paletteRam, 0, newPPU.paletteRam, 0, this.paletteRam.length);
        System.arraycopy(this.oam, 0, newPPU.oam, 0, this.oam.length);

        newPPU.mirroring = this.mirroring;

        return newPPU;
    }

    public void fastCopyFrom(PPU source) {
        this.ctrl = source.ctrl;
        this.mask = source.mask;
        this.status = source.status;
        this.oamAddr = source.oamAddr;

        this.v = source.v;
        this.t = source.t;
        this.x = source.x;
        this.w = source.w;
        this.bufferData = source.bufferData;
        this.ioBus = source.ioBus;

        this.cycle = source.cycle;
        this.scanline = source.scanline;
        this.nmiOccurred = source.nmiOccurred;
        this.frameComplete = source.frameComplete;

        this.bgNextTileId = source.bgNextTileId;
        this.bgNextTileAttrib = source.bgNextTileAttrib;
        this.bgNextTileLsb = source.bgNextTileLsb;
        this.bgNextTileMsb = source.bgNextTileMsb;

        this.bgShifterPatternLo = source.bgShifterPatternLo;
        this.bgShifterPatternHi = source.bgShifterPatternHi;
        this.bgShifterAttribLo = source.bgShifterAttribLo;
        this.bgShifterAttribHi = source.bgShifterAttribHi;

        System.arraycopy(source.nametables, 0, this.nametables, 0, source.nametables.length);
        System.arraycopy(source.paletteRam, 0, this.paletteRam, 0, source.paletteRam.length);
        System.arraycopy(source.oam, 0, this.oam, 0, source.oam.length);

        this.mirroring = source.mirroring;
        // DO NOT copy fastBuffer! It is a structural reference to the Display.
        // this.fastBuffer = source.fastBuffer;
    }

    // === Serialization ===

    public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
        dos.writeInt(ctrl);
        dos.writeInt(mask);
        dos.writeInt(status);
        dos.writeInt(oamAddr);

        dos.writeInt(v);
        dos.writeInt(t);
        dos.writeInt(x);
        dos.writeInt(w);
        dos.writeInt(bufferData);
        dos.writeInt(ioBus);

        dos.writeInt(cycle);
        dos.writeInt(scanline);
        dos.writeBoolean(nmiOccurred);
        dos.writeBoolean(frameComplete);

        dos.writeInt(bgNextTileId);
        dos.writeInt(bgNextTileAttrib);
        dos.writeInt(bgNextTileLsb);
        dos.writeInt(bgNextTileMsb);

        dos.writeInt(bgShifterPatternLo);
        dos.writeInt(bgShifterPatternHi);
        dos.writeInt(bgShifterAttribLo);
        dos.writeInt(bgShifterAttribHi);

        dos.write(nametables);
        dos.write(paletteRam);
        dos.write(oam);

        dos.writeInt(mirroring);
    }

    public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
        this.ctrl = dis.readInt();
        this.mask = dis.readInt();
        this.status = dis.readInt();
        this.oamAddr = dis.readInt();

        this.v = dis.readInt();
        this.t = dis.readInt();
        this.x = dis.readInt();
        this.w = dis.readInt();
        this.bufferData = dis.readInt();
        this.ioBus = dis.readInt();

        this.cycle = dis.readInt();
        this.scanline = dis.readInt();
        this.nmiOccurred = dis.readBoolean();
        this.frameComplete = dis.readBoolean();

        this.bgNextTileId = dis.readInt();
        this.bgNextTileAttrib = dis.readInt();
        this.bgNextTileLsb = dis.readInt();
        this.bgNextTileMsb = dis.readInt();

        this.bgShifterPatternLo = dis.readInt();
        this.bgShifterPatternHi = dis.readInt();
        this.bgShifterAttribLo = dis.readInt();
        this.bgShifterAttribHi = dis.readInt();

        dis.readFully(this.nametables);
        dis.readFully(this.paletteRam);
        dis.readFully(this.oam);

        this.mirroring = dis.readInt();
    }

}
