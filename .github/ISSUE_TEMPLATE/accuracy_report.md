---
name: Accuracy report
about: A test ROM fails, or behaviour differs from hardware
labels: accuracy
---

**Test ROM, and its result**
The failure code and message from `$6000`, if the ROM reports one.

**What hardware does**
A link to the relevant NESdev page, or a trace from a reference emulator.

**Have you checked docs/Accuracy.md?**
Several failures are known and share one root cause - sub-instruction timing.
If this is one of those, say so; a confirmation is still useful.
