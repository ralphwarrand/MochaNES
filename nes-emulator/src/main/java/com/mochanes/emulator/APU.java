package com.mochanes.emulator;


/**
 * NES audio processing unit: two pulse channels, triangle, noise and DMC.
 *
 * The emulation thread paces itself here - {@code line.write()} blocks once the
 * output buffer is full, which is what keeps the emulator running at 60Hz.
 */
public class APU {

    // Audio Output
    private final AudioSink line;
    /** Output line size in bytes; 16-bit mono, so 4 bytes = 2 samples. */
    private static final int AUDIO_BUFFER_BYTES = 8192; // ~93ms at 44.1kHz

    // One NES frame of audio (735 samples). Writing in frame-sized chunks keeps
    // the block-on-write pacing smooth.
    private final byte[] outputBuffer = new byte[1470];
    private int outputIndex = 0;

    // NTSC CPU Frequency = 1.789773 MHz
    // Sample Rate = 44100 Hz
    // Cycles per Sample = 1789773 / 44100 = ~40.58
    private static final double CYCLES_PER_SAMPLE = 40.5844;
    private double cycleCounter = 0; // Tracks when to emit a sample

    // Accumulators for Oversampling
    private double p1Sum = 0;
    private double p2Sum = 0;
    private double triSum = 0;
    private double noiseSum = 0;
    private double dmcSum = 0;
    private int sampleCount = 0; // Number of CPU cycles accumulated

    // External Dependencies
    private Memory memory;
    private CPU cpu;

    // --- IRQ ---
    public boolean irqActive = false;
    private boolean frameIrqEnabled = false;
    private boolean frameIrqActive = false;
    private boolean dmcIrqActive = false;

    // --- Frame Counter ---
    private boolean frameCounterMode = false; // 0: 4-step, 1: 5-step
    private int frameCycle = 0;
    private int frameCounterResetDelay = 0; // Delay cycles before reset affects state

    // --- Components ---

    // Channel Enabled Flags (controlled by $4015)
    private boolean p1Enabled = false;
    private boolean p2Enabled = false;
    private boolean triEnabled = false;
    private boolean noiseEnabled = false;

    // Pulse 1
    private final Envelope p1Envelope = new Envelope();
    private final Sweep p1Sweep = new Sweep(p1Envelope);
    private int p1Duty = 0;
    private boolean p1ConstantVol = false;
    private int p1Volume = 0;
    private int p1TimerLow = 0;
    private int p1TimerHigh = 0;
    private int p1Timer = 0; // Current Timer Value
    private int p1LengthCounter = 0;
    private int p1Sequence = 0;

    // Pulse 2
    private final Envelope p2Envelope = new Envelope();
    private final Sweep p2Sweep = new Sweep(p2Envelope);
    private int p2Duty = 0;
    private boolean p2ConstantVol = false;
    private int p2Volume = 0;
    private int p2TimerLow = 0;
    private int p2TimerHigh = 0;
    private int p2Timer = 0;
    private int p2LengthCounter = 0;
    private int p2Sequence = 0;

    // Triangle
    private boolean triControl = false;
    private int triLinearCounterReload = 0;
    private int triTimerLow = 0;
    private int triTimerHigh = 0;
    private int triTimer = 0;
    private int triLengthCounter = 0;
    private int triLinearCounter = 0;
    private boolean triReloadLinear = false;
    private int triSequence = 0;

    // Noise
    private final Envelope noiseEnvelope = new Envelope();
    private boolean noiseLoop = false;
    private boolean noiseConstantVol = false;
    private int noiseVolume = 0;
    private boolean noiseMode = false;
    private int noisePeriod = 0;
    private int noiseTimer = 0;
    private int noiseLengthCounter = 0;
    private int noiseShiftRegister = 1;

    // DMC
    private boolean dmcIrqEnabled = false;
    private boolean dmcLoop = false;
    private int dmcRateIndex = 0;
    private int dmcOutputLevel = 0;
    private int dmcSampleAddress = 0;
    private int dmcSampleLength = 0;
    private int dmcCurrentAddress = 0;
    private int dmcBytesRemaining = 0;
    private int dmcBuffer = 0;
    private boolean dmcBufferEmpty = true;
    private int dmcShiftRegister = 0;
    private int dmcBitsRemaining = 8;
    private boolean dmcSilence = true;
    private int dmcPeriod = DMC_RATE_TABLE_INIT;
    private int dmcTimer = 0;

    // Output filter chain, at the 44.1kHz sample rate.
    // First-order high-pass: a = RC/(RC+dt); low-pass: a = dt/(RC+dt).
    private static final double HP90_A = 0.987341;  // high-pass  90 Hz
    private static final double HP440_A = 0.940963; // high-pass 440 Hz
    private static final double LP14_A = 0.666142;  // low-pass   14 kHz
    private double hp90Prev = 0, hp90PrevIn = 0;
    private double hp440Prev = 0, hp440PrevIn = 0;
    private double lp14Prev = 0;

