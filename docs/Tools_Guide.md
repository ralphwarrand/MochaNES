# MochaNES Tools Guide

This document is the manual for the advanced debugging and analysis tools built into MochaNES.

## 1. The Integrated Debugger (`DebuggerWindow`)

Access the debugger by running `run.bat -debug` or pressing `F12` during gameplay (if enabled).

### 1.1 The CPU Tab
Real-time view of the MOS 6502 internals.
*   **Registers**:
    *   **A**: Accumulator. Used for math and logic.
    *   **X/Y**: Index registers for loops and table lookups.
    *   **SP**: Stack Pointer (Relative to `$0100`).
*   **Status Flags (NV-BDIZC)**:
    *   **N (Negative)**: Set if bit 7 of the result is 1.
    *   **V (Overflow)**: Set if signed math result is invalid.
    *   **I (Interrupt)**: If 1, IRQs are ignored.
    *   **Z (Zero)**: Set if result is 0.
    *   **C (Carry)**: Set if math overflows unsigned range.

### 1.2 The Memory Map (Hex Editor)
A complete 64KB view of the NES address space.
*   **Address Ranges**:
    *   `$0000-$07FF`: Internal RAM (2KB).
    *   `$2000-$2007`: PPU Registers (Mirrored).
    *   `$4000-$4017`: APU Registers.
    *   `$6000-$7FFF`: Save RAM (SRAM).
    *   `$8000-$FFFF`: Program ROM (PRG).
*   **Heatmap**:
    *   **FLASHING RED**: The value changed in the last frame.
    *   Use this to find "HP" or "X Position" addresses.

### 1.3 The PPU Viewer
Visualizes the Graphics state.
*   **Pattern Tables**: Shows the 16x16 grid of 8x8 tiles stored in CHR-ROM.
*   **Nametables**: Shows the 4 hardware scrolling maps (Background).
*   **OAM (Sprites)**: Shows the table of 64 active sprites (Y, TileID, Attribute, X).

---

## 2. Performance Monitor (Profiling)

Enabled via `metrics=true`. It profiles the emulator loop in nanoseconds.

### 3.1 Understanding the Hierarchy
*   **Frame**: Total time for one frame (Target: <16ms).
    *   **CPU**: Time spent executing 6502 ops.
    *   **PPU**: Time spent rendering pixels.
    *   **AI**: Time spent in PPO agent.
        *   **Fork**: Time spent cloning state.
        *   **Inference**: Time spent in DJL (GPU).

### 3.2 Troubleshooting
*   **High CPU Time**: You might have disabled "Turbo PPU".
*   **High AI Time**: Reduce `smart_scout_depth` per fork.
*   **GC Spikes**: If you see spikes every few seconds, Java Garbage Collection is running. Increase heap size (`-Xmx4G`).

---

## 3. The Replay System

Allows playing back AI training runs.

### 3.1 File Formats
*   **.rpl (Replay File)**: A compressed log of inputs.
    *   Format: `[Seed:Long][ActionByte][ActionByte]...`
*   **.stats (CSV)**: Performance data.

### 3.2 Verification
To prove an AI run is legitimate (and not hallucinated):
1.  Take the `replay.rpl`.
2.  Run `verify_run.bat <replay.rpl>`.
3.  This runs a HEADLESS verifier that checks if the inputs produce the same Hash-Score.

---
