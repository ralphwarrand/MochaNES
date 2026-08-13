# Roadmap

An honest assessment of where this project falls short of a high standard, and
what to do about it, in the order worth doing it.

Argue with any of it. If something here is wrong, that is worth a PR too.

---

## 1. What is weak

These are the things that would embarrass the project under
scrutiny.

### 1.1 CI protected almost nothing (now partly fixed)

Until recently, **37 of 42 tests skipped on CI**. The accuracy ROMs cannot be
redistributed, so the PPU, APU and every mapper had no automated cover at all. A
contributor could break sprite evaluation, take every game down with it, and
watch the badge stay green.

`RenderRegressionTest` closes the worst of that with ROMs assembled in code. It
catches gross rendering regressions - verified by mutation: removing the
8-sprite limit fails it. But it is a floor, not a ceiling. Still uncovered:

- mappers 1, 2, 3, 4 and 7 (the synthetic ROMs are NROM)
- MMC3 scanline IRQ
- APU channel behaviour beyond "samples are produced"
- the desktop front-end and the CRT filter

### 1.2 The accuracy ceiling is one problem, not many

`ppu_vbl_nmi` (1/10), `cpu_interrupts_v2` (1/5), `mmc3 4-scanline_timing` and
the Kirby scroll tear are **all the same root cause**: the PPU advances in whole
three-dot groups per CPU cycle, and interrupts are serviced only at instruction
boundaries. Nothing can observe the machine *between* dots.

This is worth stating clearly because it changes the economics. It looks like
fourteen separate bugs and it is one restructure.

### 1.3 The core is not shaped for contribution

- `CPU.java` is 2,071 lines, largely one dispatch switch. Correct, but forbidding
  to a newcomer and awkward to review.
- Mutable state is public in places (`CPU.PC`, `APU.irqActive`), so there is no
  boundary a refactor can rely on.
- No formatter or linter, so PRs will churn on whitespace.

### 1.4 Portability is claimed but not delivered

The core compiles to JavaScript and has no platform dependencies - a genuinely
unusual property. Yet it is not published anywhere, so nobody can depend on it
without vendoring the source. The most valuable thing about the architecture is
currently unusable by anyone else.

---

## 2. What to do, in order

### Tier 1 - foundations for outside contribution

*Cheap, unblocks everything else, and should land before inviting PRs.*

| | Work | Why |
|---|---|---|
| 1.1 | Golden-frame tests (**done**) | CI protects the PPU at all |
| 1.2 | `CONTRIBUTING`, issue/PR templates, `.editorconfig` (**done**) | A PR can be reviewed against something |
| 1.3 | Mapper coverage in `RenderRegressionTest` | Bank switching is entirely untested on CI |
| 1.4 | CI matrix on JDK 17 and 21 | Currently only 17 is proven |
| 1.5 | Coverage reporting | Nobody knows what is untested; guessing is how 1.1 happened |

### Tier 2 - the accuracy restructure

**This is the single highest-value change in the project.** One piece of work
moves ~14 test failures and the Kirby artifact together.

Step the PPU dot by dot, with the CPU able to observe between dots. Concretely:

1. Make `PPU.tick()` a single-dot step (it already is internally - the caller
   loops three times).
2. Move interrupt polling from instruction boundaries to cycle boundaries, so
   `$2002` reads and NMI assertion land on the right dot.
3. Re-measure against `02-vbl_set_time` with the Mesen trace already captured.

Risk: it will move hashes in `RenderRegressionTest` and may regress currently
passing suites before it improves them. Do it on a branch with the full accuracy
suite run locally at each step, and report per-suite deltas.

Expected: `ppu_vbl_nmi` 1/10 → most, `cpu_interrupts_v2` 1/5 → most,
`mmc3 4-scanline_timing`, and the Kirby tear. Roughly **31/51 → 45/51**.

### Tier 3 - performance

Current: ~8-9x realtime desktop, 2.6x realtime in the browser.

| Work | Expected |
|---|---|
| TeaVM **WASM** target instead of JavaScript | Likely 1.5-2x the browser build; measure before committing |
| Desktop CRT filter to GPU | The shader already exists for the web; the desktop pays ~9ms a frame on CPU for the same effect |
| Profile the PPU inner loop | Sprite evaluation gave 4x once; unclear if more is there without measuring |

Do not optimise the CPU dispatch without a profile first. An earlier attempt at
removing divisions gained ~3% because the JIT was already handling it.

### Tier 4 - reach

Where the Java-everywhere property finally pays off.

- **Publish `mochanes-core` to Maven Central.** No dependencies, no platform
  ties, deterministic, fast-cloning. It is a good library for anyone
  building an emulator front-end, a research harness or a bot. This is the
  highest-use item here and mostly packaging work.
- **Rewind.** Cloning costs ~1µs. A ring of states every few frames gives
  console-grade rewind almost free - the hard part is already built.
- **Netplay.** The core is deterministic and bit-identical across JVM and
  browser, which is the difficult prerequisite for lockstep netplay. WebRTC
  between two browser instances is then mostly plumbing.
- **TAS / movie support.** Deterministic replay already exists; frame-perfect
  recording with a documented format makes the emulator useful to a community
  that cares deeply about accuracy - and they file excellent bug reports.
- **Android front-end.** `FrameSink` and `AudioSink` are already the entire
  platform boundary.
- **More mappers.** MMC5 (5), MMC2 (9), MMC4 (10), Sunsoft FME-7 (69). Each is
  self-contained and a good first contribution.

---

## 3. Sequencing

If only one thing gets done: **Tier 2**. It is what stands between "a good
hobby emulator" and "an accurate one", and it resolves the only user-visible
glitch anyone has reported.

If the goal is outside contributors: **finish Tier 1 first**. Tier 2 is a large,
invasive change, and merging it without mapper coverage on CI means finding out
what it broke from bug reports rather than tests.

The recommended order is Tier 1.3-1.5, then Tier 2, then Maven Central from
Tier 4 - which costs little and makes everything else in Tier 4 possible for
other people rather than only for this repository.