    // Tables
    private static final int[][] DUTY_TABLE = {
            { 0, 1, 0, 0, 0, 0, 0, 0 },
            { 0, 1, 1, 0, 0, 0, 0, 0 },
            { 0, 1, 1, 1, 1, 0, 0, 0 },
            { 1, 0, 0, 1, 1, 1, 1, 1 }
    };

    private static final int[] LENGTH_TABLE = {
            10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30
    };

    private static final int[] TRIANGLE_SEQUENCE = {
            15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15
    };

    private static final int[] NOISE_PERIOD_TABLE = {
            4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068
    };

    private static final int DMC_RATE_TABLE_INIT = 428; // rate index 0

    private static final int[] DMC_RATE_TABLE = {
            428, 380, 340, 320, 286, 254, 226, 214, 190, 160, 142, 128, 106, 84, 72, 54
    };

    /** Uses the platform's default audio output, or silence if it has none. */
    public APU() {
        this(com.mochanes.emulator.gui.JavaSoundSink.openOrSilent());
    }

    /**
     * Sends audio to the given sink.
     *
     * <p>The APU paces the emulation thread by blocking in the sink's write, so
     * a non-blocking sink leaves the caller responsible for frame timing.
     */
    public APU(AudioSink sink) {
        this.line = sink;
    }

    /**
     * Releases the audio device. Every APU opens its own output line in the
     * constructor, so an instance discarded without this leaves a live playback
     * stream registered with the system mixer for the life of the process -
     * measured as one stranded PipeWire sink-input per discarded APU.
     */
    public void close() {
        if (line != null) {
            line.close();
        }
    }

    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    public void setCpu(CPU cpu) {
        this.cpu = cpu;
    }

    /** CPU cycles the DMC's DMA has stolen and not yet been accounted for. */
    private int dmcStallCycles = 0;

    /** Returns and clears any pending DMC DMA stall. */
    public int consumeDmcStall() {
        int s = dmcStallCycles;
        dmcStallCycles = 0;
        return s;
    }

    // === Register IO ===

    public int readRegister(int addr, int openBus) {
        if (addr == 0x4015) {
            // Status
            int val = 0;
            if (p1LengthCounter > 0)
                val |= 0x01;
            if (p2LengthCounter > 0)
                val |= 0x02;
            if (triLengthCounter > 0)
                val |= 0x04;
            if (noiseLengthCounter > 0)
                val |= 0x08;
            if (dmcBytesRemaining > 0)
                val |= 0x10;
            if (frameIrqActive)
                val |= 0x40;
            if (dmcIrqActive)
                val |= 0x80;

            // Bit 5 is unused (Open Bus)
            val |= (openBus & 0x20);

            frameIrqActive = false;
            updateIrqOutput();
            return val;
        }
        return -1; // Unmapped
    }

