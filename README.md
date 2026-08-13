# MochaNES

A Nintendo Entertainment System emulator written in Java.

The 6502 and the 2C02 are driven by a shared bus clock, so the PPU and APU
advance *during* an instruction rather than being caught up afterwards. Every
opcode and every instruction's cycle count is verified against blargg's test
ROMs. There is also an optional CRT simulation that models the electron beam
rather than overlaying a scanline texture.

Timing below the level of a single CPU cycle is not modelled yet, which is
where the remaining test failures live - the [Accuracy Report](docs/Accuracy.md)
sets out exactly what passes and what does not.

![Java CI with Maven](https://github.com/ralphwarrand/MochaNES/actions/workflows/maven.yml/badge.svg)

---

**[Play it in your browser](https://ralphwarrand.github.io/MochaNES/)** - the
same emulator compiled to JavaScript, CRT simulation included. Drop in a ROM;
nothing is uploaded.

## Quick start

Requires **JDK 17+** and **Maven 3.8+**.

```bash
git clone https://github.com/ralphwarrand/MochaNES.git
cd MochaNES
./run.sh
```

That builds the project and boots `resources/nestest.nes`. To play a game:

```bash
./run.sh smb                  # bare name, searched for under roms/
./run.sh roms/MMC3/kirby.nes  # or an explicit path
./run.sh --crt trinitron smb  # with the CRT simulation on
./run.sh --list               # what's available
```

ROMs are not included and `roms/` is gitignored. Put your own anywhere
underneath it; the layout is up to you.

**Controls:** arrow keys, `Z`/`X` for A/B, `Shift`/`Enter` for Select/Start -
all rebindable. Gamepads work on Linux with no setup. `Alt+Enter` for
fullscreen, `F1` for the CRT.

---

## What it does

**Emulation**
- A 6502 with all official and unofficial opcodes, exact per-instruction
  cycle counts, and correct interrupt-poll behaviour.
- The clock is driven by the bus: every CPU cycle is a memory access, and each
  one advances the PPU three dots and the APU one cycle. Reads of `$2002`
  mid-instruction therefore see the PPU state of that exact cycle.
- Per-scanline sprite evaluation into secondary OAM, with the hardware 8-sprite
  limit and overflow flag.
- Full APU - two pulses, triangle, noise and DMC - with the hardware's
  non-linear mixing and its 90Hz/440Hz/14kHz filter chain.
- Mappers 0, 1, 2, 3, 4 and 7 (NROM, MMC1, UxROM, CNROM, MMC3, AxROM),
  including the MMC3 scanline IRQ that drives mid-frame splits.

**Front-end**
- Fullscreen, 1x-6x window scaling, and pixel-perfect / 4:3 / stretch aspect.
- Rebindable keys and Linux gamepad support, persisted to
  `~/.config/mochanes/settings.properties`.
- A debugger with disassembly, memory and CHR-ROM views, reading through a
  non-intrusive `peek()` so inspection cannot perturb timing.

**CRT simulation** (optional, off by default)

Models a beam spot whose width grows with drive level, phosphor persistence,
NTSC chroma bandwidth in YIQ, halation, and aperture-grille / shadow / slot
masks - composited in linear light. Four presets, and live dials for mask
strength, bloom and focus.

---

## Accuracy

Verified against blargg's test ROMs, which report machine-readable results, so
this is measured rather than estimated:

| Suite | Passing | What it covers |
|---|---|---|
| `instr_test-v5` | **16 / 16** | Every official and unofficial opcode |
| `instr_misc` | **4 / 4** | Wrapping, dummy reads |
| `instr_timing` | **2 / 2** | Exact per-instruction cycle counts |
| `mmc3_test_2` | **4 / 6** | MMC3 banking and scanline IRQ |
| `apu_test` | **3 / 8** | Length counters, frame IRQ, DMC |
| `cpu_interrupts_v2` | **1 / 5** | Interrupt timing |
| `ppu_vbl_nmi` | **1 / 10** | VBlank flag and NMI timing |
| **Overall** | **31 / 51** | |

Also passing standalone: `ppu_read_buffer`, `oam_read`, `oam_stress`,
`ppu_open_bus`, `cpu_exec_space`, `cpu_dummy_writes`.

Every game in the test library boots and plays, across four mappers.
Headless throughput is roughly 8-9x realtime.

The remaining failures are concentrated in `ppu_vbl_nmi` and
`cpu_interrupts_v2`, which measure sub-instruction timing the PPU does not yet
resolve. The [Accuracy Report](docs/Accuracy.md) documents exactly what fails,
what was ruled out by measurement, and why.

---

## Documentation

* **[Architecture Reference](docs/Architecture.md)** - the clock model, CPU,
  PPU, APU, mappers, and the decisions that are not obvious from the code.
* **[Accuracy Report](docs/Accuracy.md)** - test results, known failures and
  their causes, and the bugs the suite caught.
* **[Tools Guide](docs/Tools_Guide.md)** - running, controls, CRT dials,
  debugger, hooks and replay.
* **[Roadmap](docs/Roadmap.md)** - a critical look at what is weak and what to
  do about it, in order.
* **[Contributing](CONTRIBUTING.md)** - how to build, what the tests really
  cover, and the two traps that are not obvious from the code.
* **[References](docs/References.md)** - the hardware documentation this was
  built from, grouped by subsystem, starting with the
  [NESdev Wiki](https://www.nesdev.org/wiki/Nesdev_Wiki).

---

## Building and testing

```bash
mvn clean verify     # build and run the test suite
./run.sh --test      # the same, via the launcher
```

The unit tests run anywhere. The 37 ROM-backed accuracy tests **skip** when
their ROM is absent, so a fresh clone and CI stay green - which also means a
green badge does not imply the accuracy suite ran. See the Accuracy Report.

---

## The browser build

The emulation core has no dependency on Swing, `javax.sound` or the filesystem -
it talks to a `FrameSink` and an `AudioSink` - so the same code compiles to
JavaScript with [TeaVM](https://teavm.org/) and runs unmodified on a canvas.

```bash
mvn -Pweb package        # output in nes-web/target/site
```

It is built behind a profile so an ordinary build needs nothing but a JDK.
Pushes to `main` deploy it to GitHub Pages automatically.

Output is **bit-identical** to the JVM: running nestest for 6.3M instructions
gives the same pixel hash, RAM hash and program counter in both. Throughput is
about 2.6x realtime in JavaScript against 10x on the JVM, which leaves ample
headroom for 60fps.

The CRT simulation is a WebGL fragment shader rather than a port of the CPU
filter - beam profiles, shadow masks and bloom are what GPUs are for, so it
costs almost nothing. Save states clone the machine instead of serialising
it, and the debugger, disassembler and rebindable controls are all there.

## Project layout

```
MochaNES/
├── nes-emulator/     # CPU, PPU, APU, mappers, GUI, CRT filter
├── nes-web/          # Browser front-end (TeaVM, canvas, WebGL CRT)
├── docs/             # Architecture, accuracy and tooling references
├── resources/        # nestest ROM and its reference log
├── run.sh            # Launcher
└── roms/             # Your ROMs (gitignored)
```

A reinforcement-learning agent that plays through this emulator lives on the
[`ai`](https://github.com/ralphwarrand/MochaNES/tree/ai) branch, kept separate
so the emulator has no ML dependencies.

---

## Licence

GPL-3.0. See [LICENSE](LICENSE).

The files under `resources/` are third party and not covered by that licence -
`nestest.nes` is kevtris's homebrew test ROM and `nestest.log.txt` is its
Nintendulator reference trace. See [resources/README.md](resources/README.md)
for their provenance. No commercial game ROMs are included.
