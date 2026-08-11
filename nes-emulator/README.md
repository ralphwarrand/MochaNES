# MochaNES Emulator Core

This module (`nes-emulator`) contains the NES hardware emulation and the desktop
front-end. It has no dependency on the AI module.

## Components

* **CPU** — MOS 6502, official and unofficial opcodes, with exact per-instruction
  cycle counts (verified by `instr_timing`). Verified against `nestest.log`.
* **PPU** — scanline/dot renderer with per-scanline sprite evaluation (8-sprite
  limit and overflow flag), left-column clipping, open-bus decay and the
  `$2007` read buffer.
* **APU** — pulse ×2, triangle, noise and DMC, with the hardware output filter
  chain (90 Hz + 440 Hz high-pass, 14 kHz low-pass) and DMC DMA cycle stealing.
* **Memory** — MMU handling mirroring and mapper routing.
* **Mappers** — NROM (0), MMC1 (1), UxROM (2), CNROM (3), MMC3 (4) incl. the
  scanline IRQ, and AxROM (7).

The CPU drives the clock from the bus: every 6502 cycle is a memory access, so
the PPU and APU advance *during* an instruction rather than being caught up
afterwards. `NES.stepInstruction()` is the single definition of a system step.

## Front-end

* Window scaling 1×–6×, aspect modes (pixel-perfect / 4:3 / stretch), fullscreen
  with `Alt+Enter`.
* Rebindable controls and Linux gamepad support (`/dev/input/js*`, no external
  dependencies). Settings persist to `~/.config/mochanes/settings.properties`.
* Optional CRT simulation — electron-beam spot with intensity-dependent
  blooming, phosphor persistence, NTSC chroma bandwidth, halation and shadow
  mask. Toggle with `F1`; see **Help → Controls** for the full key list.

## Testing

```bash
mvn test -pl nes-emulator
```

`BlarggTestRomTest` runs the accuracy ROMs headlessly, reading each result from
the `$6000` protocol. The ROM set under `roms/` is gitignored, so those cases
*skip* rather than fail when the ROMs are absent — a fresh clone stays green.

Suites that do not pass yet are listed in `BlarggTestRomTest.KNOWN_FAILING` with
the reason, rather than being quietly omitted. The main outstanding area is
sub-instruction PPU/interrupt timing (`ppu_vbl_nmi`, `cpu_interrupts_v2`).

## Documentation

See the [Architecture Guide](../docs/Architecture.md) for a deeper dive.