    public void writeRegister(int addr, int value) {
        switch (addr) {
            // Pulse 1
            case 0x4000:
                p1Duty = (value >> 6) & 0x03;
                p1Envelope.loop = (value & 0x20) != 0;
                p1ConstantVol = (value & 0x10) != 0;
                p1Envelope.constantVolume = (value & 0x10) != 0;
                p1Volume = value & 0x0F;
                p1Envelope.volumePeriod = value & 0x0F;
                break;
            case 0x4001:
                p1Sweep.enabled = (value & 0x80) != 0;
                p1Sweep.period = (value >> 4) & 0x07;
                p1Sweep.negate = (value & 0x08) != 0;
                p1Sweep.shift = value & 0x07;
                p1Sweep.reload = true;
                break;
            case 0x4002:
                p1TimerLow = value;
                p1Sweep.updateTargetPeriod(p1TimerLow | (p1TimerHigh << 8));
                break;
            case 0x4003:
                p1TimerHigh = value & 0x07;
                if (p1Enabled)
                    p1LengthCounter = LENGTH_TABLE[(value >> 3) & 0x1F]; // Check enabled
                p1Sequence = 0;
                p1Envelope.start = true;
                p1Sweep.updateTargetPeriod(p1TimerLow | (p1TimerHigh << 8));
                break;

            // Pulse 2
            case 0x4004:
                p2Duty = (value >> 6) & 0x03;
                p2Envelope.loop = (value & 0x20) != 0;
                p2ConstantVol = (value & 0x10) != 0;
                p2Envelope.constantVolume = (value & 0x10) != 0;
                p2Volume = value & 0x0F;
                p2Envelope.volumePeriod = value & 0x0F;
                break;
            case 0x4005:
                p2Sweep.enabled = (value & 0x80) != 0;
                p2Sweep.period = (value >> 4) & 0x07;
                p2Sweep.negate = (value & 0x08) != 0;
                p2Sweep.shift = value & 0x07;
                p2Sweep.reload = true;
                break;
            case 0x4006:
                p2TimerLow = value;
                p2Sweep.updateTargetPeriod(p2TimerLow | (p2TimerHigh << 8));
                break;
            case 0x4007:
                p2TimerHigh = value & 0x07;
                if (p2Enabled)
                    p2LengthCounter = LENGTH_TABLE[(value >> 3) & 0x1F]; // Check enabled
                p2Sequence = 0;
                p2Envelope.start = true;
                p2Sweep.updateTargetPeriod(p2TimerLow | (p2TimerHigh << 8));
                break;

            // Triangle
            case 0x4008:
                triControl = (value & 0x80) != 0;
                triLinearCounterReload = value & 0x7F;
                break;
            case 0x4009:
                break;
            case 0x400A:
                triTimerLow = value;
                break;
            case 0x400B:
                triTimerHigh = value & 0x07;
                if (triEnabled)
                    triLengthCounter = LENGTH_TABLE[(value >> 3) & 0x1F]; // Check enabled
                triReloadLinear = true;
                break;

            // Noise
            case 0x400C:
                noiseLoop = (value & 0x20) != 0;
                noiseEnvelope.loop = (value & 0x20) != 0;
                noiseConstantVol = (value & 0x10) != 0;
                noiseEnvelope.constantVolume = (value & 0x10) != 0;
                noiseVolume = value & 0x0F;
                noiseEnvelope.volumePeriod = value & 0x0F;
                break;
            case 0x400E:
                noiseMode = (value & 0x80) != 0;
                noisePeriod = NOISE_PERIOD_TABLE[value & 0x0F];
                break;
            case 0x400F:
                if (noiseEnabled)
                    noiseLengthCounter = LENGTH_TABLE[(value >> 3) & 0x1F]; // Check enabled
                noiseEnvelope.start = true;
                break;

            // DMC
            case 0x4010:
                boolean irqEnabled = (value & 0x80) != 0;
                dmcLoop = (value & 0x40) != 0;
                dmcRateIndex = value & 0x0F;
                dmcPeriod = DMC_RATE_TABLE[dmcRateIndex];
                if (!irqEnabled) {
                    dmcIrqActive = false;
                    updateIrqOutput();
                }
                dmcIrqEnabled = irqEnabled;
                break;
            case 0x4011:
                dmcOutputLevel = value & 0x7F;
                break;
            case 0x4012:
                dmcSampleAddress = 0xC000 + (value * 64);
                break;
            case 0x4013:
                dmcSampleLength = (value * 16) + 1;
                break;

            // Control & Status
            case 0x4015:
                p1Enabled = (value & 0x01) != 0;
                p2Enabled = (value & 0x02) != 0;
                triEnabled = (value & 0x04) != 0;
                noiseEnabled = (value & 0x08) != 0;

                if (!p1Enabled)
                    p1LengthCounter = 0;
                if (!p2Enabled)
                    p2LengthCounter = 0;
                if (!triEnabled)
                    triLengthCounter = 0;
                if (!noiseEnabled)
                    noiseLengthCounter = 0;

                if ((value & 0x10) != 0) {
                    if (dmcBytesRemaining == 0) {
                        dmcCurrentAddress = dmcSampleAddress;
                        dmcBytesRemaining = dmcSampleLength;
                    }
                } else {
                    dmcBytesRemaining = 0;
                    dmcIrqActive = false; // Disable clears IRQ
                    updateIrqOutput();
                }
                break;

            case 0x4017:
                pendingFrameCounterMode = (value & 0x80) != 0;
                pendingFrameIrqEnabled = (value & 0x40) == 0;

                if (!pendingFrameIrqEnabled) {
                    // Disable Frame IRQ immediately
                    frameIrqActive = false;
                    frameIrqEnabled = false; // Also update current enabled state immediate?
                    // No, 'pendingFrameIrqEnabled' applies to the *Sequence*.
                    // But the *Flag* clearing is immediate.
                    updateIrqOutput();
                }
                pendingWrite = true;

                // Jitter/Delay Logic
                // With CPU.totalCycles updated at START of instruction,
                // we are now writing at the "End Time" (Cycle 3/4).
                // This matches hardware behavior.
                // HW: Odd Write -> Delay 4, Even Write -> Delay 3.
                if (cpu != null && (cpu.getTotalCycles() & 1) != 0) {
                    frameCounterResetDelay = 4;
                } else {
                    frameCounterResetDelay = 3;
                }
                break;
        }
    }

    private void updateIrqOutput() {
        irqActive = frameIrqActive || dmcIrqActive;
    }

    // === Execution ===

    // Pending State for $4017
    private boolean pendingFrameCounterMode;
    private boolean pendingFrameIrqEnabled;
    private boolean pendingWrite;

