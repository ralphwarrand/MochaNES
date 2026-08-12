package com.mochanes.emulator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import com.mochanes.emulator.hooks.MemoryHook;

public class Memory {
    // System Memory
    final byte[] ram = new byte[2048]; // 2KB Internal RAM
    private final byte[] saveRam = new byte[8192]; // 8KB Battery Backed RAM

    private final byte[] expansionRom = new byte[8192];

    int openBus = 0; // Last value on data bus

    // Components
    private final int[] apuIoRegisters = new int[32]; // $4000-$401F
    public final byte[] oam = new byte[256]; // (Not used directly here, usually in PPU, but kept for DMA ref)

    // ROM Data
    private byte[] prgRom; // Full PRG Data
    private byte[] chrRom; // Full CHR Data (or RAM)
    private boolean isChrRam = false;

    // Mapper State
    private int mapperID;

    // MMC1 State
    private int currShift = 0;
    private int shiftCount = 0;
    private int mmc1Control = 0x0C; // Default: PRG 16k Mode 3, CHR 8k
    private int mmc1ChrBank0 = 0;
    private int mmc1ChrBank1 = 0;
    private int mmc1PrgBank = 0;

    // MMC3 (mapper 4) State
    private int mmc3BankSelect = 0; // $8000 even: R index, PRG mode (bit 6), CHR inversion (bit 7)
    private final int[] mmc3Regs = new int[8]; // R0-R7 bank numbers
    private int mmc3IrqLatch = 0;
    private int mmc3IrqCounter = 0;
    private boolean mmc3IrqReload = false;
    private boolean mmc3IrqEnabled = false;
    private boolean mmc3IrqAsserted = false;

    // Cartridge geometry, cached at load time. readPrg/readChr sit on the
    // hottest path in the emulator, and recomputing bank counts there meant an
    // integer division per memory access.
    private int prgBanks16k = 1;
    private int prgBanks8k = 1;
    private int prgBanks32k = 1;
    private int chrMask = 0; // CHR sizes are powers of two, so & replaces %

    private void cacheGeometry() {
        prgBanks16k = Math.max(1, prgRom.length / 16384);
        prgBanks8k = Math.max(1, prgRom.length / 8192);
        prgBanks32k = Math.max(1, prgRom.length / 32768);
        chrMask = chrRom.length - 1;
    }

    // Single latched bank register shared by the simple discrete mappers
    // (UxROM PRG, CNROM CHR, AxROM PRG+mirroring).
    private int simpleBank = 0;

    // Mirroring taken from the iNES header, used until a mapper overrides it.
    private int headerMirroring = PPU.MIRROR_VERTICAL;
    private boolean fourScreen = false;

    private PPU ppu;
    private Controller controller1;
    private APU apu;

    public void setAPU(APU apu) {
        this.apu = apu;
    }

    // === Hooks ===
    private final List<MemoryHook> hooks = new ArrayList<>();

    public void addHook(MemoryHook hook) {
        hooks.add(hook);
    }

    public void removeHook(MemoryHook hook) {
        hooks.remove(hook);
    }

    public void clearHooks() {
        hooks.clear();
    }

    public boolean isNmiAsserted() {
        return ppu != null && ppu.nmiOccurred;
    }

    public boolean willNmiFire(int cpuCycles) {
        return ppu != null && ppu.willNmiFire(cpuCycles);
    }

    public Memory(String romPath) throws IOException {
        this(Files.readAllBytes(Paths.get(romPath)));
    }

