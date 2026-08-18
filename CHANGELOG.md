# Changelog

Notable changes per release. Dates are release dates.

## [0.3.1] - 2026-08-18

### Fixed
- Only the top row of the picture drew in the browser. Adding the crop origin
  gave the frame upload a third parameter, which the compiler renames to a short
  name, and its inner loop declared a local of that same name - in JavaScript the
  same binding. The first row used the right offset; after that the offset held a
  pixel colour and every later row read from beyond the frame. This is the
  shadowing trap the platform scripts warn about, and why identifiers in them are
  q-prefixed.
- The smoke test now checks the cropped picture equals the uncropped one offset
  by the crop. It had passed on the broken build: it loads nestest, whose screen
  is black at the edges, so the clobbered offset happened to equal the correct
  one when overscan was off.

## [0.3.0] - 2026-08-18

### Added
- Colour emphasis and greyscale. `$2001` bits 5-7 and bit 0 were read for the
  rendering enables and nothing else, so a game tinting the screen for damage
  or lightning drew the same as normal. Emphasis attenuates the channels it
  does not name, so setting all three dims the picture rather than cancelling.
- **Overscan**, cropping the edges as a television's bezel did. The NES picks a
  palette per 16x16 block but scrolls a pixel at a time, so a column revealed by
  scrolling shows the previous block's colours until the attribute catches up.
  Games blank the leftmost 8 pixels to hide part of it; the block is 16 wide, so
  the rest still shows. Choosing a CRT preset turns it on.
- **CRT detail**, a budget for how many pixels the filter renders before being
  scaled up. Fullscreen on a 1440p screen cost 28ms a frame; the balanced
  default holds around 98fps at any screen size.
- **Save states** on Ctrl+S and Ctrl+L, or to a chosen file, so a moment can be
  kept and shared. The emulator thread stops while one is written.
- Sprite hit and overflow ROMs joined the suite - 13 of them. They report at
  zero page `$F8` rather than through `$6000`, which is why they had never been
  readable. All 11 sprite 0 hit tests pass, including the timing ones.
- Background tests that compute the expected picture independently, covering the
  nametable seam, vertical scrolling, and scroll and CHR bank changes made
  part-way down a frame and part-way along a scanline.
- `RomBuilder` can emit a mapper and real CHR-ROM, so bank switching is testable.

### Fixed
- Sprite pattern bytes are read in each sprite's own slot of the fetch phase
  rather than all eight at cycle 257, so an MMC3 game switching CHR banks
  part-way through that window has the later sprites come from the new bank.
- Rebinding a key could not capture the arrows, Tab, Space or Enter - every one
  of the default bindings - because a focused button consumes them.
- The browser CRT shader used a hardcoded 256x240 source size for its scanline
  pitch and filter taps.

### Changed
- The CRT filter is about a quarter faster: 10.6ms to 8.1ms at the balanced
  budget. Bloom alone had been a third of it.
- Tilt is gone from the CRT. Curvature is the useful control and now has fine
  steps on F6 and F7.

## [0.2.1] - 2026-08-13

### Fixed
- Stepping a single frame in the browser drew nothing. 0.2.0 moved the build to
  one bulk upload per frame, and the frame stepper filled the buffer without
  handing it over, so the debugger's step button and the smoke test saw a blank
  picture. Normal play was unaffected: the main loop uploads after catching up.

## [0.2.0] - 2026-08-13

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
- Gamepad buttons are rebindable in both builds, sharing one binding format.
  Defaults keep the d-pad and the analog stick working together, and a binding
  can list alternatives so a pad works whichever way it reports its d-pad.
- Scroll invariants for the right-hand edge and for crossing a nametable
  boundary. The existing one compared x against x+1 and so never saw column 255,
  and horizontal mirroring shows the same page twice, hiding the crossing.
- `ppu_vbl_nmi` 01-04 and 09 join the accuracy suite.

### Fixed
- Fullscreen barely filled the screen: inline sizing beat the stylesheet, so the
  canvas stayed at its windowed width.
- The shadow mask blurred in fullscreen. It is drawn in drawing-buffer pixels,
  and the buffer was capped below the displayed size, so the browser rescaled it.
- Choppy motion. The browser loop ran one frame per animation frame, tying
  console speed to the refresh rate; it now accumulates real time, and locks
  one-to-one only where the display is within 1% of 60.0988Hz. Without that
  lock the 0.027ms difference against a 60Hz screen costs a whole frame every
  ten seconds. The desktop had no frame clock at all - spacing was a side
  effect of blocking audio writes, and vanished entirely on a machine with no
  sound device - and now keeps its own deadline.
- The accuracy harness polled results through `Memory.read()`, which is a bus
  cycle, injecting phantom cycles into every frame of every run. Reading with
  `peek()` instead makes `ppu_vbl_nmi` 01-04/09 and `mmc3` 4-scanline_timing
  sub-tests 2-8 pass, with no change to the emulator.
- `BRK` and `IRQ` decided the NMI hijack from the PPU's NMI level, which stays
  high for all of VBlank, rather than from the latched edge, so any interrupt
  taken during VBlank vectored through $FFFA and swallowed the real NMI.
- Gamepad buttons stuck down for good in the browser: the pad only ever
  asserted a button and nothing released it.
- Held buttons now clear when the page loses focus, instead of staying down
  because the keyup went to another window.
- Rebinding a key on the desktop could not capture the arrows, Tab, Space or
  Enter - every default binding - because a focused button consumes them.
- Only some display settings survived a reload. Aspect, scale, volume,
  saturation and brightness now persist along with the CRT dials.

### Changed
- The browser build sends a finished frame to JavaScript in one call rather than
  61,440; the generated code kept the per-pixel write as a real function.
- The PPU keeps the palette resolved through its mirroring, instead of walking
  the VRAM address decode once per visible pixel.
- The GL texture is allocated once and updated in place rather than reallocated
  every frame.
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
