package com.mochanes.emulator;


public class Disassembler {
    private final Memory memory;

    // Opcode Info
    private static class OpInfo {
        String name;
        int mode; // 0=Imp, 1=Imm, 2=ZP, 3=ZPX, 4=Abs, 5=AbsX, 6=AbsY, 7=IndX, 8=IndY, 9=Rel,
                  // 10=Ind

        OpInfo(String name, int mode) {
            this.name = name;
            this.mode = mode;
        }
    }

    private static final OpInfo[] OPTABLE = new OpInfo[256];

    static {
        // Fill table (Partial list for brevity, ideally would be full)
        // Defaults
        for (int i = 0; i < 256; i++)
            OPTABLE[i] = new OpInfo("???", 0);

        initOps();
    }

    public Disassembler(Memory memory) {
        this.memory = memory;
    }

    // Deprecated constructor for compatibility if needed, but we should migrate
    public Disassembler(NES nes) {
        this.memory = nes.getMemory();
    }

    public String disassemble(int addr) {
        int opcode = memory.peek(addr);
        OpInfo info = OPTABLE[opcode];
        String name = info.name;

        int p1 = memory.peek(addr + 1);
        int p2 = memory.peek(addr + 2);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("$%04X: %02X ", addr, opcode));

        // Add operands
        switch (info.mode) {
            case 0: // Implied / Accumulator
                sb.append("      ").append(name);
                break;
            case 1: // Immediate #$AA
                sb.append(String.format("%02X    ", p1)).append(name).append(String.format(" #$%02X", p1));
                break;
            case 2: // Zero Page $AA
                sb.append(String.format("%02X    ", p1)).append(name).append(String.format(" $%02X", p1));
                break;
            case 3: // Zero Page X $AA,X
                sb.append(String.format("%02X    ", p1)).append(name).append(String.format(" $%02X,X", p1));
                break;
            case 4: // Absolute $AABB
                sb.append(String.format("%02X %02X ", p1, p2)).append(name).append(String.format(" $%02X%02X", p2, p1));
                break;
            case 5: // Absolute X $AABB,X
                sb.append(String.format("%02X %02X ", p1, p2)).append(name)
                        .append(String.format(" $%02X%02X,X", p2, p1));
                break;
            case 6: // Absolute Y $AABB,Y
                sb.append(String.format("%02X %02X ", p1, p2)).append(name)
                        .append(String.format(" $%02X%02X,Y", p2, p1));
                break;
            case 7: // Indirect X ($AA,X)
                sb.append(String.format("%02X    ", p1)).append(name).append(String.format(" ($%02X,X)", p1));
                break;
            case 8: // Indirect Y ($AA),Y
                sb.append(String.format("%02X    ", p1)).append(name).append(String.format(" ($%02X),Y", p1));
                break;
            case 9: // Relative (Branch)
                sb.append(String.format("%02X    ", p1)).append(name).append(String.format(" $%02X", p1)); // Ideally
                                                                                                           // resolve
                                                                                                           // target
                break;
            case 10: // Indirect (JMP)
                sb.append(String.format("%02X %02X ", p1, p2)).append(name)
                        .append(String.format(" ($%02X%02X)", p2, p1));
                break;
            case 11: // Zero Page Y $AA,Y
                sb.append(String.format("%02X    ", p1)).append(name).append(String.format(" $%02X,Y", p1));
                break;
            default:
                sb.append("      ").append(name);
        }