    /**
     * Builds from ROM bytes already in memory.
     *
     * <p>The filesystem is not always available - a browser build fetches the
     * ROM over the network, and tests generate one - so parsing is kept
     * separate from loading.
     */
    public Memory(byte[] romData) throws IOException {
        if (romData.length < 16 || romData[0] != 'N' || romData[1] != 'E' || romData[2] != 'S') {
            throw new IOException("Invalid NES ROM file");
        }

        // Parse Header
        int prgBanks = romData[4]; // 16KB units
        int chrBanks = romData[5]; // 8KB units
        int control1 = romData[6];
        int control2 = romData[7];

        mapperID = ((control2 & 0xF0) | ((control1 & 0xF0) >> 4));
        System.out.println("Detected Mapper: " + mapperID);

        // Header bit 0 selects vertical/horizontal; bit 3 requests four-screen VRAM.
        headerMirroring = ((control1 & 0x01) != 0) ? PPU.MIRROR_VERTICAL : PPU.MIRROR_HORIZONTAL;
        fourScreen = (control1 & 0x08) != 0;

        // Load PRG
        int prgSize = prgBanks * 16384;
        prgRom = new byte[prgSize];
        System.arraycopy(romData, 16, prgRom, 0, prgSize);

        // Load CHR
        if (chrBanks > 0) {
            int chrSize = chrBanks * 8192;
            chrRom = new byte[chrSize];
            System.arraycopy(romData, 16 + prgSize, chrRom, 0, chrSize);
            isChrRam = false;
        } else {
            chrRom = new byte[8192]; // 8KB CHR-RAM
            isChrRam = true;
        }

        cacheGeometry();

        // Initialize APU Registers to 0xFF (nestest expects this, likely Open Bus
        // behavior)
        java.util.Arrays.fill(apuIoRegisters, 0xFF);
    }

    public Memory() {
        // Default for testing
        prgRom = new byte[32768];
        chrRom = new byte[1024];
        cacheGeometry();
        java.util.Arrays.fill(apuIoRegisters, 0xFF);
    }

    public void setPPU(PPU ppu) {
        this.ppu = ppu;
        // Apply the cartridge's wiring; MMC1/MMC3 may override it later via their
        // own mirroring registers.
        if (ppu != null) {
            ppu.setMirroring(fourScreen ? PPU.MIRROR_FOUR_SCREEN : headerMirroring);
        }
    }

    public void setController1(Controller controller) {
        this.controller1 = controller;
    }

    // === Cycle clock ===
    // Every 6502 bus access takes exactly one CPU cycle, so driving the PPU/APU
    // from here keeps them in step *within* an instruction rather than only at
    // instruction boundaries. That ordering is what register-timing tests see.

    /** Advances the rest of the system by one CPU cycle. */
    public interface CycleSink {
        void onCpuCycle();
    }

    private CycleSink cycleSink;

    public void setCycleSink(CycleSink sink) {
        this.cycleSink = sink;
    }

    // The PPU advances before the access is sampled: the access happens at the
    // end of the CPU cycle, by which point the PPU has run its three dots.
    private void tickCycle() {
        if (cycleSink != null)
            cycleSink.onCpuCycle();
    }

    /**
     * Reads without consuming a cycle or disturbing bus state. For debuggers,
     * disassembly and execution hooks - anything that inspects memory without
     * the CPU actually driving the bus.
     */
    public int peek(int addr) {
        int address = addr & 0xFFFF;
        if (address < 0x2000)
            return ram[address & 0x07FF] & 0xFF;
        if (address < 0x4020)
            return openBus; // registers have read side effects; don't touch them
        if (address < 0x6000)
            return mapperID != 0 ? expansionRom[address - 0x4020] & 0xFF : openBus;
        if (address < 0x8000)
            return saveRam[address - 0x6000] & 0xFF;
        int prg = readPrg(address);
        return prg != -1 ? prg : openBus;
    }

    // === CPU Memory Map ===

    public int read(int addr) {
        tickCycle();
        int address = addr & 0xFFFF;
        int value = openBus; // Default to open bus

        if (address < 0x2000) { // RAM
            value = ram[address & 0x07FF] & 0xFF;

        } else if (address < 0x4000) { // PPU
            value = ppu != null ? ppu.readRegister(address & 0x2007, openBus) : openBus;

        } else if (address < 0x4020) { // IO
            if (address == 0x4016)
                value = (openBus & 0xE0) | (controller1 != null ? controller1.read() : 0);
            else if (address == 0x4017)
                value = (openBus & 0xE0) | 0x00; // Controller 2 (Not connected)
            else if (address == 0x4014) // OAMDMA usually Open Bus on read
                value = openBus;
            else {
                // Route to APU
                int val = apu != null ? apu.readRegister(address, openBus) : -1;
                if (val != -1)
                    value = val;
            }

        } else if (address < 0x6000) { // Expansion
            if (mapperID != 0) { // Only read if mapper supports it (MMC1 etc)
                value = expansionRom[address - 0x4020] & 0xFF;
            }

        } else if (address < 0x8000) { // Save RAM
            // Writes to $6000-$7FFF are unconditional, so reads must be too.
            // NROM boards can carry PRG-RAM here (Family BASIC, most test ROMs).
            value = saveRam[address - 0x6000] & 0xFF;

        } else { // PRG-ROM $8000-$FFFF
            int prg = readPrg(address);
            if (prg != -1)
                value = prg;
        }

        openBus = value; // Bus decay/update

        // Notify Hooks
        if (!hooks.isEmpty()) {
            for (MemoryHook hook : hooks) {
                hook.onRead(addr, value);
            }
        }

        return value;
    }

