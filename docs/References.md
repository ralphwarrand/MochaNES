# References

The documentation this emulator was built from. Every link here was used to
resolve something specific, and all of them were checked as reachable.

---

## Start here

* **[NESdev Wiki](https://www.nesdev.org/wiki/Nesdev_Wiki)** - the reference for
  NES hardware behaviour. Effectively the specification; where this emulator and
  the wiki disagree, the emulator is wrong.
* **[NES reference guide](https://www.nesdev.org/wiki/NES_reference_guide)** -
  the wiki's own index, organised by subsystem.
* **[NESdev forums](https://forums.nesdev.org/)** - where the hard edge cases
  get argued out. Often the only source for behaviour too obscure to be written
  up, usually with a test ROM attached.
* **[Programming guide](https://www.nesdev.org/wiki/Programming_guide)** - the
  hardware from the game's side, which is useful context for why a register
  behaves as it does.

## CPU - 6502 / Ricoh 2A03

* [6502 instruction set](https://www.masswerk.at/6502/6502_instruction_set.html)
 - opcodes, addressing modes and flag effects.
* [Unofficial opcodes](https://www.nesdev.org/wiki/CPU_unofficial_opcodes) -
  the undocumented instructions, including the unstable ones (ANE, LXA, SHA/SHX/SHY)
  whose behaviour depends on analog effects.
* [Cycle reference chart](https://www.nesdev.org/wiki/Cycle_reference_chart) and
  [cycle counting](https://www.nesdev.org/wiki/Cycle_counting) - per-instruction
  timing, page-crossing and branch penalties.
* [CPU interrupts](https://www.nesdev.org/wiki/CPU_interrupts) - the interrupt
  poll happening before the last cycle, the `CLI`/`SEI`/`PLP` latency, and NMI
  hijacking of BRK.
* [DMA](https://www.nesdev.org/wiki/DMA) - OAM DMA's 513/514 cycles and the DMC
  fetch stealing cycles from the CPU.
* [Visual6502](http://visual6502.org/) - a transistor-level simulation of the
  real die. The last resort when documentation runs out and you need to know
  what the silicon actually does.

## PPU - 2C02

* [PPU registers](https://www.nesdev.org/wiki/PPU_registers) - `$2000`-`$2007`,
  including the `$2007` read buffer.
* [PPU rendering](https://www.nesdev.org/wiki/PPU_rendering) - the dot-by-dot
  background fetch pipeline.
* [PPU scrolling](https://www.nesdev.org/wiki/PPU_scrolling) - Loopy's `v`/`t`/
  `x`/`w` registers, and the source of mid-frame split-screen effects.
* [PPU sprite evaluation](https://www.nesdev.org/wiki/PPU_sprite_evaluation) -
  per-scanline evaluation into secondary OAM, the 8-sprite limit and the
  overflow flag's hardware bug.
* [PPU OAM](https://www.nesdev.org/wiki/PPU_OAM) and
  [frame timing](https://www.nesdev.org/wiki/PPU_frame_timing) - the VBlank
  window and the odd-frame skipped dot.
* [Open bus behavior](https://www.nesdev.org/wiki/Open_bus_behavior) - the
  decaying latch behind `$2002`'s unused bits.
* [PPU palettes](https://www.nesdev.org/wiki/PPU_palettes) - palette memory,
  its mirroring, and NES colour generation.
* [Mirroring](https://www.nesdev.org/wiki/Mirroring) - nametable arrangements.

## APU

* [APU](https://www.nesdev.org/wiki/APU) - channels, the frame counter and its
  two sequencer modes.
* [APU Mixer](https://www.nesdev.org/wiki/APU_Mixer) - the non-linear mixing
  formula, and the output filter chain (90 Hz and 440 Hz high-pass, 14 kHz
  low-pass) that this emulator implements.
* [APU DMC](https://www.nesdev.org/wiki/APU_DMC) - delta modulation, sample
  fetching and its DMA cost.

## Mappers

* [Mapper list](https://www.nesdev.org/wiki/Mapper) and
  [iNES format](https://www.nesdev.org/wiki/INES) - the header this emulator
  parses to pick a mapper.
* [NROM (0)](https://www.nesdev.org/wiki/NROM) ·
  [MMC1 (1)](https://www.nesdev.org/wiki/MMC1) ·
  [UxROM (2)](https://www.nesdev.org/wiki/UxROM) ·
  [CNROM (3)](https://www.nesdev.org/wiki/CNROM) ·
  [MMC3 (4)](https://www.nesdev.org/wiki/MMC3) ·
  [AxROM (7)](https://www.nesdev.org/wiki/AxROM)

MMC3's page covers the A12-driven scanline counter, including the rev A / rev B
difference that makes `5-MMC3` and `6-MMC3_alt` mutually exclusive.

## Test ROMs

* [Emulator tests](https://www.nesdev.org/wiki/Emulator_tests) - the catalogue of
  test ROMs and what each one checks.
* [nes-test-roms](https://github.com/christopherpow/nes-test-roms) - blargg's
  suites, the ones this project measures itself against. See the
  [Accuracy Report](Accuracy.md).
* [nestest documentation](https://www.qmtpro.com/~nes/misc/nestest.txt) -
  kevtris's CPU test ROM and the notes for it. Its accompanying execution log is
  a golden reference: run the ROM and diff your trace line by line.

## Reference emulators

Useful for diffing behaviour when a test failure is ambiguous.

* **[Mesen 2](https://github.com/SourMesen/Mesen2)** - the most accurate
  open-source NES emulator, with a debugger and a Lua scripting window. The
  practical technique: log every `$2002` read with its cycle count in both
  emulators and diff, since the first divergence pins the bug.
* **[FCEUX](https://fceux.com/)** - mature, scriptable via Lua, and its
  `--newppu` core is accurate enough to be worth diffing against.

## Video and the CRT simulation

* [NTSC video](https://www.nesdev.org/wiki/NTSC_video) - how the PPU's output
  becomes a composite signal, and why colour and luma have different bandwidth.
* [nes_ntsc](http://slack.net/~ant/libs/ntsc.html) - blargg's NTSC filter, the
  standard treatment of composite artefacts in emulators.

## Hardware archaeology

* [Visual circuit tutorial](https://www.nesdev.org/wiki/Visual_circuit_tutorial)
 - reading die shots and simulations, for when behaviour is undocumented
  because nobody has written it down yet.
