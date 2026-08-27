---
applyTo: "CURRENT_STATE.md,project/**,**/DEV*.md,**/PHASE*.md"
---

# Project State & Handover Contract

Project metadata is durable product memory, not a narrative scratchpad.

When a build changes state, scope, sequencing, debt or acceptance evidence:

- update `CURRENT_STATE.md` with only the current authoritative checkpoint,
- update roadmap/backlog/feature/build metadata as applicable,
- preserve historical outcomes rather than silently rewriting them,
- distinguish automated validation from real-environment validation,
- never place real estate identities, management addresses, credentials,
  secrets or raw configuration in project metadata.

At build close, ensure the exact next build/task and unresolved blockers are
recoverable by a new chat from repository state alone.
