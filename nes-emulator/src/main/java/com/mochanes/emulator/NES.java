package com.mochanes.emulator;

import java.io.IOException;

public class NES {
    private CPU cpu;
    private PPU ppu;
    private APU apu;
    private Memory memory;
    private Controller controller;

    /** Uses the platform's default audio output. */
    public NES(FrameSink display) {
        this(display, com.mochanes.emulator.gui.JavaSoundSink.openOrSilent());
    }

    /**
     * Builds a machine against the given video and audio sinks.
     *
     * <p>The default output is chosen in the single-argument constructor rather
     * than by testing for null here, so that this constructor names no platform
     * audio API at all. A host that supplies its own sink then never references
     * {@code javax.sound}, which lets an ahead-of-time compiler drop it - the
     * difference between a browser build linking and failing.
     *
     * @param display where the PPU draws
     * @param audio   where the APU plays
     */
    public NES(FrameSink display, AudioSink audio) {
        // Initialize Components
        controller = new Controller();
        apu = new APU(audio);
        ppu = new PPU(display);
    }

    public void loadROM(String romPath) throws IOException {
        System.out.println("Loading ROM: " + romPath);
        loadROM(new Memory(romPath));
    }

    /** Loads from ROM bytes already in memory, for hosts with no filesystem. */
    public void loadROM(byte[] romData) throws IOException {
        loadROM(new Memory(romData));
    }

    private void loadROM(Memory loaded) throws IOException {
        memory = loaded;

        // Wiring
        ppu.setMemory(memory);
        memory.setPPU(ppu);
        memory.setAPU(apu);
        memory.setController1(controller);
        apu.setMemory(memory);

        cpu = new CPU(memory);
        memory.setCPU(cpu);
        memory.setCycleSink(this::onCpuCycle);
        // The APU needs the CPU's cycle parity: a $4017 write resets the frame
        // counter after 3 or 4 cycles depending on whether it landed on an odd
        // or even cycle. Without this the odd case never happens.
        apu.setCpu(cpu);
    }

    public void setController(Controller controller) {
        this.controller = controller;
        if (memory != null)
            memory.setController1(controller);
    }

    public void reset() {
        if (cpu != null) {
            cyclesClocked = 0;
            cpu.reset();

            // Power-on alignment between the CPU and the PPU's clock divider.
            // cpu.reset() charges 7 cycles but only performs two bus accesses
            // (the vector fetch), so the remaining cycles never reached the
            // PPU; these make up the difference.
            //
            // The count is measured, not derived. Every $2002 read of
            // ppu_vbl_nmi/02-vbl_set_time was traced and diffed against Mesen
            // 2.1.1 (the most accurate reference available): 6 keeps the two in
            // step until read #10875, where 0 or 1 diverge at read #506.
            for (long i = 0; i < 6; i++) {
                onCpuCycle();
            }
        }
    }

    // Cycles already clocked during this instruction via bus accesses.
    private long cyclesClocked = 0;

    /**
     * Advances PPU and APU by one CPU cycle and samples the interrupt lines.
     * Driven from {@link Memory} on every bus access, so the video and audio
     * state a running instruction observes is correct at that cycle rather than
     * being caught up afterwards.
     */
    // The 6502 decides whether to take an interrupt from the line state at the
    // *end of the second-to-last cycle* of an instruction, not from the state
    // once the instruction has finished. Keeping the previous cycle's sample
    // means an interrupt asserted on the final cycle waits for the next
    // instruction, as on hardware.
    private boolean irqLineNow, irqLinePrev;
    private boolean nmiLatchNow, nmiLatchPrev;

    private void onCpuCycle() {
        cyclesClocked++;
        ppu.tick();
        ppu.tick();
        ppu.tick();
        apu.tick();

        // The NMI edge detector runs every cycle; the poll below decides when
        // the latched edge is acted on.
        cpu.pollNmiLine(ppu.nmiOccurred);

        irqLinePrev = irqLineNow;
        irqLineNow = apu.irqActive || (memory != null && memory.isMapperIrqAsserted());

        nmiLatchPrev = nmiLatchNow;
        nmiLatchNow = cpu.isNmiPending();
    }

