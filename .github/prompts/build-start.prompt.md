---
description: "Start a SecurityExpert build with authoritative scope and reasoning routing"
---

Read AGENTS.md, CURRENT_STATE.md, .github/copilot-instructions.md and only the
current project metadata required for this task.

Do not change code yet.

Produce `SESSION START` using the repository template. Resolve the current
product/engineering baseline, classify the requested work into one movement
type, define in/out scope and Definition of Done, identify the minimum source/
tests to inspect, state risks/invariants and context intentionally not loaded,
recommend the appropriate reasoning level, recommend the Git lane
(`feature/*`, `build/*`, or direct `main` hotfix), define merge-to-main gates,
state deployment direction (`local validation only`, `staging-like`, or
`production-gated`) with required evidence, and commit to provide exact
non-interactive Git dispatch commands in `SESSION CLOSE`.

Do not read historical PHASE docs, the Continuation Pack, data/output/logs or
runtime artifacts unless the task proves they are necessary.

If this `SESSION START` crosses a session/tool boundary (arrives as, or must
be answered as, a `NEXUS_SESSION_PACKET`), transport it as exactly one
sentinel-wrapped protocol-version-2 packet per
`docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md` — the packet's `report`
object carries the complete content above, field by field; no narrative
outside the sentinel pair.