    public void tick(int cycles) {
        while (cycles-- > 0) {
            // Run logic EVERY cycle (Oversampling)

            // 1. Step Channels
            stepPulse1();
            stepPulse2();
            stepTriangle();
            stepNoise();
            stepDMC();

            // 2. Accumulate Outputs (SKIP IF MUTED to save CPU)
            if (!muted) {
                if (p1LengthCounter > 0 && !p1Sweep.mute && DUTY_TABLE[p1Duty][p1Sequence] != 0) {
                    p1Sum += p1Envelope.output;
                }
                if (p2LengthCounter > 0 && !p2Sweep.mute && DUTY_TABLE[p2Duty][p2Sequence] != 0) {
                    p2Sum += p2Envelope.output;
                }
                if (triLengthCounter > 0 && triLinearCounter > 0) {
                    triSum += TRIANGLE_SEQUENCE[triSequence];
                }
                if (noiseLengthCounter > 0 && (noiseShiftRegister & 0x01) == 0) {
                    noiseSum += noiseEnvelope.output;
                }
                if (dmcBytesRemaining > 0 || dmcBitsRemaining > 0) {
                    dmcSum += dmcOutputLevel;
                }
            }

            sampleCount++;
            cycleCounter++;

            // 3. Downsample & Generate
            if (cycleCounter >= CYCLES_PER_SAMPLE) {
                cycleCounter -= CYCLES_PER_SAMPLE;
                if (!muted) {
                    generateSample();
                } else {
                    // FAST RESET for Muted Mode
                    p1Sum = 0;
                    p2Sum = 0;
                    triSum = 0;
                    noiseSum = 0;
                    dmcSum = 0;
                    sampleCount = 0;
                    // Skip generateSample() overhead entirely
                }
            }

            // 4. Frame Counter Stepping (Handle Reset Delay)
            if (frameCounterResetDelay > 0) {
                frameCounterResetDelay--;
                if (frameCounterResetDelay == 0) {
                    // Apply Pending State
                    if (pendingWrite) {
                        frameCounterMode = pendingFrameCounterMode;
                        frameIrqEnabled = pendingFrameIrqEnabled;
                        if (!frameIrqEnabled) {
                            frameIrqActive = false;
                            updateIrqOutput();
                        }
                        pendingWrite = false;
                    }

                    frameCycle = 0;
                    if (frameCounterMode) { // Mode 1: Clock immediately
                        clockQuarterFrame();
                        clockHalfFrame();
                    }
                }
            }
            stepFrameCounter();
        }
    }

    public void tick() {
        tick(1);
    }

    // --- Steppers ---
    private void stepPulse1() {
        if (p1Timer > 0) {
            p1Timer--;
        } else {
            p1Timer = (p1TimerLow | (p1TimerHigh << 8)) * 2 + 1; // Pulse runs at CPU/2
            p1Sequence = (p1Sequence + 1) & 7;
        }
    }

    private void stepPulse2() {
        if (p2Timer > 0) {
            p2Timer--;
        } else {
            p2Timer = (p2TimerLow | (p2TimerHigh << 8)) * 2 + 1;
            p2Sequence = (p2Sequence + 1) & 7;
        }
    }

    private void stepTriangle() {
        if (triTimer > 0) {
            triTimer--;
        } else {
            triTimer = triTimerLow | (triTimerHigh << 8);
            if (triLengthCounter > 0 && triLinearCounter > 0) {
                triSequence = (triSequence + 1) & 31;
            }
        }
    }

    private void stepNoise() {
        if (noiseTimer > 0) {
            noiseTimer--;
        } else {
            noiseTimer = noisePeriod;
            int feedback;
            if (noiseMode)
                feedback = (noiseShiftRegister & 0x01) ^ ((noiseShiftRegister >> 6) & 0x01);
            else
                feedback = (noiseShiftRegister & 0x01) ^ ((noiseShiftRegister >> 1) & 0x01);
            noiseShiftRegister >>= 1;
            noiseShiftRegister |= (feedback << 14);
        }
    }

    private void stepDMC() {
        if (dmcPeriod > 0) {
            if (dmcTimer > 0)
                dmcTimer--;
            else {
                // Counting period..0 inclusive would take period+1 cycles; the
                // rate table is the period itself, so reload one short.
                dmcTimer = dmcPeriod - 1;
                // DMC Logic
                if (!dmcSilence) {
                    if ((dmcShiftRegister & 0x01) != 0) {
                        if (dmcOutputLevel <= 125)
                            dmcOutputLevel += 2;
                    } else {
                        if (dmcOutputLevel >= 2)
                            dmcOutputLevel -= 2;
                    }
                    dmcShiftRegister >>= 1;
                    dmcBitsRemaining--;
                }
                if (dmcBitsRemaining <= 0) {
                    dmcBitsRemaining = 8;
                    if (dmcBufferEmpty) {
                        dmcSilence = true;
                    } else {
                        dmcSilence = false;
                        dmcShiftRegister = dmcBuffer;
                        dmcBufferEmpty = true;
                    }
                }
                if (dmcBufferEmpty && dmcBytesRemaining > 0) {
                    // The sample fetch is a DMA: it halts the CPU for a few
                    // cycles while the APU takes the bus. Games with heavy DMC
                    // use are timed around this, so without it their audio and
                    // raster effects drift.
                    dmcStallCycles += 4;
                    // peek(), not read(): this runs from inside apu.tick(),
                    // which is itself driven by the bus clock. A read() here
                    // would re-enter the clock - ticking the PPU and the APU
                    // again from within an APU tick - and dilate time on every
                    // sample fetch. (The real DMA steals a CPU cycle; that
                    // belongs in the CPU, not in a nested tick.)
                    if (memory != null)
                        dmcBuffer = memory.peek(dmcCurrentAddress);
                    dmcCurrentAddress = (dmcCurrentAddress + 1) & 0xFFFF;
                    if (dmcCurrentAddress == 0)
                        dmcCurrentAddress = 0x8000;
                    dmcBytesRemaining--;
                    if (dmcBytesRemaining == 0) {
                        if (dmcLoop) {
                            dmcCurrentAddress = dmcSampleAddress;
                            dmcBytesRemaining = dmcSampleLength;
                        } else if (dmcIrqEnabled) {
                            dmcIrqActive = true;
                            updateIrqOutput();
                        }
                    }
                    dmcBufferEmpty = false;
                }
            }
        }
    }