    /**
     * Executes one CPU instruction with the rest of the system clocked in step
     * with its bus accesses, then services any interrupt latched during it.
     * This is the single definition of a system step, shared by the runners and
     * the test harness.
     */
    public void stepInstruction() {
        long before = cpu.getTotalCycles();
        cyclesClocked = 0;

        cpu.executeNextInstruction();

        // Cycles the CPU accounted for that performed no bus access (internal
        // operations) still have to advance the clock, or timing drifts.
        long consumed = cpu.getTotalCycles() - before;
        while (cyclesClocked < consumed) {
            onCpuCycle();
        }

        // The DMC's sample fetch halts the CPU while the APU uses the bus.
        // Applied here rather than inside apu.tick(), which is itself driven by
        // the bus clock and must not re-enter it.
        int stall = apu.consumeDmcStall();
        if (stall > 0) {
            cpu.burnCycles(stall);
            for (int i = 0; i < stall; i++) {
                onCpuCycle();
            }
        }

        if (!cpu.isDmaActive()) {
            // Act on the sample taken at the second-to-last cycle. An edge that
            // only arrived on the final cycle stays latched for next time.
            if (nmiLatchPrev) {
                cpu.serviceNmi();
            }
            if (irqLinePrev) {
                cpu.irq();
            }
        }
    }

    // Getters for Debugger
    public CPU getCpu() {
        return cpu;
    }

    public PPU getPpu() {
        return ppu;
    }

    public APU getApu() {
        return apu;
    }

    public Memory getMemory() {
        return memory;
    }

    public Controller getController() {
        return controller;
    }

    /**
     * Clones the machine, for save states and rollouts.
     *
     * <p>The copy is silent. A clone is a snapshot rather than a second console,
     * so giving it its own output would either double up on the speakers or, on
     * the desktop, strand a playback line with the system mixer for the life of
     * the process. It also keeps the platform audio API out of this path, which
     * lets an ahead-of-time compiler drop it from a browser build.
     */
    public NES copy(FrameSink newDisplay) {
        NES newNES = new NES(newDisplay, AudioSink.SILENT);

        // Deep Copy Components
        // 1. Memory (holds heavy data)
        newNES.memory = this.memory.copy();
        newNES.controller = this.controller.copy();

        // 2. Wiring for CPU/PPU/APU
        newNES.ppu = this.ppu.copy(newDisplay);
        newNES.ppu.setMemory(newNES.memory); // Wire PPU -> Memory

        newNES.cpu = this.cpu.copy(newNES.memory);

        newNES.apu = this.apu.copy(newNES.memory, newNES.cpu);

        // 3. Final Memory Wiring
        newNES.memory.setPPU(newNES.ppu);
        newNES.memory.setAPU(newNES.apu);
        newNES.memory.setCPU(newNES.cpu);
        newNES.memory.setController1(newNES.controller);

        return newNES;
    }

    public void fastCloneTo(NES target) {
        target.fastCopyFrom(this);
    }

    public void fastCopyFrom(NES source) {
        this.memory.fastCopyFrom(source.memory);
        this.cpu.fastCopyFrom(source.cpu);
        this.ppu.fastCopyFrom(source.ppu);
        this.apu.fastCopyFrom(source.apu);
        // Copy Controller State
        if (this.controller != null && source.controller != null) {
            // Controller is simple enough to just do field copy if needed,
            // but for now we assume it's transient.
            // If we need strict controller cloning:
            // this.controller.fastCopyFrom(source.controller);
        }
    }

    public void loadState(NES source) {
        fastCopyFrom(source);
    }

    // === Serialization ===

    public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
        memory.saveState(dos);
        cpu.saveState(dos);
        ppu.saveState(dos);
        apu.saveState(dos);
        // Controller is usually transient frame input, but let's save button state if
        // we had it
        // For strict determinism, we assume input comes from file/agent, but current
        // button state matters.
        // We'll skip controller for disk save as it's reset every frame by the agent
        // anyway.
    }

    public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
        memory.loadState(dis);
        cpu.loadState(dis);
        ppu.loadState(dis);
        apu.loadState(dis);
    }
}
