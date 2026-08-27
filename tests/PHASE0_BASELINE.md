# Phase 0 - Behavior Baseline

This test pack is intentionally non-invasive: production source files are not changed.
Its purpose is to freeze the behaviors that already exist before P0 reliability work begins.

> Phase 0.1 note: the baseline was originally created without modifying production source.
> `utils/logger.py` now contains the first deliberately approved production safety fix so that a
> fresh checkout can create its log directory on first write. See `PHASE0_1_LOGGER_FIX.md`.

Covered current behaviors:

- Check Point interface parsing, route parsing, and interface-reference warning behavior.
- VSX shell-noise cleanup, interface parsing, and route parsing.
- Panorama interface parsing, route parsing, /32 host-route filtering, VR/VSYS/zone fields.
- Merge compatibility across CP, VSX, Panorama, including legacy `routing` and `vr_data` fields.
- Existing UI contract: vendor filter, global search, subnet search, interface/route search,
  sorting, VSX logical deduplication function, legacy Panorama data handling, tabs, and dark UI structure.
- Logger initialization behavior.

Known correctness gaps are recorded as strict expected failures (`xfail`) rather than silently
changing production behavior in this phase.  When a defect is deliberately fixed later, the
corresponding xfail must be converted into a passing regression test in the same change.

Important limitation:

These fixtures are structural characterization samples derived from the current parser contracts.
They are not a substitute for masked real device outputs.  Golden tests using real CP, VSX, and
Panorama samples should be added before changing vendor parser semantics.