    public void write(int addr, int val) {
        tickCycle();
        writeBody(addr, val);
    }

    private void writeBody(int addr, int val) {
        int address = addr & 0xFFFF;
        int value = val & 0xFF;
        openBus = value; // Bus update (Driver is CPU)

        // Notify Hooks
        if (!hooks.isEmpty()) {
            for (MemoryHook hook : hooks) {
                hook.onWrite(address, value);
            }
        }

        if (address < 0x2000) { // RAM
            ram[address & 0x07FF] = (byte) value;

        } else if (address < 0x4000) { // PPU
            if (ppu != null)
                ppu.writeRegister(address & 0x2007, value);

        } else if (address < 0x4020) { // APU/IO
            if (address == 0x4014) {
                dmaTransfer(value);
                return;
            }
            if (address == 0x4016) {
                if (controller1 != null)
                    controller1.write(value);
                return;
            }

            // Route to APU
            if (apu != null) {
                apu.writeRegister(address, value);
            }

        } else if (address < 0x6000) {
            // Expansion

        } else if (address < 0x8000) {
            saveRam[address - 0x6000] = (byte) value;

        } else {
            // Mapper Writes
            writeMapper(address, value);
        }
    }

    // === Mapper Logic ===

    private int readPrg(int address) {
        switch (mapperID) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 4:
            case 7:
                break;
            default:
                return -1; // unsupported mapper
        }

        // Handle Mapper 0 (NROM)
        if (mapperID == 0) {
            // 32k or 16k mirror
            int mask = (prgRom.length > 16384) ? 0x7FFF : 0x3FFF;
            return prgRom[(address - 0x8000) & mask] & 0xFF;
        }

        // Handle Mapper 1 (MMC1)
        if (mapperID == 1) {
            int bankMode = (mmc1Control >> 2) & 0x03;
            int offset = 0;

            if (bankMode == 0 || bankMode == 1) { // 32KB switching
                // Bank is (mmc1PrgBank & 0xFE)
                int bank = (mmc1PrgBank & 0x0E); // Ignore bit 0
                offset = bank * 16384 + (address - 0x8000);
            } else if (bankMode == 2) { // Fix First @ 8000, Switch 16k @ C000
                if (address < 0xC000) {
                    offset = 0 + (address - 0x8000); // Bank 0 fixed
                } else {
                    int bank = mmc1PrgBank & 0x0F;
                    offset = bank * 16384 + (address - 0xC000);
                }
            } else if (bankMode == 3) { // Fix Last @ C000, Switch 16k @ 8000
                if (address < 0xC000) {
                    int bank = mmc1PrgBank & 0x0F;
                    offset = bank * 16384 + (address - 0x8000);
                } else {
                    // Last Bank Fixed
                    // Total 16k banks = length / 16384
                    int lastBank = prgBanks16k - 1;
                    offset = lastBank * 16384 + (address - 0xC000);
                }
            }
            if (offset < prgRom.length)
                return prgRom[offset] & 0xFF;
        }

        // Mapper 2 (UxROM): switchable 16KB at $8000, last bank fixed at $C000.
        if (mapperID == 2) {
            int last = prgBanks16k - 1;
            int bank = (address < 0xC000) ? (simpleBank % prgBanks16k) : last;
            int offset = bank * 16384 + (address & 0x3FFF);
            if (offset < prgRom.length)
                return prgRom[offset] & 0xFF;
        }

        // Mapper 3 (CNROM): PRG is fixed, only CHR switches.
        if (mapperID == 3) {
            int mask = (prgRom.length > 16384) ? 0x7FFF : 0x3FFF;
            return prgRom[(address - 0x8000) & mask] & 0xFF;
        }

