# Changelog

Notable changes per release. Dates are release dates.

## Unreleased

### Added
- `RenderRegressionTest`: golden-frame tests for the PPU and APU built on ROMs
  assembled in code, so they run on CI where the accuracy suite cannot. Verified
  by mutation - removing the 8-sprite limit fails them.
- `RomBuilder`: a small 6502 assembler for building test ROMs in code.
- Frame dumping for debugging hash failures (`-Dmochanes.dumpFrames=<dir>`).
- Contributing guide, roadmap, issue and pull request templates.
- On-screen controls for touch devices in the browser version.
- `serve.sh`, which builds and serves the browser version and prints an address
  reachable from a phone on the same network.

### Fixed
- Fullscreen barely filled the screen: inline sizing beat the stylesheet, so the
  canvas stayed at its windowed width.
- The shadow mask blurred in fullscreen. It is drawn in drawing-buffer pixels,
  and the buffer was capped below the displayed size, so the browser rescaled it.
- Choppy motion in the browser. The loop ran one frame per animation frame,
  tying console speed to the display's refresh rate; it now follows real time.
- Only some display settings survived a reload. Aspect, scale, volume,
  saturation and brightness now persist along with the CRT dials.

### Changed
- The debugger no longer opens at launch. Use **File > Debugger**.
- Window titles are now "MochaNES" and "MochaNES Debugger", with the running ROM
  shown in the title bar.

## [0.1.1] - 2026-08-12

### Fixed
- Display scaling (1x-4x) had no effect in the browser build.
- `Load nestest` and `?rom=` links failed while `Choose ROM` worked. Both bugs
  came from identifiers inside the browser glue scripts shadowing
  compiler-renamed parameters.
- Debugger refreshed once a second, too slow to follow a program counter, and
  its memory view could not scroll.

### Changed
- Dropped the description "cycle-accurate". Per-instruction cycle counts are
  exact and verified, but sub-cycle timing is not modelled.

### Added
- Smoke test that runs the compiled web build in CI before publishing.

## [0.1.0] - 2026-08-12

First release. Desktop emulator, browser build, and a CRT simulation.

- 6502 with all official and unofficial opcodes, exact per-instruction timing
- Bus-driven clock: the PPU and APU advance during an instruction
- Mappers 0, 1, 2, 3, 4 and 7
- 31 of 51 blargg accuracy suites passing
- Browser build compiled with TeaVM, bit-identical to the JVM