    private void stepFrameCounter() {
        frameCycle++;
        if (frameCycle == 7457)
            clockQuarterFrame();
        if (frameCycle == 14913) {
            clockQuarterFrame();
            clockHalfFrame();
        }
        if (frameCycle == 22371)
            clockQuarterFrame();
        // In 4-step mode the frame IRQ is asserted across three consecutive
        // cycles (29828-29830), not just on the sequencer step itself.
        if (!frameCounterMode && frameIrqEnabled
                && frameCycle >= 29828 && frameCycle <= 29830) {
            frameIrqActive = true;
            updateIrqOutput();
        }

        if (frameCycle == 29829) {
            if (!frameCounterMode) {
                clockQuarterFrame();
                clockHalfFrame();
            }
        }
        if (frameCycle == 37281) {
            if (frameCounterMode) {
                clockQuarterFrame();
                clockHalfFrame();
            }
            frameCycle = 0;
        }
        if (!frameCounterMode && frameCycle >= 29830)
            frameCycle = 0;
    }

    private void clockQuarterFrame() {
        p1Envelope.clock();
        p2Envelope.clock();
        noiseEnvelope.clock();

        if (triReloadLinear)
            triLinearCounter = triLinearCounterReload;
        else if (triLinearCounter > 0)
            triLinearCounter--;
        if (!triControl)
            triReloadLinear = false;
    }

    private void clockHalfFrame() {
        if (!p1Envelope.loop && p1LengthCounter > 0)
            p1LengthCounter--;
        if (!p2Envelope.loop && p2LengthCounter > 0)
            p2LengthCounter--;
        if (!triControl && triLengthCounter > 0)
            triLengthCounter--;
        if (!noiseLoop && noiseLengthCounter > 0)
            noiseLengthCounter--;

        p1Sweep.clock(p1TimerLow | (p1TimerHigh << 8), 0);
        p2Sweep.clock(p2TimerLow | (p2TimerHigh << 8), 1);
    }

    private boolean muted = false;

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    private void generateSample() {
        if (sampleCount == 0)
            return;

        double outP1 = p1Sum / sampleCount;
        double outP2 = p2Sum / sampleCount;
        double outTri = triSum / sampleCount;
        double outNoise = noiseSum / sampleCount;
        double outDMC = dmcSum / sampleCount;

        // --- Hardware Accurate Mixing ---
        double pulseOut = 0;
        if (outP1 > 0 || outP2 > 0) {
            pulseOut = 95.88 / ((8128.0 / (outP1 + outP2)) + 100.0);
        }

        double tndOut = 0;
        if (outTri > 0 || outNoise > 0 || outDMC > 0) {
            double denom = (outTri / 8227.0) + (outNoise / 12241.0) + (outDMC / 22638.0);
            if (denom > 0)
                tndOut = 159.79 / ((1.0 / denom) + 100.0);
        }

        double output = pulseOut + tndOut;

        // --- Output filter chain ---
        // The NES runs its mixed output through RC filters before the jack:
        // two high-passes (90Hz and 440Hz) and a low-pass at ~14kHz. The
        // low-pass matters most here - averaging the per-cycle accumulator down
        // to 44.1kHz is only a box filter, so without it the harmonics of high
        // notes fold back as aliasing and the top end sounds gritty.
        hp90Prev = HP90_A * (hp90Prev + output - hp90PrevIn);
        hp90PrevIn = output;
        output = hp90Prev;

        hp440Prev = HP440_A * (hp440Prev + output - hp440PrevIn);
        hp440PrevIn = output;
        output = hp440Prev;

        lp14Prev += LP14_A * (output - lp14Prev);
        output = lp14Prev;

        if (output > 1.0)
            output = 1.0;
        if (output < -1.0)
            output = -1.0;

        short finalSample = (short) (output * 32767.0);

        outputBuffer[outputIndex++] = (byte) (finalSample & 0xFF);
        outputBuffer[outputIndex++] = (byte) ((finalSample >> 8) & 0xFF);

        if (outputIndex >= outputBuffer.length) {
            if (line != null && !muted)
                line.write(outputBuffer, 0, outputBuffer.length);
            outputIndex = 0;
        }

        // Start a fresh accumulation window; without this every sample is the
        // running average since power-on, which the high-pass filter flattens
        // to silence.
        p1Sum = 0;
        p2Sum = 0;
        triSum = 0;
        noiseSum = 0;
        dmcSum = 0;
        sampleCount = 0;
    }

