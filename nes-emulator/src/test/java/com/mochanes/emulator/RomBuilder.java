package com.mochanes.emulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Assembles small 6502 programs into iNES images, for tests.
 *
 * <p>The accuracy suite depends on ROMs that cannot be redistributed, so those
 * tests skip on CI and leave the PPU, APU and mappers with no automated cover at
 * all. A ROM built in code has no such problem: it ships with the source, runs
 * everywhere, and can be pointed at whichever corner of the hardware a test
 * cares about.
 *
 * <p>Only the opcodes the tests actually use are implemented. Labels are
 * resolved in a second pass, so branches may refer to targets ahead of them.
 */
final class RomBuilder {

    /** Where PRG-ROM is mapped, and therefore where the program is assembled. */
    static final int ORIGIN = 0x8000;

    private final List<Integer> code = new ArrayList<>();
    private final Map<String, Integer> labels = new HashMap<>();
    private final List<int[]> branchFixups = new ArrayList<>();   // {position, kind}
    private final List<Object[]> wordFixups = new ArrayList<>();  // {position, label}

    private static final int BRANCH = 0;
    private static final int WORD = 1;

    // -------------------------------------------------------------- emitting

    private RomBuilder emit(int... bytes) {
        for (int b : bytes) {
            code.add(b & 0xFF);
        }
        return this;
    }

    RomBuilder label(String name) {
        labels.put(name, ORIGIN + code.size());
        return this;
    }

    // Implied
    RomBuilder sei()  { return emit(0x78); }
    RomBuilder cld()  { return emit(0xD8); }
    RomBuilder txs()  { return emit(0x9A); }
    RomBuilder tax()  { return emit(0xAA); }
    RomBuilder txa()  { return emit(0x8A); }
    RomBuilder tya()  { return emit(0x98); }
    RomBuilder inx()  { return emit(0xE8); }
    RomBuilder iny()  { return emit(0xC8); }

    // Immediate
    RomBuilder ldaImm(int v) { return emit(0xA9, v); }
    RomBuilder ldxImm(int v) { return emit(0xA2, v); }
    RomBuilder ldyImm(int v) { return emit(0xA0, v); }
    RomBuilder cpxImm(int v) { return emit(0xE0, v); }
    RomBuilder cpyImm(int v) { return emit(0xC0, v); }
    RomBuilder adcImm(int v) { return emit(0x69, v); }

    // Zero page
    RomBuilder staZp(int a) { return emit(0x85, a); }
    RomBuilder ldaZp(int a) { return emit(0xA5, a); }
    RomBuilder incZp(int a) { return emit(0xE6, a); }

    /** Absolute,X - needed to fill a table through an index register. */
    RomBuilder staAbsX(int a) { return emit(0x9D, a & 0xFF, (a >> 8) & 0xFF); }

    RomBuilder andImm(int v) { return emit(0x29, v); }

    // Absolute
    RomBuilder staAbs(int a) { return emit(0x8D, a & 0xFF, (a >> 8) & 0xFF); }
    RomBuilder ldaAbs(int a) { return emit(0xAD, a & 0xFF, (a >> 8) & 0xFF); }
    RomBuilder bitAbs(int a) { return emit(0x2C, a & 0xFF, (a >> 8) & 0xFF); }

    /** Branch on not-equal to a label, resolved later. */
    RomBuilder bne(String target) {
        emit(0xD0, 0x00);
        branchFixups.add(new int[] { code.size() - 1, BRANCH });
        pendingBranch.add(target);
        return this;
    }

    /** Branch on plus (N clear), used to poll $2002 for VBlank. */
    RomBuilder bpl(String target) {
        emit(0x10, 0x00);
        branchFixups.add(new int[] { code.size() - 1, BRANCH });
        pendingBranch.add(target);
        return this;
    }

    RomBuilder jmp(String target) {
        emit(0x4C, 0x00, 0x00);
        wordFixups.add(new Object[] { code.size() - 2, target });
        return this;
    }

    private final List<String> pendingBranch = new ArrayList<>();

    // ------------------------------------------------------------- packaging

    /**
     * Produces a 16KB NROM image with CHR-RAM.
     *
     * <p>CHR-RAM rather than CHR-ROM so the program can write its own pattern
     * data through {@code $2007}, which exercises that path too.
     *
     * @param vertical vertical mirroring when true, horizontal otherwise
     */
    byte[] build(boolean vertical) {
        return build(vertical, 0, null);
    }

    /**
     * Builds with a chosen mapper and, optionally, real CHR-ROM.
     *
     * <p>CHR-ROM rather than CHR-RAM is what makes bank switching testable:
     * the banks have to exist in the file, since a switchable bank cannot be
     * filled through {@code $2007}.
     *
     * @param mapper iNES mapper number
     * @param chrRom CHR data, a multiple of 8KB, or null for 8KB of CHR-RAM
     */
    byte[] build(boolean vertical, int mapper, byte[] chrRom) {
        resolve();

        byte[] prg = new byte[0x4000];
        for (int i = 0; i < code.size(); i++) {
            prg[i] = (byte) (int) code.get(i);
        }

        // Vectors sit at the top of the bank: NMI, RESET, IRQ.
        int nmi = labels.getOrDefault("nmi", ORIGIN);
        prg[0x3FFA] = (byte) (nmi & 0xFF);
        prg[0x3FFB] = (byte) ((nmi >> 8) & 0xFF);
        prg[0x3FFC] = (byte) (ORIGIN & 0xFF);
        prg[0x3FFD] = (byte) ((ORIGIN >> 8) & 0xFF);
        prg[0x3FFE] = (byte) (nmi & 0xFF);
        prg[0x3FFF] = (byte) ((nmi >> 8) & 0xFF);

        int chrLen = chrRom == null ? 0 : chrRom.length;
        byte[] rom = new byte[16 + prg.length + chrLen];
        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = 1;                                  // 1 x 16KB PRG
        rom[5] = (byte) (chrLen / 8192);             // 0 CHR banks means CHR-RAM
        rom[6] = (byte) (((mapper & 0x0F) << 4) | (vertical ? 0x01 : 0x00));
        rom[7] = (byte) (mapper & 0xF0);
        System.arraycopy(prg, 0, rom, 16, prg.length);
        if (chrRom != null) {
            System.arraycopy(chrRom, 0, rom, 16 + prg.length, chrLen);
        }
        return rom;
    }

    private void resolve() {
        for (int i = 0; i < branchFixups.size(); i++) {
            int pos = branchFixups.get(i)[0];
            String target = pendingBranch.get(i);
            Integer dest = labels.get(target);
            if (dest == null) {
                throw new IllegalStateException("undefined label: " + target);
            }
            int from = ORIGIN + pos + 1;             // PC after the operand
            int delta = dest - from;
            if (delta < -128 || delta > 127) {
                throw new IllegalStateException("branch out of range to " + target);
            }
            code.set(pos, delta & 0xFF);
        }
        for (Object[] fix : wordFixups) {
            int pos = (Integer) fix[0];
            Integer dest = labels.get((String) fix[1]);
            if (dest == null) {
                throw new IllegalStateException("undefined label: " + fix[1]);
            }
            code.set(pos, dest & 0xFF);
            code.set(pos + 1, (dest >> 8) & 0xFF);
        }
    }
}
