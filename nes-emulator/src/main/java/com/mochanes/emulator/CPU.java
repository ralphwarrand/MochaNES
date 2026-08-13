package com.mochanes.emulator;

import com.mochanes.emulator.hooks.ExecutionHook;
import java.util.ArrayList;
import java.util.List;

public class CPU {

    // === Definitions
    // =================================================================================================

    private static final int STACK_START = 0xFD; // Default NES stack start ($01FD)

    public static final String[] OP_NAMES = {
            "BRK", "ORA", "KIL", "SLO", "NOP", "ORA", "ASL", "SLO", "PHP", "ORA", "ASL", "ANC", "NOP", "ORA", "ASL",
            "SLO",
            "BPL", "ORA", "KIL", "SLO", "NOP", "ORA", "ASL", "SLO", "CLC", "ORA", "NOP", "SLO", "NOP", "ORA", "ASL",
            "SLO",
            "JSR", "AND", "KIL", "RLA", "BIT", "AND", "ROL", "RLA", "PLP", "AND", "ROL", "ANC", "BIT", "AND", "ROL",
            "RLA",
            "BMI", "AND", "KIL", "RLA", "NOP", "AND", "ROL", "RLA", "SEC", "AND", "NOP", "RLA", "NOP", "AND", "ROL",
            "RLA",
            "RTI", "EOR", "KIL", "SRE", "NOP", "EOR", "LSR", "SRE", "PHA", "EOR", "LSR", "ALR", "JMP", "EOR", "LSR",
            "SRE",
            "BVC", "EOR", "KIL", "SRE", "NOP", "EOR", "LSR", "SRE", "CLI", "EOR", "NOP", "SRE", "NOP", "EOR", "LSR",
            "SRE",
            "RTS", "ADC", "KIL", "RRA", "NOP", "ADC", "ROR", "RRA", "PLA", "ADC", "ROR", "ARR", "JMP", "ADC", "ROR",
            "RRA",
            "BVS", "ADC", "KIL", "RRA", "NOP", "ADC", "ROR", "RRA", "SEI", "ADC", "NOP", "RRA", "NOP", "ADC", "ROR",
            "RRA",
            "NOP", "STA", "NOP", "SAX", "STY", "STA", "STX", "SAX", "DEY", "NOP", "TXA", "XAA", "STY", "STA", "STX",
            "SAX",
            "BCC", "STA", "KIL", "AHX", "STY", "STA", "STX", "SAX", "TYA", "STA", "TXS", "TAS", "SHY", "STA", "SHX",
            "AHX",
            "LDY", "LDA", "LDX", "LAX", "LDY", "LDA", "LDX", "LAX", "TAY", "LDA", "TAX", "LAX", "LDY", "LDA", "LDX",
            "LAX",
            "BCS", "LDA", "KIL", "LAX", "LDY", "LDA", "LDX", "LAX", "CLV", "LDA", "TSX", "LAS", "LDY", "LDA", "LDX",
            "LAX",
            "CPY", "CMP", "NOP", "DCP", "CPY", "CMP", "DEC", "DCP", "INY", "CMP", "DEX", "AXS", "CPY", "CMP", "DEC",
            "DCP",
            "BNE", "CMP", "KIL", "DCP", "NOP", "CMP", "DEC", "DCP", "CLD", "CMP", "NOP", "DCP", "NOP", "CMP", "DEC",
            "DCP",
            "CPX", "SBC", "NOP", "ISB", "CPX", "SBC", "INC", "ISB", "INX", "SBC", "NOP", "SBC", "CPX", "SBC", "INC",
            "ISB",
            "BEQ", "SBC", "KIL", "ISB", "NOP", "SBC", "INC", "ISB", "SED", "SBC", "NOP", "ISB", "NOP", "SBC", "INC",
            "ISB"
    };

    public static final int[] OP_CYCLES = {
            7, 6, 0, 8, 3, 3, 5, 5, 3, 2, 2, 2, 4, 4, 6, 6,
            2, 5, 0, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            6, 6, 0, 8, 3, 3, 5, 5, 4, 2, 2, 2, 4, 4, 6, 6,
            2, 5, 0, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            6, 6, 0, 8, 3, 3, 5, 5, 3, 2, 2, 2, 3, 4, 6, 6,
            2, 5, 0, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            6, 6, 0, 8, 3, 3, 5, 5, 4, 2, 2, 2, 5, 4, 6, 6,
            2, 5, 0, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4,
            2, 6, 0, 6, 4, 4, 4, 4, 2, 5, 2, 5, 5, 5, 5, 5,
            2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4,
            2, 5, 0, 5, 4, 4, 4, 4, 2, 4, 2, 4, 4, 4, 4, 4,
            2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6,
            2, 5, 0, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6,
            2, 5, 0, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7
    };

    // === CPU Components
    // ==============================================================================================

    private int registers; // Register bitfield (32-bit integer)
    public int PC = 0; // Program Counter
    private int flags = 0x24; // Status Flags (Initially I=1, U=1)
    private long totalCycles = 0; // Total CPU cycles executed
    private boolean loggingEnabled = false;

    private final Memory memory;

    // Helper for correct timing on page crosses
    // Flag bit positions
    private static final int FLAG_C = 0; // Carry
    private static final int FLAG_Z = 1; // Zero
    private static final int FLAG_I = 2; // Interrupt Disable
    private static final int FLAG_D = 3; // Decimal Mode
    private static final int FLAG_B = 4; // Break Command
    private static final int FLAG_V = 6; // Overflow
    private static final int FLAG_N = 7; // Negative

    // === CPU Functions
    // ===============================================================================================

    public CPU(Memory mem) {
        this.memory = mem;
        this.disassembler = new Disassembler(mem); // Instantiate Disassembler with Memory
        reset();
    }

    private final Disassembler disassembler;

    private void log() {
        String asm = disassembler.disassembleForLog(PC);

        // Append Registers
        // Standard NES Log Format: A:00 X:00 Y:00 P:24 SP:FD CYC:7

        String regs = String.format("A:%02X X:%02X Y:%02X P:%02X SP:%02X CYC:%d",
                getReg(2), getReg(0), getReg(1),
                getFlags(), getSP(), totalCycles);

        System.out.println(asm + regs);
    }

    public void setLoggingEnabled(boolean enabled) {
        this.loggingEnabled = enabled;
    }

    public long getTotalCycles() {
        return totalCycles;
    }

    public void reset(Integer startPC) {
        if (startPC != null) {
            PC = startPC;
        } else {
            PC = (memory.read(0xFFFC) & 0xFF) | ((memory.read(0xFFFD) & 0xFF) << 8);
        }

        flags = 0x24; // IRQ Disabled (I=1), Unused Bit (5) always set
        setSP(STACK_START); // Set stack pointer using bitfield
        totalCycles = 7; // Initialization takes 7 cycles
    }

    public void reset() { // Default NES CPU behaviour
        reset(null); // Calls the version with an argument, defaulting to the reset vector
    }

