---
description: "Implement an approved SecurityExpert build scope"
---

Work in `IMPLEMENTATION` movement using the active approved scope/contract.

Before editing, state the minimal change set and invariants. Implement coherent
changes without unrelated cleanup. Run targeted tests first and expand
regression according to blast radius. Do not run network collection unless it
was explicitly approved for this task.

If implementation reveals materially larger coupling or a new architecture
choice, stop and return to ARCHITECTURE instead of silently expanding scope.

Do not mark network-facing behavior DONE without required human real-environment
evidence.
