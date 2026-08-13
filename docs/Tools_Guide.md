# MochaNES Tools Guide

Running the emulator, and the tools built into it.

---

## 1. Running

```bash
./run.sh                      # nestest (the default)
./run.sh smb                  # bare name, found under roms/
./run.sh roms/MMC3/kirby.nes  # or an explicit path
```

A bare name is searched for case-insensitively under `roms/` and `resources/`,
so you do not need to know which mapper folder a game lives in. This also
reaches the accuracy ROMs (`./run.sh cpu_timing_test`).

| Option | Effect |
|---|---|
| `-l`, `--list` | List available ROMs (games listed; test ROMs summarised by count) |
| `-b`, `--build` | Force a rebuild first |
| `-t`, `--test` | Run the test suite instead of launching |
| `-c`, `--crt [preset]` | Start with the CRT simulation on |
| `-h`, `--help` | Full help |

Two gotchas:

* **Several test ROMs share a basename** - there are three different
  `1-clocking.nes`. Bare-name lookup takes the first match, so pass the full
  path when it matters.
* **`--crt` optionally takes a preset**, so it checks whether the next word is
  `trinitron`, `consumer`, `arcade` or `monochrome`. A ROM named the same would
  be read as the preset; pass its path to disambiguate.

If a game shows a blank screen, check the `Detected Mapper: N` line printed at
load. Anything outside {0, 1, 2, 3, 4, 7} is unimplemented - see the
[Architecture Reference](Architecture.md#6-the-mapper-subsystem-memoryjava).

---

## 2. Controls

Defaults, all rebindable via **Input → Configure Buttons**:

| NES | Key |
|---|---|
| D-Pad | Arrow keys |
| A / B | `Z` / `X` |
| Select | `Shift` |
| Start | `Enter` |

Binding a key already in use releases it from its previous button, so you cannot
end up with two buttons fighting over one key.

**Gamepads** work on Linux with no extra setup - the emulator reads
`/dev/input/js*` directly. Face buttons map to A/B, centre buttons to
Select/Start, and both the left stick and the d-pad hat to directions. Keyboard
and pad work at the same time. A pad plugged in *after* launch is not detected
automatically; toggle **Input → Gamepad** off and on.

Settings persist to `~/.config/mochanes/settings.properties`.

### Window and video

| Key | Action |
|---|---|
| `Alt+Enter` | Fullscreen (`Esc` to exit) |
| `F1` | Toggle CRT simulation |
| `F2` / `F3` | Cycle preset / shadow mask |
| `F4` / `F5` | Curvature up / down |
| `F6` / `F7` | Screen tilt |
| `F8` / `F9` | Mask strength |
| `F10` / `F11` | Bloom |
| `F12` | Focus sharper (`Shift+F12` softer) |

**Video → Window Size** offers 1x-6x, and **Aspect Ratio** offers pixel-perfect
(integer multiples only, letterboxed), 4:3 (as on a TV - the NES has non-square
pixels) or stretch.

**Help → Controls** lists all of this in-app.

---

## 3. The CRT Simulation

Off by default; `F1` or `--crt` enables it. It models the parts of a tube that
produce the look rather than overlaying a scanline texture:

* **Electron beam spot** - a gaussian whose width grows with drive level. The
  scanlines are the gaps the beam does not reach, which is why bright lines
  bloom wide enough to close their own gap while dark ones stay thin.
* **Phosphor persistence**, per channel, so motion trails slightly.
* **NTSC bandwidth** - filtered in YIQ, so colour smears horizontally while
  luma edges stay sharp. Real composite behaviour, not a blur.
* **Halation** - light scattering in the glass, driven by the bright parts only.
* **Aperture grille / shadow mask / slot mask**, applied in screen space so the
  fine structure stays crisp instead of aliasing.

Compositing happens in linear light, with sRGB conversion only at the
boundaries; that is what stops the bloom and scanline falloff looking muddy.

Four presets (Trinitron, Consumer TV, Arcade, Green Phosphor). It costs roughly
9ms per frame at 1024x960, parallelised across rows.

**If fine detail is hard to read** - dark text on a bright panel is the worst
case - reach for `F12` (sharper focus) and `F10` (less bloom) first. Mask
strength does not affect overall brightness: the gain tracks the mask's mean
transmission, so the dials are independent.

---

## 4. The Debugger

Open it with **File > Debugger**. It stays closed otherwise, since it is a
developer tool rather than something to have beside the game every launch, and
it is built on first use. Reopening raises the existing window, so the tab and
scroll position survive.

It has tabs for **Controls**, **Disassembly**, **Memory** and **CHR-ROM**.

The memory view covers the full 64KB map:

| Range | Contents |
|---|---|
| `$0000-$07FF` | Internal RAM (2KB, mirrored to `$1FFF`) |
| `$2000-$2007` | PPU registers (mirrored every 8 bytes to `$3FFF`) |
| `$4000-$4017` | APU and I/O registers |
| `$6000-$7FFF` | PRG-RAM / save RAM |
| `$8000-$FFFF` | PRG-ROM |

> **Note:** debugger and disassembler reads go through `Memory.peek()`, which
> does not consume a cycle or disturb bus state. Inspecting memory therefore
> cannot perturb the emulation - important, since the emulator's clock is driven
> by bus accesses.

---

## 5. Hooks

Two interfaces for instrumenting a running system, used by the test harnesses:

```java
memory.addHook(new MemoryHook() {
    public void onRead(int address, int value)  { ... }
    public void onWrite(int address, int value) { ... }
});

cpu.addHook((pc, opcode, op1, op2) -> { ... });
```

`MemoryHook` is how you trace register access - logging every `$2002` read with
its cycle count is what pins down timing divergences against another emulator.
`ExecutionHook` fires per instruction, which makes it a cheap PC profiler for
finding a stalled wait-loop.

Both are observational; neither affects timing.

---

## 6. Replay

```bash
./run.sh --replay <log> [rom]
```

Replays a recorded input log against a ROM, which makes a run reproducible.
Because the emulator is deterministic given the same ROM and inputs, a replay
that diverges indicates a real behavioural change.

---

## 7. The browser version

```bash
./serve.sh                              # build and serve on localhost:8000
./serve.sh --test                       # run the smoke test after building
./serve.sh --no-build                   # serve what is already built
./serve.sh --port 9000 --open           # different port, open a browser
./serve.sh --rom roms/MMC3/kirby.nes    # load a ROM on startup
```

The address it prints for your phone works for anything on the same wifi, which
is how to try the on-screen controls. `?rom=<name>` loads any ROM sitting beside
the page, so a particular game can be linked directly.

After a rebuild, reload with `Ctrl-Shift-R`. The compiled script is cached hard,
and an ordinary reload will quietly show you the previous build.

The browser version carries the same features as the desktop one: CRT presets
and dials, aspect and scale options, fullscreen, rebindable keys, gamepads, save
states and a debugger. Settings are kept in the browser and survive a reload.

**Touch devices** get an on-screen pad automatically, below the screen. It is
revealed when the browser reports touch support, so a laptop with a touchscreen
gets it too. Sliding off a button releases it rather than leaving a direction
held.

**Fullscreen** fits whichever dimension runs out first, honouring the aspect
setting. The CRT renders one buffer pixel per screen pixel so the shadow mask
stays crisp; on a large display that is the most expensive thing on screen, so
turn the CRT off if fullscreen struggles.

---

## 8. Further reading

* [Architecture Reference](Architecture.md) - how the emulator is put together.
* [Accuracy Report](Accuracy.md) - what passes, what does not, and why.
* [References](References.md) - the NESdev hardware documentation.