        // Mapper 7 (AxROM): 32KB banks, single-screen mirroring.
        if (mapperID == 7) {
            int offset = (simpleBank % prgBanks32k) * 32768 + (address - 0x8000);
            if (offset < prgRom.length)
                return prgRom[offset] & 0xFF;
        }

        // Handle Mapper 4 (MMC3) - 8KB PRG banks
        if (mapperID == 4) {
            int lastBank = prgBanks8k - 1;
            int slot = (address - 0x8000) / 8192; // 0:$8000 1:$A000 2:$C000 3:$E000
            boolean prgMode = (mmc3BankSelect & 0x40) != 0;

            int bank;
            switch (slot) {
                case 0:
                    bank = prgMode ? (lastBank - 1) : mmc3Regs[6];
                    break;
                case 1:
                    bank = mmc3Regs[7];
                    break;
                case 2:
                    bank = prgMode ? mmc3Regs[6] : (lastBank - 1);
                    break;
                default:
                    bank = lastBank;
                    break;
            }

            int offset = (bank % prgBanks8k) * 8192 + (address & 0x1FFF);
            if (offset >= 0 && offset < prgRom.length)
                return prgRom[offset] & 0xFF;
        }

        return -1;
    }

    private void writeMapper(int address, int value) {
        if (mapperID == 4) {
            writeMMC3(address, value);
            return;
        }

        // UxROM / CNROM / AxROM all latch a single bank number from the data
        // written anywhere in $8000-$FFFF.
        if (mapperID == 2) {
            simpleBank = value & 0x0F;
            return;
        }
        if (mapperID == 3) {
            simpleBank = value & 0x03;
            return;
        }
        if (mapperID == 7) {
            simpleBank = value & 0x07;
            if (ppu != null) {
                ppu.setMirroring((value & 0x10) != 0 ? PPU.MIRROR_ONESCREEN_HI : PPU.MIRROR_ONESCREEN_LO);
            }
            return;
        }

        if (mapperID == 1) {
            // MMC1 Logic
            if ((value & 0x80) != 0) {
                // Reset Shift
                currShift = 0;
                shiftCount = 0;
                mmc1Control |= 0x0C; // Reset control
            } else {
                currShift |= ((value & 0x01) << shiftCount);
                shiftCount++;
                if (shiftCount == 5) {
                    int reg = (address >> 13) & 0x03; // 0=Control, 1=Chr0, 2=Chr1, 3=Prg

                    switch (reg) {
                        case 0: // Control (8000-9FFF)
                            mmc1Control = currShift;
                            int mirror = mmc1Control & 0x03;
                            if (ppu != null) {
                                switch (mirror) {
                                    case 0:
                                        ppu.setMirroring(PPU.MIRROR_ONESCREEN_LO);
                                        break;
                                    case 1:
                                        ppu.setMirroring(PPU.MIRROR_ONESCREEN_HI);
                                        break;
                                    case 2:
                                        ppu.setMirroring(PPU.MIRROR_VERTICAL);
                                        break;
                                    case 3:
                                        ppu.setMirroring(PPU.MIRROR_HORIZONTAL);
                                        break;
                                }
                            }
                            break;
                        case 1: // CHR 0 (A000-BFFF)
                            mmc1ChrBank0 = currShift;
                            break;
                        case 2: // CHR 1 (C000-DFFF)
                            mmc1ChrBank1 = currShift;
                            break;
                        case 3: // PRG (E000-FFFF)
                            mmc1PrgBank = currShift;
                            break;
                    }
                    currShift = 0;
                    shiftCount = 0;
                }
            }
        }
    }

    private void writeMMC3(int address, int value) {
        boolean odd = (address & 0x01) != 0;

        if (address < 0xA000) {
            if (!odd) { // $8000: Bank select
                mmc3BankSelect = value;
            } else { // $8001: Bank data
                mmc3Regs[mmc3BankSelect & 0x07] = value;
            }
        } else if (address < 0xC000) {
            if (!odd) { // $A000: Mirroring (ignored when the board is four-screen)
                if (ppu != null && !fourScreen) {
                    ppu.setMirroring((value & 0x01) != 0 ? PPU.MIRROR_HORIZONTAL : PPU.MIRROR_VERTICAL);
                }
            }
            // $A001: PRG-RAM protect - not emulated, writes to $6000-$7FFF always land.
        } else if (address < 0xE000) {
            if (!odd) { // $C000: IRQ latch
                mmc3IrqLatch = value;
            } else { // $C001: IRQ reload
                mmc3IrqCounter = 0;
                mmc3IrqReload = true;
            }
        } else {
            if (!odd) { // $E000: IRQ disable + acknowledge
                mmc3IrqEnabled = false;
                mmc3IrqAsserted = false;
            } else { // $E001: IRQ enable
                mmc3IrqEnabled = true;
            }
        }
    }

    /**
     * Clocks the MMC3 scanline counter. Driven by A12 rising edges on hardware,
     * which during rendering occur once per scanline.
     */
    public void clockMapperScanline() {
        if (mapperID != 4)
            return;

        if (mmc3IrqCounter == 0 || mmc3IrqReload) {
            mmc3IrqCounter = mmc3IrqLatch;
            mmc3IrqReload = false;
        } else {
            mmc3IrqCounter--;
        }

        if (mmc3IrqCounter == 0 && mmc3IrqEnabled) {
            mmc3IrqAsserted = true;
        }
    }

    public boolean isMapperIrqAsserted() {
        return mmc3IrqAsserted;
    }

    /**
     * Maps a PPU pattern-table address to an offset in CHR under MMC3 banking.
     * R0/R1 are 2KB banks (low bit of the bank number ignored), R2-R5 are 1KB.
     * Bank-select bit 7 swaps the two 4KB halves of the pattern table.
     */
    private int mmc3ChrOffset(int address) {
        address &= 0x1FFF;
        int region = address >> 10; // 1KB region, 0-7
        if ((mmc3BankSelect & 0x80) != 0)
            region ^= 4; // A12 inversion

        int bank;
        if (region < 2) {
            bank = (mmc3Regs[0] & 0xFE) + region;
        } else if (region < 4) {
            bank = (mmc3Regs[1] & 0xFE) + (region - 2);
        } else {
            bank = mmc3Regs[region - 2]; // regions 4-7 map to R2-R5
        }

        return (bank * 1024 + (address & 0x3FF)) & chrMask;
    }

    // Helper for PPU to call
    public int readChr(int address) {
        if (mapperID == 0)
            return chrRom[address] & 0xFF;

        if (mapperID == 4)
            return chrRom[mmc3ChrOffset(address)] & 0xFF;

        // UxROM and AxROM use CHR-RAM; CNROM banks 8KB of CHR-ROM.
        if (mapperID == 2 || mapperID == 7)
            return chrRom[address & chrMask] & 0xFF;

        if (mapperID == 3)
            return chrRom[(simpleBank * 8192 + address) & chrMask] & 0xFF;

        if (mapperID == 1) {
            int bankMode = (mmc1Control >> 4) & 0x01;
            int offset = 0;
            if (bankMode == 0) { // 8K Switch
                int bank = mmc1ChrBank0 & 0x1E; // Low bit ignored
                offset = bank * 8192 + address;
            } else { // 4K Switch
                if (address < 0x1000) {
                    offset = mmc1ChrBank0 * 4096 + address;
                } else {
                    offset = mmc1ChrBank1 * 4096 + (address - 0x1000);
                }
            }
            if (offset < chrRom.length) {
                return chrRom[offset] & 0xFF;
            } else {
                // Wrap/Mirror for CHR-RAM safe fallback (e.g. if game selects bank 1 on 8k RAM)
                return chrRom[offset & chrMask] & 0xFF;
            }
        }
        return 0;
    }

    public void writeChr(int address, int value) {
        if (mapperID == 0) {
            if (isChrRam)
                chrRom[address] = (byte) value;
            return;
        }

        if (mapperID == 4) {
            if (isChrRam)
                chrRom[mmc3ChrOffset(address)] = (byte) value;
            return;
        }

        if (mapperID == 2 || mapperID == 3 || mapperID == 7) {
            if (isChrRam)
                chrRom[address & chrMask] = (byte) value;
            return;
        }

        if (mapperID == 1) {
            int bankMode = (mmc1Control >> 4) & 0x01;
            int offset = 0;
            if (bankMode == 0) { // 8K Switch
                int bank = mmc1ChrBank0 & 0x1E;
                offset = bank * 8192 + address;
            } else { // 4K Switch
                if (address < 0x1000) {
                    offset = mmc1ChrBank0 * 4096 + address;
                } else {
                    offset = mmc1ChrBank1 * 4096 + (address - 0x1000);
                }
            }

            // Masking for safety
            offset &= chrMask;

            if (isChrRam) {
                chrRom[offset] = (byte) value;
            }
        }
    }

    private CPU cpu;

    public void setCPU(CPU cpu) {
        this.cpu = cpu;
    }

    private void dmaTransfer(int page) {
        if (cpu != null) {
            cpu.triggerDMA(page);
        }
    }

    public Memory copy() {
        Memory newMem = new Memory(); // Use default constructor
        // Copy Arrays
        System.arraycopy(this.ram, 0, newMem.ram, 0, this.ram.length);
        System.arraycopy(this.saveRam, 0, newMem.saveRam, 0, this.saveRam.length);
        System.arraycopy(this.expansionRom, 0, newMem.expansionRom, 0, this.expansionRom.length);
        System.arraycopy(this.apuIoRegisters, 0, newMem.apuIoRegisters, 0, this.apuIoRegisters.length);
        System.arraycopy(this.oam, 0, newMem.oam, 0, this.oam.length);

        // ROM Data (Reference copy is fine as ROM is immutable)
        newMem.prgRom = this.prgRom; // Immutable
        newMem.chrRom = new byte[this.chrRom.length]; // Mutable if RAM
        System.arraycopy(this.chrRom, 0, newMem.chrRom, 0, this.chrRom.length);
        newMem.isChrRam = this.isChrRam;
        newMem.mapperID = this.mapperID;
        newMem.cacheGeometry();

        // MMC1 State
        newMem.currShift = this.currShift;
        newMem.shiftCount = this.shiftCount;
        newMem.mmc1Control = this.mmc1Control;
        newMem.mmc1ChrBank0 = this.mmc1ChrBank0;
        newMem.mmc1ChrBank1 = this.mmc1ChrBank1;
        newMem.mmc1PrgBank = this.mmc1PrgBank;

        // MMC3 State
        newMem.mmc3BankSelect = this.mmc3BankSelect;
        System.arraycopy(this.mmc3Regs, 0, newMem.mmc3Regs, 0, this.mmc3Regs.length);
        newMem.mmc3IrqLatch = this.mmc3IrqLatch;
        newMem.mmc3IrqCounter = this.mmc3IrqCounter;
        newMem.mmc3IrqReload = this.mmc3IrqReload;
        newMem.mmc3IrqEnabled = this.mmc3IrqEnabled;
        newMem.mmc3IrqAsserted = this.mmc3IrqAsserted;
        newMem.headerMirroring = this.headerMirroring;
        newMem.fourScreen = this.fourScreen;
        newMem.simpleBank = this.simpleBank;

        newMem.openBus = this.openBus;

        return newMem;
    }

    public void fastCopyFrom(Memory source) {
        // Copy Arrays (In-place)
        System.arraycopy(source.ram, 0, this.ram, 0, source.ram.length);
        System.arraycopy(source.saveRam, 0, this.saveRam, 0, source.saveRam.length);
        System.arraycopy(source.expansionRom, 0, this.expansionRom, 0, source.expansionRom.length);
        System.arraycopy(source.apuIoRegisters, 0, this.apuIoRegisters, 0, source.apuIoRegisters.length);
        System.arraycopy(source.oam, 0, this.oam, 0, source.oam.length);

        // Mutable CHR-RAM check
        if (this.chrRom.length != source.chrRom.length) {
            this.chrRom = new byte[source.chrRom.length];
        }
        System.arraycopy(source.chrRom, 0, this.chrRom, 0, source.chrRom.length);

        // Fields
        this.openBus = source.openBus;
        this.mapperID = source.mapperID;
        this.prgRom = source.prgRom; // Safe ref copy
        this.isChrRam = source.isChrRam;
        cacheGeometry();

        // MMC1
        this.currShift = source.currShift;
        this.shiftCount = source.shiftCount;
        this.mmc1Control = source.mmc1Control;
        this.mmc1ChrBank0 = source.mmc1ChrBank0;
        this.mmc1ChrBank1 = source.mmc1ChrBank1;
        this.mmc1PrgBank = source.mmc1PrgBank;

        // MMC3
        this.mmc3BankSelect = source.mmc3BankSelect;
        System.arraycopy(source.mmc3Regs, 0, this.mmc3Regs, 0, source.mmc3Regs.length);
        this.mmc3IrqLatch = source.mmc3IrqLatch;
        this.mmc3IrqCounter = source.mmc3IrqCounter;
        this.mmc3IrqReload = source.mmc3IrqReload;
        this.mmc3IrqEnabled = source.mmc3IrqEnabled;
        this.mmc3IrqAsserted = source.mmc3IrqAsserted;
        this.headerMirroring = source.headerMirroring;
        this.fourScreen = source.fourScreen;
        this.simpleBank = source.simpleBank;
    }

    // === Serialization ===

    public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
        dos.write(ram);
        dos.write(saveRam);
        dos.write(expansionRom);

        // APU Registers are int[], convert to byte-wise or write ints
        // Since they are registers 0-255 usually, byte is enough but array is int.
        // Let's write as ints to be safe/simple.
        for (int val : apuIoRegisters)
            dos.writeInt(val);

        dos.write(oam);

        // CHR RAM/ROM
        // Warning: If CHR is ROM, we don't strictly need to save it unless it's
        // modified (rare bankswitch tricks?)
        // If CHR is RAM, we MUST save it.
        dos.writeBoolean(isChrRam);
        dos.writeInt(chrRom.length);
        if (isChrRam) {
            dos.write(chrRom);
        } else {
            // If ROM, we don't save contents, resolved on load via ROM file
        }

        dos.writeInt(openBus);
        dos.writeInt(mapperID);

        // MMC1
        dos.writeInt(currShift);
        dos.writeInt(shiftCount);
        dos.writeInt(mmc1Control);
        dos.writeInt(mmc1ChrBank0);
        dos.writeInt(mmc1ChrBank1);
        dos.writeInt(mmc1PrgBank);

        // MMC3
        dos.writeInt(mmc3BankSelect);
        for (int r : mmc3Regs)
            dos.writeInt(r);
        dos.writeInt(mmc3IrqLatch);
        dos.writeInt(mmc3IrqCounter);
        dos.writeBoolean(mmc3IrqReload);
        dos.writeBoolean(mmc3IrqEnabled);
        dos.writeBoolean(mmc3IrqAsserted);
        dos.writeInt(headerMirroring);
        dos.writeBoolean(fourScreen);
        dos.writeInt(simpleBank);
    }

    public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
        dis.readFully(ram);
        dis.readFully(saveRam);
        dis.readFully(expansionRom);

        for (int i = 0; i < apuIoRegisters.length; i++)
            apuIoRegisters[i] = dis.readInt();

        dis.readFully(oam);

        boolean savedIsChrRam = dis.readBoolean();
        int savedChrLen = dis.readInt();

        // Validation / Allocation
        if (savedIsChrRam) {
            // If we are currently ROM, we might need to switch or error?
            // Usually internal logic doesn't change isChrRam static property of ROM.
            // But if we are loading state, we expect the allocated memory to match logic.
            if (this.chrRom.length != savedChrLen) {
                this.chrRom = new byte[savedChrLen];
            }
            dis.readFully(this.chrRom);
            this.isChrRam = true;
        } else {
            // It was ROM. We assume our loaded ROM handles initialization.
            // We just ensure we didn't accidentally stay in RAM mode if logic allows?
            // Actually, we should just trust the standard ROM load.
        }

        this.openBus = dis.readInt();
        this.mapperID = dis.readInt();

        this.currShift = dis.readInt();
        this.shiftCount = dis.readInt();
        this.mmc1Control = dis.readInt();
        this.mmc1ChrBank0 = dis.readInt();
        this.mmc1ChrBank1 = dis.readInt();
        this.mmc1PrgBank = dis.readInt();

        this.mmc3BankSelect = dis.readInt();
        for (int i = 0; i < mmc3Regs.length; i++)
            mmc3Regs[i] = dis.readInt();
        this.mmc3IrqLatch = dis.readInt();
        this.mmc3IrqCounter = dis.readInt();
        this.mmc3IrqReload = dis.readBoolean();
        this.mmc3IrqEnabled = dis.readBoolean();
        this.mmc3IrqAsserted = dis.readBoolean();
        this.headerMirroring = dis.readInt();
        this.fourScreen = dis.readBoolean();
        this.simpleBank = dis.readInt();
    }
}
