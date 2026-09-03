---
applyTo: "CURRENT_STATE.md,project/**,**/DEV*.md,**/PHASE*.md"
---

# Project State & Handover Contract

Path-scoped delta only. `AGENTS.md` "Project-state update rule" is
canonical (which files a state-changing build must update, the
append/rebase-not-rewrite rule, the render-harness gate) — do not restate
it here.

Project metadata is durable product memory, not a narrative scratchpad:
distinguish automated validation from real-environment validation, and
never place real estate identities, management addresses, credentials,
secrets or raw configuration in project metadata. At build close, ensure
the exact next build/task and unresolved blockers are recoverable by a new
chat from repository state alone.