    public APU copy(Memory newMemory, CPU newCPU) {
        // A clone is a snapshot, not a second console: it gets no output device
        // at all. Opening one here used to strand a playback line with the
        // system mixer for every discarded clone.
        APU newAPU = new APU(AudioSink.SILENT);
        newAPU.setMuted(true);
        newAPU.setMemory(newMemory);
        newAPU.setCpu(newCPU);

        return newAPU;
    }

    public void fastCopyFrom(APU source) {
        // Copy State
        this.cycleCounter = source.cycleCounter;
        this.sampleCount = source.sampleCount;
        this.p1Sum = source.p1Sum;
        this.p2Sum = source.p2Sum;
        this.triSum = source.triSum;
        this.noiseSum = source.noiseSum;
        this.dmcSum = source.dmcSum;

        this.irqActive = source.irqActive;
        this.frameIrqEnabled = source.frameIrqEnabled;
        this.frameIrqActive = source.frameIrqActive;
        this.dmcIrqActive = source.dmcIrqActive;

        this.frameCounterMode = source.frameCounterMode;
        this.frameCycle = source.frameCycle;
        this.frameCounterResetDelay = source.frameCounterResetDelay;

        this.p1Enabled = source.p1Enabled;
        this.p2Enabled = source.p2Enabled;
        this.triEnabled = source.triEnabled;
        this.noiseEnabled = source.noiseEnabled;

        // P1
        this.p1Envelope.copyFrom(source.p1Envelope);
        this.p1Sweep.copyFrom(source.p1Sweep);
        this.p1Duty = source.p1Duty;
        this.p1ConstantVol = source.p1ConstantVol;
        this.p1Volume = source.p1Volume;
        this.p1TimerLow = source.p1TimerLow;
        this.p1TimerHigh = source.p1TimerHigh;
        this.p1Timer = source.p1Timer;
        this.p1LengthCounter = source.p1LengthCounter;
        this.p1Sequence = source.p1Sequence;

        // P2
        this.p2Envelope.copyFrom(source.p2Envelope);
        this.p2Sweep.copyFrom(source.p2Sweep);
        this.p2Duty = source.p2Duty;
        this.p2ConstantVol = source.p2ConstantVol;
        this.p2Volume = source.p2Volume;
        this.p2TimerLow = source.p2TimerLow;
        this.p2TimerHigh = source.p2TimerHigh;
        this.p2Timer = source.p2Timer;
        this.p2LengthCounter = source.p2LengthCounter;
        this.p2Sequence = source.p2Sequence;

        // Triangle
        this.triControl = source.triControl;
        this.triLinearCounterReload = source.triLinearCounterReload;
        this.triTimerLow = source.triTimerLow;
        this.triTimerHigh = source.triTimerHigh;
        this.triTimer = source.triTimer;
        this.triLengthCounter = source.triLengthCounter;
        this.triLinearCounter = source.triLinearCounter;
        this.triReloadLinear = source.triReloadLinear;
        this.triSequence = source.triSequence;

        // Noise
        this.noiseEnvelope.copyFrom(source.noiseEnvelope);
        this.noiseLoop = source.noiseLoop;
        this.noiseConstantVol = source.noiseConstantVol;
        this.noiseVolume = source.noiseVolume;
        this.noiseMode = source.noiseMode;
        this.noisePeriod = source.noisePeriod;
        this.noiseTimer = source.noiseTimer;
        this.noiseLengthCounter = source.noiseLengthCounter;
        this.noiseShiftRegister = source.noiseShiftRegister;

        // DMC
        this.dmcIrqEnabled = source.dmcIrqEnabled;
        this.dmcLoop = source.dmcLoop;
        this.dmcRateIndex = source.dmcRateIndex;
        this.dmcOutputLevel = source.dmcOutputLevel;
        this.dmcSampleAddress = source.dmcSampleAddress;
        this.dmcSampleLength = source.dmcSampleLength;
        this.dmcCurrentAddress = source.dmcCurrentAddress;
        this.dmcBytesRemaining = source.dmcBytesRemaining;
        this.dmcBuffer = source.dmcBuffer;
        this.dmcBufferEmpty = source.dmcBufferEmpty;
        this.dmcShiftRegister = source.dmcShiftRegister;
        this.dmcBitsRemaining = source.dmcBitsRemaining;
        this.dmcSilence = source.dmcSilence;
        this.dmcPeriod = source.dmcPeriod;
        this.dmcTimer = source.dmcTimer;
    }

    // === Serialization ===

    public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
        dos.writeDouble(cycleCounter);
        dos.writeInt(sampleCount);
        dos.writeDouble(p1Sum);
        dos.writeDouble(p2Sum);
        dos.writeDouble(triSum);
        dos.writeDouble(noiseSum);
        dos.writeDouble(dmcSum);

        dos.writeBoolean(irqActive);
        dos.writeBoolean(frameIrqEnabled);
        dos.writeBoolean(frameIrqActive);
        dos.writeBoolean(dmcIrqActive);

        dos.writeBoolean(frameCounterMode);
        dos.writeInt(frameCycle);
        dos.writeInt(frameCounterResetDelay);

