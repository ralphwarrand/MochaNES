# Accuracy Report

What the emulator gets right, what it does not, and how that is measured.

Everything here is a test result, not an estimate. Where something is unknown it
says so.

---

## 1. How accuracy is measured

The [blargg test ROMs](https://github.com/christopherpow/nes-test-roms) are the
standard for NES emulator correctness. Each one exercises a narrow piece of
hardware behaviour and writes its verdict into PRG-RAM:

* `$6001-$6003` hold `DE B0 61` once output is valid
* `$6000` holds the status - `0x80` running, `0x81` wants a reset, otherwise the
  result code (`0` = pass)
* `$6004` onwards is a NUL-terminated message

`TestRomRunner` drives a ROM headlessly and reads that protocol, so results are
machine-checkable rather than something a human has to eyeball on screen.

```bash
mvn test -pl nes-emulator
```

### The ROM caveat

`roms/` is **gitignored**. The ROM set is not redistributable, so it is not in
the repository.

Tests whose ROM is missing **skip** rather than fail, which keeps a fresh clone
and CI green. The practical consequence: **a green CI badge does not mean the
accuracy suite ran.** On CI all 37 ROM-backed cases skip. Real coverage only
happens locally with the ROMs present.

To run them, place the test ROMs under `roms/test/` matching the paths in
`BlarggTestRomTest`.

---

## 2. Current status

| Suite | Passing | Notes |
|-------|---------|-------|
| `instr_test-v5` | **16 / 16** | Every official and unofficial opcode |
| `instr_misc` | **4 / 4** | Wrapping, dummy reads |
| `instr_timing` | **2 / 2** | Exact per-instruction cycle counts |
| `mmc3_test_2` | **4 / 6** | See below |
| `apu_test` | **3 / 8** | Length counters and IRQ flag basics |
| `cpu_interrupts_v2` | **1 / 5** | Interrupt-disable latency |
| `ppu_vbl_nmi` | **1 / 10** | NMI enable/disable control |
| **Suite total** | **31 / 51** | |

Standalone, all passing: `ppu_read_buffer`, `oam_read`, `oam_stress`,
`ppu_open_bus`, `cpu_exec_space` (both), `cpu_dummy_writes/oam`.

**Game compatibility: 8 / 8** of the library boots and plays - across NROM,
MMC1, UxROM and MMC3.

**Performance:** roughly 8-9x realtime headless.

---

## 3. Known failures, and why

These are recorded in `BlarggTestRomTest.KNOWN_FAILING` rather than quietly
omitted, so the list cannot rot without someone noticing.

### 3.1 Sub-instruction timing - `ppu_vbl_nmi`, `cpu_interrupts_v2`

The dominant remaining gap, and the reason ~14 tests fail.

The clock is bus-driven and per-instruction cycle counts are exact (proven by
`instr_timing`), but the PPU still advances in whole three-dot groups per CPU
cycle. These tests measure where the VBlank flag flips *within* a single CPU
cycle, and that resolution does not exist yet.

Fixing it means stepping the PPU dot-by-dot with the CPU able to observe between
dots - a restructure, not a patch.

Ruled out by measurement, so nobody repeats the work:

| Hypothesis | Result |
|---|---|
| Bus/cycle drift | Audited: 1,206,937 accesses vs 1,206,937 cycles - zero drift |
| VBlank window wrong | Probed: set at (241,1), cleared at (261,1), exactly 6820 dots |
| Tick phase (before/after access) | Tested both; before is better, neither passes |
| Splitting the 3 dots around the access | All four split points tested, no change |
| Reset phase offset | Scanned 0-14, no combination passes |
| VBlank suppression / odd-frame skip | A/B'd all four combinations, no change |
| Power-on frame phase | Found a real 2391-cycle offset and corrected it; tests unmoved, because `sync_vbl` resynchronises before every measurement |

Note: **FCEUX's accurate `newppu` core also fails part of this suite**
(it misses the row-04 flag-suppression case in `02-vbl_set_time`). These are
exotic even by emulator standards.

### 3.2 MMC3 - 2 of 6

* `4-scanline_timing` needs exact IRQ placement *within* a scanline - same
  sub-instruction limitation as above.
* `6-MMC3_alt` fails **by design, not as a defect.** It and `5-MMC3` test the
  two mutually exclusive MMC3 revisions; passing both is impossible. This
  emulator implements rev B, which `5-MMC3` checks.

### 3.3 APU - 3 of 8

`4-jitter`, `5-len_timing` and `6-irq_flag_timing` need frame-sequencer events
placed to sub-cycle precision. Scanning the sequencer phase and the frame-IRQ
offset changed nothing, which points at the same root cause.

`7-dmc_basics` and `8-dmc_rates` need finer DMC timing than the current model.

### 3.4 Left-edge corruption while scrolling (open)

Visible in Kirby's Adventure as wrong tiles and palettes in the leftmost few
pixels while the screen scrolls.

`RenderRegressionTest.scrollingMovesTheImageByExactlyOnePixel` reproduces it
without needing a reference emulator. Scrolling one pixel must move the picture
by exactly one pixel, and with horizontal mirroring the test ROM's background
repeats every 256 pixels, so the invariant is exact. It currently fails on
**columns 2 to 7**, four to eight rows each. The test is marked `@Ignore` so the
build stays green; remove that once this is fixed.

Columns 2-7 puts it in the background pipeline rather than in the timing work
above. The three candidates, in order of likelihood:

* **fine-X (`x`)** selecting the wrong bit of the shift registers
* **the attribute latch** reloading a dot early or late relative to the pattern
  shifters, which gives the right tile with the wrong palette
* **the next line's first tile**, fetched during dots 321-336, landing wrongly

That only a few rows per column disagree, rather than all 240, suggests the
fault is revealed by particular tile content rather than happening every line -
worth confirming before assuming which mechanism it is.

### 3.5 Not auto-verifiable

The 2005-era suites (`blargg_ppu_tests_2005`, `sprite_hit_tests`,
`sprite_overflow_tests`) predate the `$6000` protocol and only report on screen,
so they cannot be checked automatically. They are not counted above in either
direction.

---

## 4. Bugs this suite has caught

A record of what the tests actually bought, since several were invisible in
normal play:

| Bug | Found by | Symptom |
|---|---|---|
| `BRK` charged 14 cycles instead of 7 | `instr_timing` | Invisible until an opcode-sweeping test; most games never hit it |
| APU accumulators never reset | - | **All audio silent**; every sample was the running average since power-on |
| Stack pops did not wrap in page 1 | `instr_test-v5/11-stack` | `SP=$FF` pop read `$0200` |
| `SEI` missing interrupt latency | `cpu_interrupts_v2/1-cli_latency` | One IRQ swallowed that hardware allows |
| `$2007` palette read buffer | `ppu_read_buffer` | Test **hung**; fix also repaired `oam_stress` |
| ANE/LXA unimplemented | `instr_test-v5/03-immediate` | Fell into the JAM path and hung |
| Left-column clipping ignored | - | Garbage column while scrolling |
| Sprites scanned per pixel | - | No 8-sprite limit or overflow flag; ~4x slower |
| Missing 14kHz low-pass | - | Aliasing heard as gritty high end |
| DMC fetch re-entered the clock | - | Broke the 3:1 PPU:CPU invariant |
| No audio device crashed startup | CI | `IllegalArgumentException`, not `LineUnavailableException` |

---

## 5. Reference emulators

When a test failure was ambiguous, behaviour was diffed against other
emulators rather than argued from first principles.

* **FCEUX** (`--newppu`) - scriptable via Lua, good for `$2002` read traces.
* **Mesen 2** - the most accurate reference available. Its `--testRunner` mode
  crashes on some Linux builds (a static-init `std::regex` locale fault inside
  the bundled libstdc++), so tracing has to go through the GUI Script Window.

The technique that worked: log every `$2002` read with its CPU cycle count in
both emulators, then diff on cycle deltas and returned values. The first
divergence pins the bug. Both emulators disagreeing with each other is itself
informative - that is how the power-on phase offset was found.

See [References](References.md) for the full documentation set, including the
[test ROM catalogue](https://www.nesdev.org/wiki/Emulator_tests).
