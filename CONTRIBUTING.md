# Contributing

Contributions are welcome - accuracy fixes, mappers, front-end work, or
documentation. This page covers what you need to know that the code does not
tell you.

## Getting set up

Requires **JDK 17+** and **Maven 3.8+**. Nothing else.

```bash
git clone https://github.com/ralphwarrand/MochaNES.git
cd MochaNES
mvn clean verify     # build and test
./run.sh             # boot nestest
```

The browser build is behind a profile, so an ordinary build never pays for it:

```bash
mvn -Pweb package                                   # output in nes-web/target/site
node nes-web/src/test/js/smoke.js nes-web/target/site
```

## What the tests actually cover

Worth understanding before you trust a green build.

| Suite | Needs ROMs? | Runs on CI |
|---|---|---|
| `RenderRegressionTest` | no - ROMs are assembled in code | yes |
| `CPUExecutionTest` | no - nestest is bundled | yes |
| `FastCloneTest`, `SaveStateTest`, `AddresserTest` | no | yes |
| `BlarggTestRomTest` (37 cases) | **yes** | **no - skips** |

The blargg accuracy ROMs cannot be redistributed, so they are gitignored and
their cases skip when absent. **A green CI badge does not mean the accuracy
suite ran.** If you are changing CPU, PPU, APU or mapper behaviour, get the test
ROMs locally and run the full suite before opening a PR - see the
[Accuracy Report](docs/Accuracy.md).

## Golden-hash tests

`RenderRegressionTest` renders small assembled ROMs and compares a hash of the
finished picture. If one fails, **that is the test doing its job**: something
about rendering changed.

To see what changed rather than guess:

```bash
mvn -pl nes-emulator test -Dtest=RenderRegressionTest -Dmochanes.dumpFrames=/tmp/frames
```

That writes the captured frames as PNGs. Look at them. If the change is
intended, update the constant and say in your PR why the picture should differ.
Updating a hash without looking at the frame defeats the entire test.

Each hash is paired with checks on the *shape* of the picture - lit-pixel
counts, colour counts, and that turning sprites off changes the output. Those
exist so a hash can never quietly lock in a blank or broken frame. Keep them.

## Two traps worth knowing

**The browser glue scripts.** Every identifier declared inside a `@JSBody`
script in `nes-web` is prefixed `q`. This is not style. TeaVM rewrites those
scripts and renames their parameters to short names like `b` and `c`, so an
ordinary local can silently shadow a parameter - no compile error, no warning.
That shipped two bugs in 0.1.0: display scaling did nothing, and fetched ROMs
failed to load while picked ones worked. Keep the prefix, and run the smoke test.

**Anything added to component state must be added to all four paths** -
`copy`, `fastCopyFrom`, `saveState`, `loadState` - or it will silently fail to
survive a clone, and save states will be subtly wrong in ways no test catches.

## Style

- Java 17, 4-space indent, 100-column soft limit. `.editorconfig` covers the
  mechanical parts.
- Comments should explain *why*, not restate the code. The codebase leans on
  this heavily, particularly where behaviour looks wrong but is faithful to
  hardware - the left-column clipping in Kirby, for instance.
- Cite hardware documentation when implementing hardware behaviour. The
  [References](docs/References.md) page collects the sources used so far.

## Pull requests

- One concern per PR. Accuracy fixes separate from refactors.
- Say what you measured. "Fixes sprite priority" is weaker than "`sprite_hit`
  goes 4/6 → 6/6, no change elsewhere".
- If you change emulation behaviour, state which test suites moved in either
  direction. A change that fixes one ROM and breaks two is useful information,
  not a failure - say so and we can work out the trade-off.
- New hardware behaviour needs a test. If the behaviour cannot be tested without
  a copyrighted ROM, add a case to `RenderRegressionTest` using `RomBuilder`
  instead; it can assemble whatever program you need.

## Good first areas

- **Mappers.** Only 0, 1, 2, 3, 4 and 7 are implemented. Each new one makes a
  swathe of games boot, and `Memory.java` shows the pattern.
- **Web front-end.** Touch controls, a ROM library in IndexedDB, WebRTC netplay
 - the core is already deterministic, which is the hard prerequisite.
- **Accuracy.** See [the roadmap](docs/Roadmap.md) for what is blocked on what.
  Most remaining failures share one root cause, so that work is worth
  coordinating rather than duplicating.