        return sb.toString();
    }

    // Format: "C000 4C F5 C5 JMP $C5F5 "
    // Total Length: 48 chars before Register info?
    // Implementation to match nestest log format exactly
    public String disassembleForLog(int addr) {
        int opcode = memory.peek(addr);
        OpInfo info = OPTABLE[opcode];
        String name = info.name;

        int p1 = memory.peek(addr + 1);
        int p2 = memory.peek(addr + 2);

        StringBuilder hexSb = new StringBuilder();
        StringBuilder asmSb = new StringBuilder();

        hexSb.append(String.format("%04X  %02X ", addr, opcode));

        switch (info.mode) {
            case 0: // Implied
                asmSb.append(name);
                if (name.equals("ASL") || name.equals("LSR") || name.equals("ROL") || name.equals("ROR")) {
                    asmSb.append(" A");
                }
                break;
            case 1: // Immediate
                hexSb.append(String.format("%02X ", p1));
                asmSb.append(name).append(String.format(" #$%02X", p1));
                break;
            case 2: // ZP
                hexSb.append(String.format("%02X ", p1));
                asmSb.append(name).append(String.format(" $%02X", p1));
                break;
            case 3: // ZP,X
                hexSb.append(String.format("%02X ", p1));
                asmSb.append(name).append(String.format(" $%02X,X", p1));
                break;
            case 4: // Abs
                hexSb.append(String.format("%02X %02X ", p1, p2));
                asmSb.append(name).append(String.format(" $%02X%02X", p2, p1));
                break;
            case 5: // Abs,X
                hexSb.append(String.format("%02X %02X ", p1, p2));
                asmSb.append(name).append(String.format(" $%02X%02X,X", p2, p1));
                break;
            case 6: // Abs,Y
                hexSb.append(String.format("%02X %02X ", p1, p2));
                asmSb.append(name).append(String.format(" $%02X%02X,Y", p2, p1));
                break;
            case 7: // (Ind,X)
                hexSb.append(String.format("%02X ", p1));
                asmSb.append(name).append(String.format(" ($%02X,X)", p1));
                break;
            case 8: // (Ind),Y
                hexSb.append(String.format("%02X ", p1));
                asmSb.append(name).append(String.format(" ($%02X),Y", p1));
                break;
            case 9: // Rel
                hexSb.append(String.format("%02X ", p1));
                // Calc target
                // If p1 >= 0x80, it's negative
                int offset = p1;
                if (p1 >= 0x80)
                    offset = p1 - 256;
                // PC ref is actually PC+2
                int target = (addr + 2 + offset) & 0xFFFF;
                asmSb.append(name).append(String.format(" $%04X", target));
                break;
            case 10: // Indirect
                hexSb.append(String.format("%02X %02X ", p1, p2));
                asmSb.append(name).append(String.format(" ($%02X%02X)", p2, p1));
                break;
            case 11: // ZP,Y
                hexSb.append(String.format("%02X ", p1));
                asmSb.append(name).append(String.format(" $%02X,Y", p1));
                break;
        }

        // Padding
        // Hex part should be roughly 16 chars? "C000 4C F5 C5 "
        // Let's rely on fixed padding logic.
        // Nestest Log:
        // C000 4C F5 C5 JMP $C5F5 A:00 X:00 Y:00 P:24 SP:FD PPU: 0, 0 CYC:7

        // Pad hex part to 16 chars?
        // "C000 4C F5 C5 " -> 16 chars?
        // "C000 A2 00 " -> 16 chars?

        String hexStr = hexSb.toString();
        // Ensure hexStr is exactly 16 chars long for alignment? Or just append space.
        // It seems variable length in example "C000 4C " vs "C000 4C F5 C5 "

        while (hexStr.length() < 16) {
            hexStr += " ";
        }

        String asmStr = asmSb.toString();

        // Logic: Return combined string. CPU will append registers.
        // "C000 4C F5 C5 JMP $C5F5 "
        // Total width seems to be 48 chars.

        StringBuilder finalSb = new StringBuilder();
        finalSb.append(hexStr);
        finalSb.append(asmStr);

        while (finalSb.length() < 48) {
            finalSb.append(" ");
        }

        return finalSb.toString();
    }

    public int getInstructionLength(int addr) {
        int opcode = memory.peek(addr);
        OpInfo info = OPTABLE[opcode];
        switch (info.mode) {
            case 0:
                return 1;
            case 1:
                return 2;
            case 2:
                return 2;
            case 3:
                return 2;
            case 4:
                return 3;
            case 5:
                return 3;
            case 6:
                return 3;
            case 7:
                return 2;
            case 8:
                return 2;
            case 9:
                return 2;
            case 10:
                return 3;
            default:
                return 1;
        }
    }

    private static void initOps() {
        // Standard 6502 Opcodes
        set(0x00, "BRK", 0);
        set(0x04, "*NOP", 2); // ZP
        set(0x14, "*NOP", 3); // ZP,X
        set(0x1A, "*NOP", 0); // Implied
        set(0x34, "*NOP", 3); // ZP,X
        set(0x3A, "*NOP", 0); // Implied
        set(0x44, "*NOP", 2); // ZP
        set(0x54, "*NOP", 3); // ZP,X
        set(0x5A, "*NOP", 0); // Implied
        set(0x64, "*NOP", 2); // ZP
        set(0x74, "*NOP", 3); // ZP,X
        set(0x7A, "*NOP", 0); // Implied
        set(0x80, "*NOP", 1); // Imm
        set(0x82, "*NOP", 1);
        set(0x89, "*NOP", 1);
        set(0xC2, "*NOP", 1);
        set(0xD4, "*NOP", 3); // ZP,X
        set(0xDA, "*NOP", 0); // Implied
        set(0xE2, "*NOP", 1); // Imm
        set(0xF4, "*NOP", 3); // ZP,X
        set(0xFA, "*NOP", 0); // Implied
        set(0x0C, "*NOP", 4); // Abs
        set(0x1C, "*NOP", 5); // Abs,X
        set(0x3C, "*NOP", 5); // Abs,X
        set(0x5C, "*NOP", 5); // Abs,X
        set(0x7C, "*NOP", 5); // Abs,X
        set(0xDC, "*NOP", 5); // Abs,X
        set(0xFC, "*NOP", 5); // Abs,X

        // LAX
        set(0xA3, "*LAX", 7); // Ind,X
        set(0xB3, "*LAX", 8); // Ind,Y
        set(0xA7, "*LAX", 2); // ZP
        set(0xB7, "*LAX", 11); // ZP,Y
        set(0xAF, "*LAX", 4); // Abs
        set(0xBF, "*LAX", 6); // Abs,Y

        // SAX
        set(0x83, "*SAX", 7); // Ind,X
        set(0x87, "*SAX", 2); // ZP
        set(0x97, "*SAX", 11); // ZP,Y
        set(0x8F, "*SAX", 4); // Abs

        // SBC (Unofficial)
        set(0xEB, "*SBC", 1); // Imm

        // DCP
        set(0xC7, "*DCP", 2); // ZP
        set(0xD7, "*DCP", 3); // ZP,X
        set(0xCF, "*DCP", 4); // Abs
        set(0xDF, "*DCP", 5); // Abs,X
        set(0xDB, "*DCP", 6); // Abs,Y
        set(0xC3, "*DCP", 7); // Ind,X
        set(0xD3, "*DCP", 8); // Ind,Y

        // ISB
        set(0xE7, "*ISB", 2); // ZP
        set(0xF7, "*ISB", 3); // ZP,X
        set(0xEF, "*ISB", 4); // Abs
        set(0xFF, "*ISB", 5); // Abs,X
        set(0xFB, "*ISB", 6); // Abs,Y
        set(0xE3, "*ISB", 7); // Ind,X
        set(0xF3, "*ISB", 8); // Ind,Y

        // SLO
        set(0x07, "*SLO", 2); // ZP
        set(0x17, "*SLO", 3); // ZP,X
        set(0x0F, "*SLO", 4); // Abs
        set(0x1F, "*SLO", 5); // Abs,X
        set(0x1B, "*SLO", 6); // Abs,Y
        set(0x03, "*SLO", 7); // Ind,X
        set(0x13, "*SLO", 8); // Ind,Y

        // RLA
        set(0x27, "*RLA", 2);
        set(0x37, "*RLA", 3);
        set(0x2F, "*RLA", 4);
        set(0x3F, "*RLA", 5);
        set(0x3B, "*RLA", 6);
        set(0x23, "*RLA", 7);
        set(0x33, "*RLA", 8);

        // SRE
        set(0x47, "*SRE", 2);
        set(0x57, "*SRE", 3);
        set(0x4F, "*SRE", 4);
        set(0x5F, "*SRE", 5);
        set(0x5B, "*SRE", 6);
        set(0x43, "*SRE", 7);
        set(0x53, "*SRE", 8);

        // RRA
        set(0x67, "*RRA", 2);
        set(0x77, "*RRA", 3);
        set(0x6F, "*RRA", 4);
        set(0x7F, "*RRA", 5);
        set(0x7B, "*RRA", 6);
        set(0x63, "*RRA", 7);
        set(0x73, "*RRA", 8);

        set(0x01, "ORA", 7);
        set(0x05, "ORA", 2);
        set(0x06, "ASL", 2);
        set(0x08, "PHP", 0);
        set(0x09, "ORA", 1);
        set(0x0A, "ASL", 0);
        set(0x0D, "ORA", 4);
        set(0x0E, "ASL", 4);
        set(0x10, "BPL", 9);
        set(0x11, "ORA", 8);
        set(0x15, "ORA", 3);
        set(0x16, "ASL", 3);
        set(0x18, "CLC", 0);
        set(0x19, "ORA", 6);
        set(0x1D, "ORA", 5);
        set(0x1E, "ASL", 5);
        set(0x20, "JSR", 4);
        set(0x21, "AND", 7);
        set(0x24, "BIT", 2);
        set(0x25, "AND", 2);
        set(0x26, "ROL", 2);
        set(0x28, "PLP", 0);
        set(0x29, "AND", 1);
        set(0x2A, "ROL", 0);
        set(0x2C, "BIT", 4);
        set(0x2D, "AND", 4);
        set(0x2E, "ROL", 4);
        set(0x30, "BMI", 9);
        set(0x31, "AND", 8);
        set(0x35, "AND", 3);
        set(0x36, "ROL", 3);
        set(0x38, "SEC", 0);
        set(0x39, "AND", 6);
        set(0x3D, "AND", 5);
        set(0x3E, "ROL", 5);
        set(0x40, "RTI", 0);
        set(0x41, "EOR", 7);
        set(0x45, "EOR", 2);
        set(0x46, "LSR", 2);
        set(0x48, "PHA", 0);
        set(0x49, "EOR", 1);
        set(0x4A, "LSR", 0);
        set(0x4C, "JMP", 4);
        set(0x4D, "EOR", 4);
        set(0x4E, "LSR", 4);
        set(0x50, "BVC", 9);
        set(0x51, "EOR", 8);
        set(0x55, "EOR", 3);
        set(0x56, "LSR", 3);
        set(0x58, "CLI", 0);
        set(0x59, "EOR", 6);
        set(0x5D, "EOR", 5);
        set(0x5E, "LSR", 5);
        set(0x60, "RTS", 0);
        set(0x61, "ADC", 7);
        set(0x65, "ADC", 2);
        set(0x66, "ROR", 2);
        set(0x68, "PLA", 0);
        set(0x69, "ADC", 1);
        set(0x6A, "ROR", 0);
        set(0x6C, "JMP", 10);
        set(0x6D, "ADC", 4);
        set(0x6E, "ROR", 4);
        set(0x70, "BVS", 9);
        set(0x71, "ADC", 8);
        set(0x75, "ADC", 3);
        set(0x76, "ROR", 3);
        set(0x78, "SEI", 0);
        set(0x79, "ADC", 6);
        set(0x7D, "ADC", 5);
        set(0x7E, "ROR", 5);
        set(0x81, "STA", 7);
        set(0x84, "STY", 2);
        set(0x85, "STA", 2);
        set(0x86, "STX", 2);
        set(0x88, "DEY", 0);
        set(0x8A, "TXA", 0);
        set(0x8C, "STY", 4);
        set(0x8D, "STA", 4);
        set(0x8E, "STX", 4);
        set(0x90, "BCC", 9);
        set(0x91, "STA", 8);
        set(0x94, "STY", 3);
        set(0x95, "STA", 3);
        set(0x96, "STX", 11);
        set(0x98, "TYA", 0);
        set(0x99, "STA", 6);
        set(0x9A, "TXS", 0);
        set(0x9D, "STA", 5);
        set(0xA0, "LDY", 1);
        set(0xA1, "LDA", 7);
        set(0xA2, "LDX", 1);
        set(0xA4, "LDY", 2);
        set(0xA5, "LDA", 2);
        set(0xA6, "LDX", 2);
        set(0xA8, "TAY", 0);
        set(0xA9, "LDA", 1);
        set(0xAA, "TAX", 0);
        set(0xAC, "LDY", 4);
        set(0xAD, "LDA", 4);
        set(0xAE, "LDX", 4);
        set(0xB0, "BCS", 9);
        set(0xB1, "LDA", 8);
        set(0xB4, "LDY", 3);
        set(0xB5, "LDA", 3);
        set(0xB6, "LDX", 11);
        set(0xB8, "CLV", 0);
        set(0xB9, "LDA", 6);
        set(0xBA, "TSX", 0);
        set(0xBC, "LDY", 5);
        set(0xBD, "LDA", 5);
        set(0xBE, "LDX", 6);
        set(0xC0, "CPY", 1);
        set(0xC1, "CMP", 7);
        set(0xC4, "CPY", 2);
        set(0xC5, "CMP", 2);
        set(0xC6, "DEC", 2);
        set(0xC8, "INY", 0);
        set(0xC9, "CMP", 1);
        set(0xCA, "DEX", 0);
        set(0xCC, "CPY", 4);
        set(0xCD, "CMP", 4);
        set(0xCE, "DEC", 4);
        set(0xD0, "BNE", 9);
        set(0xD1, "CMP", 8);
        set(0xD5, "CMP", 3);
        set(0xD6, "DEC", 3);
        set(0xD8, "CLD", 0);
        set(0xD9, "CMP", 6);
        set(0xDD, "CMP", 5);
        set(0xDE, "DEC", 5);
        set(0xE0, "CPX", 1);
        set(0xE1, "SBC", 7);
        set(0xE4, "CPX", 2);
        set(0xE5, "SBC", 2);
        set(0xE6, "INC", 2);
        set(0xE8, "INX", 0);
        set(0xE9, "SBC", 1);
        set(0xEA, "NOP", 0);
        set(0xEC, "CPX", 4);
        set(0xED, "SBC", 4);
        set(0xEE, "INC", 4);
        set(0xF0, "BEQ", 9);
        set(0xF1, "SBC", 8);
        set(0xF5, "SBC", 3);
        set(0xF6, "INC", 3);
        set(0xF8, "SED", 0);
        set(0xF9, "SBC", 6);
        set(0xFD, "SBC", 5);
        set(0xFE, "INC", 5);

        // Unofficial / Missing handled as ???
    }

    private static void set(int op, String name, int mode) {
        OPTABLE[op] = new OpInfo(name, mode);
    }
}
