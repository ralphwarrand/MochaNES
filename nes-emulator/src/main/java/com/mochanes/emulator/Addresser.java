package com.mochanes.emulator;

public class Addresser {
        // Array for O(1) lookup
        private static final String[] addressingModes = new String[256];

        static {
                // Initialize with default
                for (int i = 0; i < 256; i++) {
                        addressingModes[i] = "Unknown";
                }

                // Group opcodes by addressing mode – both official and unofficial:
                int[][] opcodeGroups = {
                                // Immediate (1-byte operand)
                                { 0x69, 0x29, 0xC9, 0xE0, 0xC0, 0xA9, 0xA2, 0xA0, 0x09, 0x49, 0xE9, 0x80, 0xEB },

                                // ZeroPage (1-byte operand)
                                { 0x65, 0x24, 0x25, 0xC5, 0xC6, 0xE4, 0xC4, 0xA5, 0xA6, 0xA4, 0x05, 0x45, 0x85, 0x86,
                                                0x84, 0xE6, 0xE5,
                                                0x46, 0x06, 0x66, 0x26, 0x04, 0x44, 0x64, 0xA7, 0x87, 0xE7, 0xC7, 0x07,
                                                0x27, 0x47, 0x67 },

                                // ZeroPage,X (1-byte operand; add X)
                                { 0x75, 0x35, 0xD5, 0xF5, 0xB5, 0xB4, 0x15, 0x55, 0x95, 0x94, 0xD6, 0xF6, 0x56, 0x16,
                                                0x76, 0x36, 0x14,
                                                0x34, 0x54, 0x74, 0xD4, 0xF4, 0xD7, 0xF7, 0x17, 0x37, 0x57, 0x77 },

                                // ZeroPage,Y (1-byte operand; add Y)
                                { 0xB6, 0x96, 0xB7, 0x97 },

                                // Absolute (2-byte operand)
                                { 0x6D, 0x2C, 0x20, 0x2D, 0xCD, 0xEC, 0xCC, 0xAD, 0xAE, 0xAC, 0x0D, 0x4D, 0x8D, 0x8E,
                                                0x8C, 0xCE, 0xEE,
                                                0x4C, 0x4E, 0x0E, 0x6E, 0x2E, 0xED, 0x0C, 0xAF, 0x8F, 0xEF, 0xCF, 0xEF,
                                                0x0F, 0x2F, 0x4F, 0x6F },

                                // Absolute,X (2-byte operand; add X)
                                { 0x7D, 0x3D, 0xDD, 0xFD, 0xBD, 0x1D, 0x5D, 0x9D, 0xDE, 0xFE, 0xDF, 0x5E, 0x1E, 0x7E,
                                                0x3E, 0xBC, 0x1C,
                                                0x3C, 0x5C, 0x7C, 0xDC, 0xFC, 0xFF, 0x1F, 0x3F, 0x5F, 0x7F },

                                // Absolute,Y (2-byte operand; add Y)
                                { 0x79, 0x39, 0xD9, 0xF9, 0xB9, 0x19, 0x59, 0x99, 0xBE, 0xDB, 0xBF, 0xFB, 0x1B, 0x3B,
                                                0x5B, 0x7B },

                                // Indirect (only used by JMP)
                                { 0x6C },

                                // Indirect,X (1-byte operand; pre-indexed by X)
                                { 0x61, 0x21, 0xC1, 0x41, 0xA1, 0x81, 0xC3, 0xE1, 0x01, 0xA3, 0x83, 0xE3, 0x03, 0x23,
                                                0x43, 0x63 },

                                // Indirect,Y (1-byte operand; post-indexed by Y)
                                { 0x71, 0x31, 0xD1, 0xF1, 0xB1, 0x11, 0x51, 0x91, 0xD3, 0xB3, 0xD3, 0xF3, 0x13, 0x33,
                                                0x53, 0x73 },

                                // Relative (branch instructions)
                                { 0x90, 0xB0, 0xF0, 0x30, 0xD0, 0x10, 0x50, 0x70 },

                                // Implied (no operand) – also add the one-byte unofficial NOPs here
                                { 0x00, 0x40, 0x60, 0x08, 0x28, 0x48, 0x68, 0x88, 0x98, 0xA8, 0xB8, 0xC8, 0xE8, 0x18,
                                                0x38, 0x58, 0x78,
                                                0xD8, 0xF8, 0xAA, 0xBA, 0xCA, 0xEA, 0x1A, 0x3A, 0x5A, 0x7A, 0xDA,
                                                0xFA },

                                // Accumulator (operand is the accumulator itself)
                                { 0x4A, 0x0A, 0x6A, 0x2A }
                };

                String[] modeNames = {
                                "Immediate", "ZeroPage", "ZeroPageX", "ZeroPageY", "Absolute",
                                "AbsoluteX", "AbsoluteY", "Indirect", "IndirectX", "IndirectY",
                                "Relative", "Implied", "Accumulator"
                };

                for (int i = 0; i < opcodeGroups.length; i++) {
                        for (int opcode : opcodeGroups[i]) {
                                addressingModes[opcode] = modeNames[i];
                        }
                }
        }

        public static String getAddressingMode(int opcode) {
                if (opcode < 0 || opcode > 255)
                        return "Unknown";
                return addressingModes[opcode];
        }
}