    public CPU copy(Memory newMemory) {
        CPU newCPU = new CPU(newMemory);
        newCPU.registers = this.registers;

        newCPU.PC = this.PC;
        newCPU.flags = this.flags;
        newCPU.totalCycles = this.totalCycles;
        newCPU.loggingEnabled = this.loggingEnabled;

        // Internal state
        newCPU.interruptDelay = this.interruptDelay;
        newCPU.prevIFlag = this.prevIFlag;
        newCPU.dmaActive = this.dmaActive;
        newCPU.dmaPage = this.dmaPage;
        newCPU.dmaByte = this.dmaByte;
        newCPU.dmaStarted = this.dmaStarted;
        newCPU.nmiPrevious = this.nmiPrevious;
        newCPU.nmiPending = this.nmiPending;

        // Copy remaining state
        newCPU.registers = this.registers;

        return newCPU;
    }

    public void fastCopyFrom(CPU source) {
        this.registers = source.registers;
        this.PC = source.PC;
        this.flags = source.flags;
        this.totalCycles = source.totalCycles;
        this.loggingEnabled = source.loggingEnabled;

        this.interruptDelay = source.interruptDelay;
        this.prevIFlag = source.prevIFlag;
        this.dmaActive = source.dmaActive;
        this.dmaPage = source.dmaPage;
        this.dmaByte = source.dmaByte;
        this.dmaStarted = source.dmaStarted;
        this.nmiPrevious = source.nmiPrevious;
        this.nmiPending = source.nmiPending;
    }

    // === Serialization ===

    public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
        dos.writeInt(registers);
        dos.writeInt(PC);
        dos.writeInt(flags);
        dos.writeLong(totalCycles);

        // Internal State
        dos.writeInt(interruptDelay);
        dos.writeInt(prevIFlag);

        // DMA
        dos.writeBoolean(dmaActive);
        dos.writeInt(dmaPage);
        dos.writeInt(dmaByte);
        dos.writeBoolean(dmaStarted);

