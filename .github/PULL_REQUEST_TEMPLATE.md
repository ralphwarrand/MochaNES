## What this changes

<!-- One or two sentences. -->

## Why

<!-- The behaviour that was wrong, or the capability that was missing. Cite
     hardware documentation if this implements hardware behaviour. -->

## Evidence

<!-- What you measured, not what you expect. For emulation changes, give the
     test-suite deltas in BOTH directions - a change that fixes one suite and
     regresses another is useful information, not a failure. -->

- [ ] `mvn clean verify` passes
- [ ] Ran the accuracy suite locally with the test ROMs present (required for
      any CPU/PPU/APU/mapper change - CI cannot run it)
- [ ] Browser build: `mvn -Pweb package` and the smoke test pass (if `nes-web`
      is touched)

If a golden hash in `RenderRegressionTest` changed, attach the before/after
frames (`-Dmochanes.dumpFrames=/tmp/frames`) and say why the picture should
differ.