        dos.writeBoolean(p1Enabled);
        dos.writeBoolean(p2Enabled);
        dos.writeBoolean(triEnabled);
        dos.writeBoolean(noiseEnabled);

        // P1
        p1Envelope.saveState(dos);
        p1Sweep.saveState(dos);
        dos.writeInt(p1Duty);
        dos.writeBoolean(p1ConstantVol);
        dos.writeInt(p1Volume);
        dos.writeInt(p1TimerLow);
        dos.writeInt(p1TimerHigh);
        dos.writeInt(p1Timer);
        dos.writeInt(p1LengthCounter);
        dos.writeInt(p1Sequence);

        // P2
        p2Envelope.saveState(dos);
        p2Sweep.saveState(dos);
        dos.writeInt(p2Duty);
        dos.writeBoolean(p2ConstantVol);
        dos.writeInt(p2Volume);
        dos.writeInt(p2TimerLow);
        dos.writeInt(p2TimerHigh);
        dos.writeInt(p2Timer);
        dos.writeInt(p2LengthCounter);
        dos.writeInt(p2Sequence);

        // Triangle
        dos.writeBoolean(triControl);
        dos.writeInt(triLinearCounterReload);
        dos.writeInt(triTimerLow);
        dos.writeInt(triTimerHigh);
        dos.writeInt(triTimer);
        dos.writeInt(triLengthCounter);
        dos.writeInt(triLinearCounter);
        dos.writeBoolean(triReloadLinear);
        dos.writeInt(triSequence);

        // Noise
        noiseEnvelope.saveState(dos);
        dos.writeBoolean(noiseLoop);
        dos.writeBoolean(noiseConstantVol);
        dos.writeInt(noiseVolume);
        dos.writeBoolean(noiseMode);
        dos.writeInt(noisePeriod);
        dos.writeInt(noiseTimer);
        dos.writeInt(noiseLengthCounter);
        dos.writeInt(noiseShiftRegister);