        dos.writeBoolean(nmiPrevious);
    }

    public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
        this.registers = dis.readInt();
        this.PC = dis.readInt();
        this.flags = dis.readInt();
        this.totalCycles = dis.readLong();

        this.interruptDelay = dis.readInt();
        this.prevIFlag = dis.readInt();

        this.dmaActive = dis.readBoolean();
        this.dmaPage = dis.readInt();
        this.dmaByte = dis.readInt();
        this.dmaStarted = dis.readBoolean();

        this.nmiPrevious = dis.readBoolean();
    }

    private void branch(boolean condition) {
        if (condition) {
            int offset = memory.read(PC); // Cycle 2: Fetch offset.
            // Cycle 3: Dummy fetch of next instruction byte
            memory.read(PC + 1);
            totalCycles++; // Account for Cycle 3

            int basePC = PC + 1;
            // Java bytes are signed (-128 to 127), so casting handles 0x80-0xFF correctly
            // as negative
            int signedOffset = (byte) offset;
            int newPC = basePC + signedOffset;

            // Page crossing check
            if ((basePC & 0xFF00) != (newPC & 0xFF00)) {
                // Cycle 4: Page Crossing Dummy Read (Old High, New Low)
                memory.read((basePC & 0xFF00) | (newPC & 0xFF));
                totalCycles++; // Account for Cycle 4
            }

            PC = newPC;
        } else {
            // Branch not taken
            // Cycle 2: Fetch offset (and act like we skipped it)
            memory.read(PC++);
        }
        // Cycle 2 for not taken is accounted for by OP_CYCLES (2),
        // Taken is (3) base + 1 above = 3? OP_CYCLES for branch is 2.
        // So Taken adds 1 (Cycle 3). Page adds 1 (Cycle 4).
        // Correct.
    }

    // === Interrupts ===

    public void burnCycles(int cycles) {
        totalCycles += cycles;
    }

    public void nmi() {
        memory.read(PC); // Cycle 1: Dummy Read Opcode (Internal operation, but bus reads PC)
        memory.read(PC); // Cycle 2: Dummy Read PC (Instruction fetch ignored)

        push((PC >> 8) & 0xFF); // Cycle 3: Push PCH
        push(PC & 0xFF); // Cycle 4: Push PCL
        push(flags | 0x20); // Cycle 5: Push P (Bit 5 set)

        flags |= (1 << FLAG_I);

        int low = memory.read(0xFFFA); // Cycle 6: Fetch Vector Low
        int high = memory.read(0xFFFB); // Cycle 7: Fetch Vector High
        PC = (low | (high << 8));
        totalCycles += 7;
    }

    public void irq() {
        // If interrupt latency is active (CLI/SEI/PLP just ran), use the PREVIOUS I
        // flag state.
        // This ensures CLI delays enabling IRQ by 1 op, and SEI delays disabling by 1
        // op.
        int effectiveI = (interruptDelay > 0) ? prevIFlag : getFlag(FLAG_I);

        if (effectiveI == 0 && !dmaActive) { // Also block if DMA (Safety, though Main checks too)
            memory.read(PC); // Cycle 1
            memory.read(PC); // Cycle 2

            // Push PC and Status
            push((PC >> 8) & 0xFF);
            push(PC & 0xFF);

            // Push Status (No B flag)
            push(flags | 0x20); // Bit 5 always set, B clear

            setFlag(FLAG_I, 1);

            // NMI Hijack Check: an NMI edge latched during the push cycles
            // steals the vector fetch. The latch is what counts, not the PPU's
            // NMI level - that stays high for the whole of VBlank, which would
            // hijack every interrupt taken anywhere in those ~2270 cycles.
            // pollNmiLine runs on every bus cycle, so by the time control
            // reaches here nmiPending already covers an edge that arrived
            // mid-instruction, and no lookahead is needed.
            int vector = 0xFFFE; // Default IRQ
            if (nmiPending) {
                vector = 0xFFFA; // NMI Hijack!
                suppressNextNMI(); // Artificial edge consumption
            }

            int low = memory.read(vector);
            int high = memory.read(vector + 1);
            PC = (low | (high << 8));

            totalCycles += 7;
        }
    }

    public void brk() {
        memory.read(PC++); // Dummy read of signature byte

        push((PC >> 8) & 0xFF);
        push(PC & 0xFF);
        push(flags | 0x30); // Set B (Bit 4) and Unused (Bit 5)

        setFlag(FLAG_I, 1);

        // NMI Hijack Check for BRK - see irq() for why this is the latch.
        int vector = 0xFFFE;
        if (nmiPending) {
            vector = 0xFFFA;
            suppressNextNMI();
        }

        int low = memory.read(vector);
        int high = memory.read(vector + 1);
        PC = (low | (high << 8));
        // No cycle charge here: BRK is dispatched as a normal opcode, so
        // executeNextInstruction has already added OP_CYCLES[0x00] = 7. Adding
        // it again made every BRK cost 14.
    }

    // === DMA State ===
    private boolean dmaActive = false;
    private int dmaPage = 0;
    private int dmaByte = 0;
    private boolean dmaStarted = false;

    public void triggerDMA(int page) {
        dmaActive = true;
        dmaPage = page;
        dmaByte = 0;
        dmaStarted = false;
    }

    public boolean isDmaActive() {
        return dmaActive;
    }

    // === Interrupt Latency ===
    private int interruptDelay = 0;
    private int prevIFlag = 1;

    // === Hooks ===
    private final List<ExecutionHook> hooks = new ArrayList<>();

    public void addHook(ExecutionHook hook) {
        hooks.add(hook);
    }

    public void removeHook(ExecutionHook hook) {
        hooks.remove(hook);
    }

    public void clearHooks() {
        hooks.clear();
    }

    // === NMI Edge Detection ===
    private boolean nmiPrevious = false;

    public void setNMI(boolean active) {
        if (active && !nmiPrevious) {
            nmi();
        }
        nmiPrevious = active;
    }

    /** True once an NMI edge has been seen and not yet serviced. */
    private boolean nmiPending = false;

    /**
     * Samples the NMI input for one cycle, latching a low-to-high edge. The
     * NMI input is edge-triggered, so the latch - not the current level - is
     * what decides whether the handler runs.
     */
    public void pollNmiLine(boolean active) {
        if (active && !nmiPrevious) {
            nmiPending = true;
        }
        nmiPrevious = active;
    }

    /** True once an NMI edge has been latched and not yet serviced. */
    public boolean isNmiPending() {
        return nmiPending;
    }

    /** Runs the NMI handler if an edge was latched during the instruction. */
    public void serviceNmi() {
        if (nmiPending) {
            nmiPending = false;
            nmi();
        }
    }

    // Internal helper to suppress next NMI (used by Hijack)
    private void suppressNextNMI() {
        nmiPrevious = true;
        // The hijack has already vectored through $FFFA, so any edge latched
        // for this NMI is spent. Leaving it pending would run the handler a
        // second time at the next instruction boundary.
        nmiPending = false;
    }

    // === Execution ===

    public int executeNextInstruction() {
        // Capture I flag state BEFORE instruction execution
        // This is used for "Delayed" interrupt handling (CLI/SEI/PLP)
        prevIFlag = getFlag(FLAG_I);

        if (interruptDelay > 0) {
            interruptDelay--;
        }

        if (dmaActive) {
            // cycle-stealing DMA
            if (!dmaStarted) {
                // Alignment Cycle(s)
                // 1 Dummy Read (Get)
                // +1 if on Even cycle (We want to start on Odd cycle so Write is Even)
                memory.read(PC); // Dummy read (Cycle 1)
                totalCycles++;
                int cycles = 1;

                if ((totalCycles & 1) == 0) {
                    totalCycles++; // Alignment penalty (Cycle 2)
                    cycles++;
                }
                dmaStarted = true;
                return cycles;
            } else {
                // Read + Write Cycle (2 Cycles)
                int val = memory.read((dmaPage << 8) | dmaByte);
                memory.write(0x2004, val); // Write to PPU OAMData port

                totalCycles += 2; // Read(1) + Write(1)
                dmaByte++;

                if (dmaByte > 255) {
                    dmaActive = false;
                    dmaStarted = false;
                }
                return 2;
            }
        }

        if (loggingEnabled) {
            log();
        }

        // Fetch Opcode
        int opcode = memory.read(PC);

        // Notify Hooks (Before PC increment). These are observational reads, so
        // they must not drive the bus - peek() costs no cycle.
        if (!hooks.isEmpty()) {
            int op1 = memory.peek(PC + 1);
            int op2 = memory.peek(PC + 2);
            for (ExecutionHook hook : hooks) {
                hook.onExecute(PC, opcode, op1, op2);
            }
        }

        int cycles = OP_CYCLES[opcode];
        if (cycles == 0) {
            System.err.println("Zero-cycle opcode detected: 0x" + Integer.toHexString(opcode) + " at PC: 0x"
                    + Integer.toHexString(PC));
            cycles = 1; // Prevent infinite loop
        }

        // Update Total Cycles at START (Simulate writes happening at end of cycle
        // structure)
        totalCycles += cycles;

        PC++;

        long startCpu = com.mochanes.emulator.performance.Metrics.start();

        // Execute Opcode
        switch (opcode) {
            // LDA
            case 0xA9:
                lda(imm());
                break;

            case 0xA5:
                lda(zp());
                break;
            case 0xB5:
                lda(zpx());
                break;
            case 0xAD:
                lda(abs());
                break;
            case 0xBD:
                lda(abx());
                break;
            case 0xB9:
                lda(aby());
                break;
            case 0xA1:
                lda(izx());
                break;
            case 0xB1:
                lda(izy());
                break;

            // LDX
            case 0xA2:
                ldx(imm());
                break;
            case 0xA6:
                ldx(zp());
                break;
            case 0xB6:
                ldx(zpy());
                break;
            case 0xAE:
                ldx(abs());
                break;
            case 0xBE:
                ldx(aby());
                break;

            // LDY
            case 0xA0:
                ldy(imm());
                break;
            case 0xA4:
                ldy(zp());
                break;
            case 0xB4:
                ldy(zpx());
                break;
            case 0xAC:
                ldy(abs());
                break;
            case 0xBC:
                ldy(abx());
                break;

            // STA
            case 0x85:
                sta(zp());
                break;
            case 0x95:
                sta(zpx());
                break;
            case 0x8D:
                sta(abs());
                break;
            case 0x9D:
                sta(abxWrite());
                break;
            case 0x99:
                sta(abyWrite());
                break;
            case 0x81:
                sta(izx());
                break;
            case 0x91:
                sta(izyWrite());
                break;

            // STX
            case 0x86:
                stx(zp());
                break;
            case 0x96:
                stx(zpy());
                break;
            case 0x8E:
                stx(abs());
                break;

            // STY
            case 0x84:
                sty(zp());
                break;
            case 0x94:
                sty(zpx());
                break;
            case 0x8C:
                sty(abs());
                break;

            // TAX, TAY, TSX, TXA, TXS, TYA
            case 0xAA:
                tax();
                break;
            case 0xA8:
                tay();
                break;
            case 0xBA:
                tsx();
                break;
            case 0x8A:
                txa();
                break;
            case 0x9A:
                txs();
                break;
            case 0x98:
                tya();
                break;

            // PHA, PHP, PLA, PLP
            case 0x48:
                pha();
                break;
            case 0x08:
                php();
                break;
            case 0x68:
                pla();
                break;
            case 0x28:
                plp();
                interruptDelay = 1;
                break;

            // AND
            case 0x29:
                and(imm());
                break;
            case 0x25:
                and(zp());
                break;
            case 0x35:
                and(zpx());
                break;
            case 0x2D:
                and(abs());
                break;
            case 0x3D:
                and(abx());
                break;
            case 0x39:
                and(aby());
                break;
            case 0x21:
                and(izx());
                break;
            case 0x31:
                and(izy());
                break;

            // EOR
            case 0x49:
                eor(imm());
                break;
            case 0x45:
                eor(zp());
                break;
            case 0x55:
                eor(zpx());
                break;
            case 0x4D:
                eor(abs());
                break;
            case 0x5D:
                eor(abx());
                break;
            case 0x59:
                eor(aby());
                break;
            case 0x41:
                eor(izx());
                break;
            case 0x51:
                eor(izy());
                break;

            // ORA
            case 0x09:
                ora(imm());
                break;
            case 0x05:
                ora(zp());
                break;
            case 0x15:
                ora(zpx());
                break;
            case 0x0D:
                ora(abs());
                break;
            case 0x1D:
                ora(abx());
                break;
            case 0x19:
                ora(aby());
                break;
            case 0x01:
                ora(izx());
                break;
            case 0x11:
                ora(izy());
                break;

            // BIT
            case 0x24:
                bit(zp());
                break;
            case 0x2C:
                bit(abs());
                break;

            // ADC
            case 0x69:
                adc(imm());
                break;
            case 0x65:
                adc(zp());
                break;
            case 0x75:
                adc(zpx());
                break;
            case 0x6D:
                adc(abs());
                break;
            case 0x7D:
                adc(abx());
                break;
            case 0x79:
                adc(aby());
                break;
            case 0x61:
                adc(izx());
                break;
            case 0x71:
                adc(izy());
                break;

            // SBC
            case 0xE9:
                sbc(imm());
                break;
            case 0xE5:
                sbc(zp());
                break;
            case 0xF5:
                sbc(zpx());
                break;
            case 0xED:
                sbc(abs());
                break;
            case 0xFD:
                sbc(abx());
                break;
            case 0xF9:
                sbc(aby());
                break;
            case 0xE1:
                sbc(izx());
                break;
            case 0xF1:
                sbc(izy());
                break;

            // CMP
            case 0xC9:
                cmp(imm());
                break;
            case 0xC5:
                cmp(zp());
                break;
            case 0xD5:
                cmp(zpx());
                break;
            case 0xCD:
                cmp(abs());
                break;
            case 0xDD:
                cmp(abx());
                break;
            case 0xD9:
                cmp(aby());
                break;
            case 0xC1:
                cmp(izx());
                break;
            case 0xD1:
                cmp(izy());
                break;

            // CPX
            case 0xE0:
                cpx(imm());
                break;
            case 0xE4:
                cpx(zp());
                break;
            case 0xEC:
                cpx(abs());
                break;

            // CPY
            case 0xC0:
                cpy(imm());
                break;
            case 0xC4:
                cpy(zp());
                break;
            case 0xCC:
                cpy(abs());
                break;

            // INC
            case 0xE6:
                inc(zp());
                break;
            case 0xF6:
                inc(zpx());
                break;
            case 0xEE:
                inc(abs());
                break;
            case 0xFE:
                inc(abxWrite());
                break;

            // DEC
            case 0xC6:
                dec(zp());
                break;
            case 0xD6:
                dec(zpx());
                break;
            case 0xCE:
                dec(abs());
                break;
            case 0xDE:
                dec(abxWrite());
                break;

            // ASL
            case 0x0A:
                asl_acc();
                break;
            case 0x06:
                asl(zp());
                break;
            case 0x16:
                asl(zpx());
                break;
            case 0x0E:
                asl(abs());
                break;
            case 0x1E:
                asl(abxWrite());
                break;
            case 0x1E_99:
                break; // Fake opcode hack?

            // LSR
            case 0x4A:
                lsr_acc();
                break;
            case 0x46:
                lsr(zp());
                break;
            case 0x56:
                lsr(zpx());
                break;
            case 0x4E:
                lsr(abs());
                break;
            case 0x5E:
                lsr(abxWrite());
                break;

            // INX, DEX, INY, DEY
            case 0xE8:
                inx();
                break;
            case 0xCA:
                dex();
                break;
            case 0xC8:
                iny();
                break;
            case 0x88:
                dey();
                break;

            // ROL
            case 0x2A:
                rol_acc();
                break;
            case 0x26:
                rol(zp());
                break;
            case 0x36:
                rol(zpx());
                break;
            case 0x2E:
                rol(abs());
                break;
            case 0x3E:
                rol(abxWrite());
                break;

            // ROR
            case 0x6A:
                ror_acc();
                break;
            case 0x66:
                ror(zp());
                break;
            case 0x76:
                ror(zpx());
                break;
            case 0x6E:
                ror(abs());
                break;
            case 0x7E:
                ror(abxWrite());
                break;

            // BRK
            case 0x00:
                brk();
                break;

            // RTI
            case 0x40:
                rti();
                break;

            // JMP

            // JMP
            case 0x4C:
                jmp(abs());
                break;
            case 0x6C:
                jmp(ind());
                break;

            // JSR, RTS
            case 0x20:
                jsr();
                break;
            case 0x60:
                rts();
                break;

            // Branches
            case 0x90:
                branch(getFlag(FLAG_C) == 0);
                break; // BCC
            case 0xB0:
                branch(getFlag(FLAG_C) == 1);
                break; // BCS
            case 0xF0:
                branch(getFlag(FLAG_Z) == 1);
                break; // BEQ
            case 0x30:
                branch(getFlag(FLAG_N) == 1);
                break; // BMI
            case 0xD0:
                branch(getFlag(FLAG_Z) == 0);
                break; // BNE
            case 0x10:
                branch(getFlag(FLAG_N) == 0);
                break; // BPL
            case 0x50:
                branch(getFlag(FLAG_V) == 0);
                break; // BVC
            case 0x70:
                branch(getFlag(FLAG_V) == 1);
                break; // BVS

            // Status Flag Changes
            case 0x18:
                dummyReadPC();
                setFlag(FLAG_C, 0);
                break; // CLC
            case 0xD8:
                dummyReadPC();
                setFlag(FLAG_D, 0);
                break; // CLD
            case 0x58:
                dummyReadPC();
                setFlag(FLAG_I, 0);
                interruptDelay = 1;
                break; // CLI
            case 0xB8:
                dummyReadPC();
                setFlag(FLAG_V, 0);
                break; // CLV
            case 0x38:
                dummyReadPC();
                setFlag(FLAG_C, 1);
                break; // SEC
            case 0xF8:
                dummyReadPC();
                setFlag(FLAG_D, 1);
                break; // SED
            case 0x78:
                dummyReadPC();
                setFlag(FLAG_I, 1);
                // SEI is delayed exactly like CLI and PLP: the interrupt poll
                // for this instruction already happened with I clear, so one
                // pending IRQ still gets through after it.
                interruptDelay = 1;
                break; // SEI

            // Old Unofficial NOPs removed (Duplicate)
            // See Unofficial Block below
            // NOP
            case 0xEA:
            case 0x1A:
            case 0x3A:
            case 0x5A:
            case 0x7A:
            case 0xDA:
            case 0xFA:
                dummyReadPC();
                break;

            // ANC (AND #i + C=N)
            case 0x0B:
            case 0x2B:
                anc(imm());
                break;

            // ALR (AND #i + LSR)
            case 0x4B:
                alr(imm());
                break;

            // ARR (AND #i + ROR)
            case 0x6B:
                arr(imm());
                break;

            // AXS (CMP+DEX kind of? (A&X)-imm -> X)
            case 0xCB:
                axs(imm());
                break;

            // ANE/XAA (unstable): A = (A | magic) & X & imm
            case 0x8B:
                ane(imm());
                break;

            // LXA/ATX (unstable): A = X = (A | magic) & imm
            case 0xAB:
                lxa(imm());
                break;

            // LAX (LDA + LDX)
            case 0xA7:
                lax(zp());
                break;
            case 0xB7:
                lax(zpy());
                break;
            case 0xAF:
                lax(abs());
                break;
            case 0xBF:
                lax(aby());
                break;
            case 0xA3:
                lax(izx());
                break;
            case 0xB3:
                lax(izy());
                break;

            // SAX (STA + STX) -> ANDs A and X and stores in memory
            case 0x87:
                sax(zp());
                break;
            case 0x97:
                sax(zpy());
                break;
            case 0x8F:
                sax(abs());
                break;
            case 0x83:
                sax(izx());
                break;

            // SBC (Unofficial)
            case 0xEB:
                sbc(imm());
                break;

            // DCP (DEC + CMP)
            case 0xC7:
                dcp(zp());
                break;
            case 0xD7:
                dcp(zpx());
                break;
            case 0xCF:
                dcp(abs());
                break;
            case 0xDF:
                dcp(abxWrite());
                break;
            case 0xDB:
                dcp(abyWrite());
                break;
            case 0xC3:
                dcp(izx());
                break;
            case 0xD3:
                dcp(izyWrite());
                break;

            // ISB (INC + SBC)
            case 0xE7:
                isb(zp());
                break;
            case 0xF7:
                isb(zpx());
                break;
            case 0xEF:
                isb(abs());
                break;
            case 0xFF:
                isb(abxWrite());
                break;
            case 0xFB:
                isb(abyWrite());
                break;
            case 0xE3:
                isb(izx());
                break;
            case 0xF3:
                isb(izyWrite());
                break;

            // SLO (ASL + ORA)
            case 0x07:
                slo(zp());
                break;
            case 0x17:
                slo(zpx());
                break;
            case 0x0F:
                slo(abs());
                break;
            case 0x1F:
                slo(abxWrite());
                break;
            case 0x1B:
                slo(abyWrite());
                break;
            case 0x03:
                slo(izx());
                break;
            case 0x13:
                slo(izyWrite());
                break;

            // RLA (ROL + AND)
            case 0x27:
                rla(zp());
                break;
            case 0x37:
                rla(zpx());
                break;
            case 0x2F:
                rla(abs());
                break;
            case 0x3F:
                rla(abxWrite());
                break;
            case 0x3B:
                rla(abyWrite());
                break;
            case 0x23:
                rla(izx());
                break;
            case 0x33:
                rla(izyWrite());
                break;

            // SRE (LSR + EOR)
            case 0x47:
                sre(zp());
                break;
            case 0x57:
                sre(zpx());
                break;
            case 0x4F:
                sre(abs());
                break;
            case 0x5F:
                sre(abxWrite());
                break;
            case 0x5B:
                sre(abyWrite());
                break;
            case 0x43:
                sre(izx());
                break;
            case 0x53:
                sre(izyWrite());
                break;

            // RRA (ROR + ADC)
            case 0x67:
                rra(zp());
                break;
            case 0x77:
                rra(zpx());
                break;
            case 0x6F:
                rra(abs());
                break;
            case 0x7F:
                rra(abxWrite());
                break;
            case 0x7B:
                rra(abyWrite());
                break;
            case 0x63:
                rra(izx());
                break;
            case 0x73:
                rra(izyWrite());
                break;

            // Unofficial Opcodes (Treat as KIL/Halt for unknown)
            // Unofficial NOPs
            // DOP (Double NOP) - Zero Page
            case 0x04:
            case 0x44:
            case 0x64:
            case 0x14:
            case 0x34:
            case 0x54:
            case 0x74:
            case 0xD4:
            case 0xF4:
                memory.read(PC++); // Dummy read ZP
                break;

            // TOP (Triple NOP) - Absolute
            case 0x0C:
                memory.read(abs()); // Read absolute address
                break;
            case 0x1C:
            case 0x3C:
            case 0x5C:
            case 0x7C:
            case 0xDC:
            case 0xFC:
                memory.read(abx()); // Read absolute X address
                break;

            // Unstable Opcodes (SHA, SHX, SHY, SHS, LAE) - Previously Default/KIL
            case 0x93: // SHA Ind,Y
                sha(izyWrite());
                break;
            case 0x9F: // SHA Abs,Y
                sha(abyWrite());
                break;
            case 0x9B: // SHS Abs,Y
                shs(abyWrite());
                break;
            case 0x9C: // SHY Abs,X
                shy(abxWrite());
                break;
            case 0x9E: // SHX Abs,Y
                shx(abyWrite());
                break;
            case 0xBB: // LAE/LAS Abs,Y
                // A read instruction, so it uses the read addressing mode with
                // its conditional page-cross penalty - not the write helper,
                // which always burns the extra dummy-read cycle.
                lae(aby());
                break;

            // NOP Immediate
            case 0x80:
            case 0x82:
            case 0x89:
            case 0xC2:
            case 0xE2:
                PC++; // Read immediate (ignore)
                break;

            // KIL / HLT / JAM
            default:
                PC--; // Infinite loop (Halt)
                break;
        }

        return cycles;

    }

    // === Addressing Modes
    // =========================================================================================

    private int imm() {
        return PC++;
    }

    private int zp() {
        return memory.read(PC++);
    }

    private int zpx() {
        int ptr = memory.read(PC++);
        memory.read(ptr); // Dummy read
        return (ptr + getReg(0)) & 0xFF; // X is reg 0
    } // wait, implementation of getReg?

    private int zpy() {
        int ptr = memory.read(PC++);
        memory.read(ptr); // Dummy read
        return (ptr + getReg(1)) & 0xFF; // Y is reg 1
    }

    private int abs() {
        int low = memory.read(PC++);
        int high = memory.read(PC++);
        return low | (high << 8);
    }

    private int abx() {
        int low = memory.read(PC++);
        int high = memory.read(PC++);
        int address = (low | (high << 8)) + getReg(0);
        if ((address & 0xFF00) != (high << 8)) {
            memory.read((high << 8) | (address & 0xFF)); // Dummy read of invalid address
            totalCycles++;
        }
        return address & 0xFFFF;
    }

    private int aby() {
        int low = memory.read(PC++);
        int high = memory.read(PC++);
        int address = (low | (high << 8)) + getReg(1);
        if ((address & 0xFF00) != (high << 8)) {
            memory.read((high << 8) | (address & 0xFF)); // Dummy read
            totalCycles++;
        }
        return address & 0xFFFF;
    }

    private int ind() {
        int low = memory.read(PC++);
        int high = memory.read(PC++);
        int ptr = low | (high << 8);

        // Hardware bug: JMP ($xxFF) wraps to $xx00
        int nextPtr = (low == 0xFF) ? (ptr & 0xFF00) : ptr + 1;

        return memory.read(ptr) | (memory.read(nextPtr) << 8);
    }

    private int izx() {
        int ptr = memory.read(PC++);
        memory.read(ptr); // Dummy read of pointer
        ptr = (ptr + getReg(0)) & 0xFF;
        int low = memory.read(ptr);
        int high = memory.read((ptr + 1) & 0xFF);
        return low | (high << 8);
    }

    private int izy() {
        int ptr = memory.read(PC++);
        int low = memory.read(ptr);
        int high = memory.read((ptr + 1) & 0xFF);
        int address = (low | (high << 8)) + getReg(1);
        if ((address & 0xFF00) != (high << 8)) {
            memory.read((high << 8) | (address & 0xFF)); // Dummy read
            totalCycles++;
        }
        return address & 0xFFFF;
    }

    // === Instructions implementation (Simplified) ===

    private void lda(int addr) {
        setReg(2, memory.read(addr));
        setZN(getReg(2));
    } // A is reg 2

    private void ldx(int addr) {
        setReg(0, memory.read(addr));
        setZN(getReg(0));
    }

    private void ldy(int addr) {
        setReg(1, memory.read(addr));
        setZN(getReg(1));
    }

    private void sta(int addr) {
        memory.write(addr, getReg(2));
    }

    private void stx(int addr) {
        memory.write(addr, getReg(0));
    }

    private void sty(int addr) {
        memory.write(addr, getReg(1));
    }

    private void tax() {
        dummyReadPC();
        setReg(0, getReg(2));
        setZN(getReg(0));
    }

    private void tay() {
        dummyReadPC();
        setReg(1, getReg(2));
        setZN(getReg(1));
    }

    private void tsx() {
        dummyReadPC();
        setReg(0, getSP());
        setZN(getReg(0));
    }

    private void txa() {
        dummyReadPC();
        setReg(2, getReg(0));
        setZN(getReg(2));
    }

    private void dummyReadPC() {
        memory.read(PC);
    }

    private void txs() {
        dummyReadPC();
        setSP(getReg(0));
    }

    private void tya() {
        dummyReadPC();
        setReg(2, getReg(1));
        setZN(getReg(2));
    }

    private void pha() {
        dummyReadPC();
        push(getReg(2));
    }

    private void php() {
        dummyReadPC();
        push(flags | 0x30);
    } // Break flag logic is complex, usually pushed as set

    private void pla() {
        dummyReadPC();
        memory.read(0x100 | getSP()); // Stack dummy read
        setReg(2, pop());
        setZN(getReg(2));
    }

    private void plp() {
        dummyReadPC();
        memory.read(0x100 | getSP()); // Stack dummy read
        flags = pop();
        setFlag(FLAG_B, 0); // B flag doesn't exist in register
        setFlag(5, 1); // Always 1
    }

    private void rti() {
        dummyReadPC();
        memory.read(0x100 | getSP()); // Stack dummy read
        flags = pop();
        setFlag(FLAG_B, 0); // B flag doesn't exist in register
        setFlag(5, 1); // Always 1
        int low = pop();
        int high = pop();
        PC = low | (high << 8);
    }

    private void and(int addr) {
        setReg(2, getReg(2) & memory.read(addr));
        setZN(getReg(2));
    }

    private void eor(int addr) {
        setReg(2, getReg(2) ^ memory.read(addr));
        setZN(getReg(2));
    }

    private void ora(int addr) {
        setReg(2, getReg(2) | memory.read(addr));
        setZN(getReg(2));
    }

    private void bit(int addr) {
        int val = memory.read(addr);
        setFlag(FLAG_Z, (getReg(2) & val) == 0 ? 1 : 0);
        setFlag(FLAG_N, (val & 0x80) != 0 ? 1 : 0);
        setFlag(FLAG_V, (val & 0x40) != 0 ? 1 : 0);
    }

    // Arithmetic
    private void adc(int addr) {
        int val = memory.read(addr);
        int a = getReg(2);
        int sum = a + val + getFlag(FLAG_C);
        setFlag(FLAG_C, sum > 0xFF ? 1 : 0);
        setFlag(FLAG_V, (~(a ^ val) & (a ^ sum) & 0x80) != 0 ? 1 : 0);
        setReg(2, sum & 0xFF);
        setZN(getReg(2));
    }

    private void sbc(int addr) {
        int val = memory.read(addr) ^ 0xFF; // Invert bits matches ADC logic
        int a = getReg(2);
        int sum = a + val + getFlag(FLAG_C);
        setFlag(FLAG_C, sum > 0xFF ? 1 : 0);
        setFlag(FLAG_V, (~(a ^ val) & (a ^ sum) & 0x80) != 0 ? 1 : 0);
        setReg(2, sum & 0xFF);
        setZN(getReg(2));
    }

    private void cmp(int addr) {
        int val = memory.read(addr);
        int a = getReg(2);
        setFlag(FLAG_C, a >= val ? 1 : 0);
        setZN((a - val) & 0xFF);
    }

    private void cpx(int addr) {
        int val = memory.read(addr);
        int x = getReg(0);
        setFlag(FLAG_C, x >= val ? 1 : 0);
        setZN((x - val) & 0xFF);
    }

    private void cpy(int addr) {
        int val = memory.read(addr);
        int y = getReg(1);
        setFlag(FLAG_C, y >= val ? 1 : 0);
        setZN((y - val) & 0xFF);
    }

    private void inc(int addr) {
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        val = (val + 1) & 0xFF;
        memory.write(addr, val);
        setZN(val);
    }

    private void dec(int addr) {
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        val = (val - 1) & 0xFF;
        memory.write(addr, val);
        setZN(val);
    }

    private void inx() {
        dummyReadPC();
        setReg(0, (getReg(0) + 1) & 0xFF);
        setZN(getReg(0));
    }

    private void dex() {
        dummyReadPC();
        setReg(0, (getReg(0) - 1) & 0xFF);
        setZN(getReg(0));
    }

    private void iny() {
        dummyReadPC();
        setReg(1, (getReg(1) + 1) & 0xFF);
        setZN(getReg(1));
    }

    private void dey() {
        dummyReadPC();
        setReg(1, (getReg(1) - 1) & 0xFF);
        setZN(getReg(1));
    }

    // Shifts
    private void asl_acc() {
        dummyReadPC();
        int val = getReg(2);
        setFlag(FLAG_C, (val & 0x80) != 0 ? 1 : 0);
        val = (val << 1) & 0xFF;
        setReg(2, val);
        setZN(val);
    }

    private void asl(int addr) {
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        setFlag(FLAG_C, (val & 0x80) != 0 ? 1 : 0);
        val = (val << 1) & 0xFF;
        memory.write(addr, val);
        setZN(val);
    }

    private void lsr_acc() {
        dummyReadPC();
        int val = getReg(2);
        setFlag(FLAG_C, (val & 0x01) != 0 ? 1 : 0);
        val = (val >> 1) & 0xFF;
        setReg(2, val);
        setZN(val);
    }

    private void lsr(int addr) {
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        setFlag(FLAG_C, (val & 0x01) != 0 ? 1 : 0);
        val = (val >> 1) & 0xFF;
        memory.write(addr, val);
        setZN(val);
    }

    private void rol_acc() {
        dummyReadPC();
        int val = getReg(2);
        int c = getFlag(FLAG_C);
        setFlag(FLAG_C, (val & 0x80) != 0 ? 1 : 0);
        val = ((val << 1) | c) & 0xFF;
        setReg(2, val);
        setZN(val);
    }

    private void rol(int addr) {
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        int c = getFlag(FLAG_C);
        setFlag(FLAG_C, (val & 0x80) != 0 ? 1 : 0);
        val = ((val << 1) | c) & 0xFF;
        memory.write(addr, val);
        setZN(val);
    }

    private void ror_acc() {
        dummyReadPC();
        int val = getReg(2);
        int c = getFlag(FLAG_C);
        setFlag(FLAG_C, (val & 0x01) != 0 ? 1 : 0);
        val = ((val >> 1) | (c << 7)) & 0xFF;
        setReg(2, val);
        setZN(val);
    }

    private void ror(int addr) {
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        int c = getFlag(FLAG_C);
        setFlag(FLAG_C, (val & 0x01) != 0 ? 1 : 0);
        val = ((val >> 1) | (c << 7)) & 0xFF;
        memory.write(addr, val);
        setZN(val);
    }

    // === Illegal Opcode Helpers ===

    private void lax(int addr) {
        int val = memory.read(addr);
        setReg(2, val); // LDA
        setReg(0, val); // LDX
        setZN(val);
    }

    private void sax(int addr) {
        int val = getReg(2) & getReg(0); // A & X
        memory.write(addr, val);
    }

    private void dcp(int addr) {
        // DEC (decrement M)
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        val = (val - 1) & 0xFF;
        memory.write(addr, val);
        // CMP (compare A with M)
        int a = getReg(2);
        setFlag(FLAG_C, a >= val ? 1 : 0);
        setZN((a - val) & 0xFF);
    }

    private void isb(int addr) {
        // INC (increment M)
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        val = (val + 1) & 0xFF;
        memory.write(addr, val);
        // SBC (subtract M from A)
        // Note: SBC Logic duplicated from sbc() to operate on value 'val' directly
        // without re-reading memory
        // SBC implementation: A - M - ~C
        val = val ^ 0xFF; // Invert bits for SBC
        int a = getReg(2);
        int sum = a + val + getFlag(FLAG_C);
        setFlag(FLAG_C, sum > 0xFF ? 1 : 0);
        setFlag(FLAG_V, (~(a ^ val) & (a ^ sum) & 0x80) != 0 ? 1 : 0);
        setReg(2, sum & 0xFF);
        setZN(getReg(2));
    }

    private void slo(int addr) {
        // ASL (shift left M)
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        setFlag(FLAG_C, (val & 0x80) != 0 ? 1 : 0);
        val = (val << 1) & 0xFF;
        memory.write(addr, val);
        // ORA (A | M)
        setReg(2, getReg(2) | val);
        setZN(getReg(2));
    }

    private void rla(int addr) {
        // ROL (rotate left M)
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        int c = getFlag(FLAG_C);
        setFlag(FLAG_C, (val & 0x80) != 0 ? 1 : 0);
        val = ((val << 1) | c) & 0xFF;
        memory.write(addr, val);
        // AND (A & M)
        setReg(2, getReg(2) & val);
        setZN(getReg(2));
    }

    private void sre(int addr) {
        // LSR (shift right M)
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        setFlag(FLAG_C, (val & 0x01) != 0 ? 1 : 0);
        val = (val >> 1) & 0xFF;
        memory.write(addr, val);
        // EOR (A ^ M)
        setReg(2, getReg(2) ^ val);
        setZN(getReg(2));
    }

    private void rra(int addr) {
        // ROR (rotate right M)
        int val = memory.read(addr);
        memory.write(addr, val); // Dummy write
        int c = getFlag(FLAG_C);
        setFlag(FLAG_C, (val & 0x01) != 0 ? 1 : 0);
        val = ((val >> 1) | (c << 7)) & 0xFF;
        memory.write(addr, val);
        // ADC (A + M + C)
        // ADC Logic duplicated
        int a = getReg(2);
        int sum = a + val + getFlag(FLAG_C);
        setFlag(FLAG_C, sum > 0xFF ? 1 : 0);
        setFlag(FLAG_V, (~(a ^ val) & (a ^ sum) & 0x80) != 0 ? 1 : 0);
        setReg(2, sum & 0xFF);
        setZN(getReg(2));
    }

    // Jumps
    private void jmp(int addr) {
        PC = addr;
    }

    private void jsr() {
        // JSR Cycle Order:
        // T1: Opcode (PC)
        // T2: Fetch ADL (PC) -> PC++
        // T3: Push PCH (Stack) - Note: Pushes PC of *last* byte of instruction?
        // No, JSR pushes Address of Instruction + 2 (the return address - 1).
        // Actually it pushes the address of the 3rd byte (Reference).
        // Which is effectively (Instruction Address + 2).
        // Wait, 6502 JSR pushes (return address - 1).
        // If JSR is at 0x1000. 1000: 20 00 20 (JSR 2000).
        // Return should be 1003. Stack gets 1002.

        // Implementation:
        // PC is at ADL.
        int low = memory.read(PC++); // Fetch ADL

        memory.read(0x100 | getSP()); // Internal Cycle (Stack dummy read)

        // Internal Cycle (stack push PCH). Pushes PC (which is now at ADH).
        // But we need to push PC corresponding to 'last byte of instruction'?
        // The return address stored is the address of the last byte of the JSR operand.
        // Current PC points to ADH.
        // So Pushed PC is Current PC.
        push((PC >> 8) & 0xFF);
        push(PC & 0xFF);

        int high = memory.read(PC); // Fetch ADH
        PC = low | (high << 8);
    }

    private void rts() {
        dummyReadPC(); // T2: Read PC (dummy)
        memory.read(0x100 | getSP()); // T3: Stack dummy read
        // Wait, standard RTS T3 is often "Stack (increment S)". S is at old value.
        // It reads stack pointer (or throws away?), increments S.
        // My pop() increments S then reads.

        // Let's stick to simple "pop()" logic. pop is effectively a read.
        // But is there an extra cycle?
        // RTS is 6 cycles.
        // T1: Opcode.
        // T2: Read PC + 1. S increments.
        // T3: Read Stack (PCL). S increments.
        // T4: Read Stack (PCH).
        // T5: Read PC (Old value or new?) -> "Jumps to address on stack + 1".
        // T5 reads PC (from stack value) (throws away).
        // PC increments.
        // T6: Fetch next op.

        int low = pop(); // T4
        int high = pop(); // T5
        PC = (low | (high << 8));
        memory.read(PC); // T6: Read address
        PC++; // Increment PC
    }

    // === Debugger Accessors ===
    public int getPC() {
        return PC;
    }

    public int getFlags() {
        return flags;
    }

    // Helpers
    private void push(int val) {
        int sp = getSP();
        memory.write(0x100 + sp, val);
        setSP(sp - 1);
    }

    private int pop() {
        // SP wraps within page 1: popping with SP=$FF must read $0100, not $0200.
        int sp = (getSP() + 1) & 0xFF;
        setSP(sp);
        return memory.read(0x100 | sp);
    }

    // Register Helpers (Using bitfield)
    // 0:X, 1:Y, 2:A, 3:SP(low 8)
    public int getReg(int idx) {
        return (registers >> (idx * 8)) & 0xFF;
    }

    private void setReg(int idx, int val) {
        registers &= ~(0xFF << (idx * 8));
        registers |= (val & 0xFF) << (idx * 8);
    }

    public int getSP() {
        return getReg(3);
    }

    private void setSP(int val) {
        setReg(3, val);
    }

    private int getFlag(int bit) {
        return (flags >> bit) & 0x01;
    }

    private void setFlag(int bit, int val) {
        if (val == 0)
            flags &= ~(1 << bit);
        else
            flags |= (1 << bit);
    }

    private void setZN(int val) {
        setFlag(FLAG_Z, (val & 0xFF) == 0 ? 1 : 0);
        setFlag(FLAG_N, (val & 0x80) != 0 ? 1 : 0);
    }
    // === Write Addressing Modes (Always Dummy Read) ===

    private int abyWrite() {
        int low = memory.read(PC++);
        int high = memory.read(PC++);
        int address = (low | (high << 8)) + getReg(1);
        int invalidAddr = (address & 0xFF) | (high << 8);
        memory.read(invalidAddr);
        noteShOperand(high, address);
        return address & 0xFFFF;
    }

    private int abxWrite() {
        int low = memory.read(PC++);
        int high = memory.read(PC++);
        int address = (low | (high << 8)) + getReg(0);
        int invalidAddr = (address & 0xFF) | (high << 8);
        memory.read(invalidAddr);
        noteShOperand(high, address);
        return address & 0xFFFF;
    }

    private int izyWrite() {
        int ptr = memory.read(PC++);
        int low = memory.read(ptr);
        int high = memory.read((ptr + 1) & 0xFF);
        int address = (low | (high << 8)) + getReg(1);
        int invalidAddr = (address & 0xFF) | (high << 8);
        memory.read(invalidAddr);
        noteShOperand(high, address);
        return address & 0xFFFF;
    }

    // === Extra Illegal Helpers ===

    private void anc(int addr) { // Immediate
        int val = memory.read(addr); // Logic implies getting immediate
        // AND #i
        int a = getReg(2) & val;
        setReg(2, a);
        setZN(a);
        // C = Bit 7 (ASL-like behavior but on the result)
        setFlag(FLAG_C, (a & 0x80) != 0 ? 1 : 0);
    }

    private void alr(int addr) { // Immediate
        int val = memory.read(addr);
        int a = getReg(2) & val;
        // LSR
        setFlag(FLAG_C, (a & 0x01) != 0 ? 1 : 0);
        a = (a >> 1) & 0xFF;
        setReg(2, a);
        setZN(a);
    }

    private void arr(int addr) { // Immediate
        int val = memory.read(addr);
        int a = getReg(2) & val;
        // ROR-like but complex V flag stuff
        int c = getFlag(FLAG_C);
        a = (a >> 1) | (c << 7);
        setReg(2, a);
        setZN(a);

        // ARR V Flag: V = bit6 ^ bit5
        // C = bit6
        int bit6 = (a >> 6) & 1;
        int bit5 = (a >> 5) & 1;
        setFlag(FLAG_C, bit6);
        setFlag(FLAG_V, bit6 ^ bit5);
    }

    // ANE/XAA and LXA are unstable on hardware: the accumulator is first OR'd
    // with a "magic" constant determined by analog effects on the real chip.
    // 0xEE is the value these opcodes settle on in practice, and is what
    // blargg's instr_test-v5 expects.
    private static final int UNSTABLE_MAGIC = 0xFF;

    private void ane(int addr) { // Immediate
        // ANE/XAA: A = (A | magic) & X & imm
        int val = memory.read(addr);
        int a = (getReg(2) | UNSTABLE_MAGIC) & getReg(0) & val;
        setReg(2, a);
        setZN(a);
    }

    private void lxa(int addr) { // Immediate
        // LXA/ATX: A = X = (A | magic) & imm
        int val = memory.read(addr);
        int a = (getReg(2) | UNSTABLE_MAGIC) & val;
        setReg(2, a); // A
        setReg(0, a); // X
        setZN(a);
    }

    private void axs(int addr) { // Immediate
        // AXS: X = (A & X) - imm
        int val = memory.read(addr);
        int ax = getReg(2) & getReg(0);
        int res = ax - val;
        setFlag(FLAG_C, ax >= val ? 1 : 0);
        setReg(0, res & 0xFF);
        setZN(getReg(0));
    }

    // The unstable SH* opcodes AND the stored value with the high byte of the
    // *base* address plus one, so the addressing helpers record it here rather
    // than letting these derive it from the (already indexed) effective address.
    private int shBaseHigh;
    private boolean shPageCrossed;

    private void noteShOperand(int baseHigh, int address) {
        shBaseHigh = baseHigh;
        shPageCrossed = ((address & 0xFFFF) >> 8) != baseHigh;
    }

    // On a page cross the carry corrupts the address: the high byte written to
    // is replaced by the stored value itself.
    private void shStore(int addr, int val) {
        int target = shPageCrossed ? ((val << 8) | (addr & 0xFF)) : addr;
        memory.write(target & 0xFFFF, val);
    }

    private void shy(int addr) {
        // SHY: M = Y & (H + 1)
        shStore(addr, getReg(1) & (shBaseHigh + 1));
    }

    private void shx(int addr) {
        // SHX: M = X & (H + 1)
        shStore(addr, getReg(0) & (shBaseHigh + 1));
    }

    private void sha(int addr) {
        // SHA/AXA: M = A & X & (H + 1)
        shStore(addr, getReg(2) & getReg(0) & (shBaseHigh + 1));
    }

    private void shs(int addr) {
        // SHS/TAS: S = A & X, M = S & (H + 1)
        int s = getReg(2) & getReg(0);
        setSP(s); // Update SP
        shStore(addr, s & (shBaseHigh + 1));
    }

    private void lae(int addr) {
        // LAE/LAS: A, X, S = (val & S)
        int val = memory.read(addr);
        int result = val & getSP();
        setSP(result);
        setReg(0, result); // X
        setReg(2, result); // A
        setZN(result);
    }

    // === Public API for Registers ===
    public enum Register {
        X, Y, A, SP
    }

    public void setReg(Register reg, int value) {
        switch (reg) {
            case X:
                setReg(0, value);
                break;
            case Y:
                setReg(1, value);
                break;
            case A:
                setReg(2, value);
                break;
            case SP:
                setReg(3, value);
                break;
        }
    }
}