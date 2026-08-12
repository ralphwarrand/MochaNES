# Third-party resources

These files are **not** part of MochaNES and are **not** covered by the
project's GPL-3.0 licence. They are included here because the test suite and
the default launch path depend on them.

## `nestest.nes`

A 6502 CPU test ROM written by **kevtris**. It is homebrew — it contains no
Nintendo code or assets, so it is not a commercial game dump.

It carries no explicit licence. It has been distributed with emulator projects
and on the NESdev forums for roughly two decades, and shipping it alongside an
emulator is long-standing common practice, but that is convention rather than
a granted right. If you would rather not rely on that, see *Removing them*
below.

Documentation: <https://www.qmtpro.com/~nes/misc/nestest.txt>

## `nestest.log.txt`

A cycle-by-cycle execution trace of the above ROM, produced by **Nintendulator**
and conventionally distributed with it. `CPUExecutionTest` diffs the emulator's
own trace against this line by line, which is the strongest single check on CPU
correctness in the suite — it catches wrong flags, wrong addressing and wrong
cycle counts in one pass.

Same licensing position as the ROM: no explicit grant, widely redistributed.

## What depends on them

* `CPUExecutionTest` — diffs against the reference log.
* `SaveStateTest` — loads the ROM, with a synthetic fallback if it is absent.
* `run.sh` with no arguments — boots `nestest.nes` as the default.

A duplicate pair lives under `nes-emulator/src/test/resources/` so the tests can
load them from the classpath.

## Removing them

If you would prefer not to redistribute these, delete both pairs. `run.sh` will
then need a ROM argument, and `CPUExecutionTest` will need the files supplied
locally or the test skipped. Nothing in the emulator itself depends on them.

## Note on game ROMs

Commercial game ROMs are a different matter entirely: those *are* copyrighted,
and none are included here. `roms/` is gitignored for that reason.