        // DMC
        dos.writeBoolean(dmcIrqEnabled);
        dos.writeBoolean(dmcLoop);
        dos.writeInt(dmcRateIndex);
        dos.writeInt(dmcOutputLevel);
        dos.writeInt(dmcSampleAddress);
        dos.writeInt(dmcSampleLength);
        dos.writeInt(dmcCurrentAddress);
        dos.writeInt(dmcBytesRemaining);
        dos.writeInt(dmcBuffer);
        dos.writeBoolean(dmcBufferEmpty);
        dos.writeInt(dmcShiftRegister);
        dos.writeInt(dmcBitsRemaining);
        dos.writeBoolean(dmcSilence);
        dos.writeInt(dmcPeriod);
        dos.writeInt(dmcTimer);
    }

    public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
        this.cycleCounter = dis.readDouble();
        this.sampleCount = dis.readInt();
        this.p1Sum = dis.readDouble();
        this.p2Sum = dis.readDouble();
        this.triSum = dis.readDouble();
        this.noiseSum = dis.readDouble();
        this.dmcSum = dis.readDouble();

        this.irqActive = dis.readBoolean();
        this.frameIrqEnabled = dis.readBoolean();
        this.frameIrqActive = dis.readBoolean();
        this.dmcIrqActive = dis.readBoolean();

        this.frameCounterMode = dis.readBoolean();
        this.frameCycle = dis.readInt();
        this.frameCounterResetDelay = dis.readInt();

        this.p1Enabled = dis.readBoolean();
        this.p2Enabled = dis.readBoolean();
        this.triEnabled = dis.readBoolean();
        this.noiseEnabled = dis.readBoolean();

        // P1
        p1Envelope.loadState(dis);
        p1Sweep.loadState(dis);
        this.p1Duty = dis.readInt();
        this.p1ConstantVol = dis.readBoolean();
        this.p1Volume = dis.readInt();
        this.p1TimerLow = dis.readInt();
        this.p1TimerHigh = dis.readInt();
        this.p1Timer = dis.readInt();
        this.p1LengthCounter = dis.readInt();
        this.p1Sequence = dis.readInt();

        // P2
        p2Envelope.loadState(dis);
        p2Sweep.loadState(dis);
        this.p2Duty = dis.readInt();
        this.p2ConstantVol = dis.readBoolean();
        this.p2Volume = dis.readInt();
        this.p2TimerLow = dis.readInt();
        this.p2TimerHigh = dis.readInt();
        this.p2Timer = dis.readInt();
        this.p2LengthCounter = dis.readInt();
        this.p2Sequence = dis.readInt();

        // Triangle
        this.triControl = dis.readBoolean();
        this.triLinearCounterReload = dis.readInt();
        this.triTimerLow = dis.readInt();
        this.triTimerHigh = dis.readInt();
        this.triTimer = dis.readInt();
        this.triLengthCounter = dis.readInt();
        this.triLinearCounter = dis.readInt();
        this.triReloadLinear = dis.readBoolean();
        this.triSequence = dis.readInt();

        // Noise
        noiseEnvelope.loadState(dis);
        this.noiseLoop = dis.readBoolean();
        this.noiseConstantVol = dis.readBoolean();
        this.noiseVolume = dis.readInt();
        this.noiseMode = dis.readBoolean();
        this.noisePeriod = dis.readInt();
        this.noiseTimer = dis.readInt();
        this.noiseLengthCounter = dis.readInt();
        this.noiseShiftRegister = dis.readInt();

        // DMC
        this.dmcIrqEnabled = dis.readBoolean();
        this.dmcLoop = dis.readBoolean();
        this.dmcRateIndex = dis.readInt();
        this.dmcOutputLevel = dis.readInt();
        this.dmcSampleAddress = dis.readInt();
        this.dmcSampleLength = dis.readInt();
        this.dmcCurrentAddress = dis.readInt();
        this.dmcBytesRemaining = dis.readInt();
        this.dmcBuffer = dis.readInt();
        this.dmcBufferEmpty = dis.readBoolean();
        this.dmcShiftRegister = dis.readInt();
        this.dmcBitsRemaining = dis.readInt();
        this.dmcSilence = dis.readBoolean();
        this.dmcPeriod = dis.readInt();
        this.dmcTimer = dis.readInt();
    }

    // === Inner Classes ===

    private class Envelope {
        public boolean start = false;
        public boolean loop = false;
        public boolean constantVolume = false;
        public int volumePeriod = 0;
        public int output = 0;
        private int decayCount = 0;
        private int dividerCount = 0;

        public void copyFrom(Envelope other) {
            this.start = other.start;
            this.loop = other.loop;
            this.constantVolume = other.constantVolume;
            this.volumePeriod = other.volumePeriod;
            this.output = other.output;
            this.decayCount = other.decayCount;
            this.dividerCount = other.dividerCount;
        }

        public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
            dos.writeBoolean(start);
            dos.writeBoolean(loop);
            dos.writeBoolean(constantVolume);
            dos.writeInt(volumePeriod);
            dos.writeInt(output);
            dos.writeInt(decayCount);
            dos.writeInt(dividerCount);
        }

        public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
            this.start = dis.readBoolean();
            this.loop = dis.readBoolean();
            this.constantVolume = dis.readBoolean();
            this.volumePeriod = dis.readInt();
            this.output = dis.readInt();
            this.decayCount = dis.readInt();
            this.dividerCount = dis.readInt();
        }

        public void clock() {
            if (!start) {
                if (dividerCount == 0) {
                    dividerCount = volumePeriod;
                    if (decayCount > 0)
                        decayCount--;
                    else if (loop)
                        decayCount = 15;
                } else
                    dividerCount--;
            } else {
                start = false;
                decayCount = 15;
                dividerCount = volumePeriod;
            }
            if (constantVolume)
                output = volumePeriod;
            else
                output = decayCount;
        }
    }

    private class Sweep {
        public boolean enabled = false;
        public boolean negate = false;
        public boolean reload = false;
        public int period = 0;
        public int shift = 0;
        public boolean mute = false;
        private int divider = 0;
        private Envelope envelope;

        public Sweep(Envelope e) {
            this.envelope = e;
        }

        public void copyFrom(Sweep other) {
            this.enabled = other.enabled;
            this.negate = other.negate;
            this.reload = other.reload;
            this.period = other.period;
            this.shift = other.shift;
            this.mute = other.mute;
            this.divider = other.divider;
        }

        public void saveState(java.io.DataOutputStream dos) throws java.io.IOException {
            dos.writeBoolean(enabled);
            dos.writeBoolean(negate);
            dos.writeBoolean(reload);
            dos.writeInt(period);
            dos.writeInt(shift);
            dos.writeBoolean(mute);
            dos.writeInt(divider);
        }

        public void loadState(java.io.DataInputStream dis) throws java.io.IOException {
            this.enabled = dis.readBoolean();
            this.negate = dis.readBoolean();
            this.reload = dis.readBoolean();
            this.period = dis.readInt();
            this.shift = dis.readInt();
            this.mute = dis.readBoolean();
            this.divider = dis.readInt();
        }

        public void updateTargetPeriod(int currentPeriod) {
            int change = currentPeriod >> shift;
            int target;
            if (negate)
                target = currentPeriod - change;
            else
                target = currentPeriod + change;
            mute = (currentPeriod < 8) || (target > 0x7FF);
        }

        public void clock(int currentPeriod, int channel) {
            if (divider == 0 && enabled && !mute && shift > 0) {
                int change = currentPeriod >> shift;
                int target = negate ? (currentPeriod - change - (channel == 0 ? 1 : 0)) : (currentPeriod + change);
                if (target >= 0 && target <= 0x7FF) {
                    if (channel == 0) {
                        p1TimerLow = target & 0xFF;
                        p1TimerHigh = (target >> 8) & 0x07;
                    } else {
                        p2TimerLow = target & 0xFF;
                        p2TimerHigh = (target >> 8) & 0x07;
                    }
                }
            }
            if (divider == 0 || reload) {
                divider = period;
                reload = false;
            } else
                divider--;
            updateTargetPeriod(currentPeriod);
        }
    }

}
