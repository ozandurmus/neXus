---
description: "Create a compact handover before changing Copilot chats/models"
---

Do not restate the full project history.

Ensure all durable decisions from the current session are already represented
in CURRENT_STATE.md, project metadata or the current build/design document.
If not, identify what must be persisted before handover.

Then output a compact handover containing: current build/status, last verified
evidence, unresolved issue, exact next action, files/components likely needed,
movement type, recommended reasoning level, recommended branch/PR target,
explicit main-merge decision (approved/blocked), exact non-interactive Git
dispatch commands for the recommended path, explicit `main.py/UI effect`
expectation for the next operator run, and whether the next chat needs any
historical context beyond the normal repository bootstrap.
