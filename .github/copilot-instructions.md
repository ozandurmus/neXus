# SecurityExpert — GitHub Copilot Operating Instructions

Copilot-specific delta only. The canonical constitution is `/AGENTS.md`; the
cold-start entry point, reading order, and SESSION START/CLOSE/reasoning-tier
schemas are in `/AI_START_HERE.md`; the network-device command gate, approval
boundaries and render-harness mechanics are in
`/docs/AI_DEVELOPMENT_PROTOCOL.md`. This file does not restate them — follow
those documents, and read this one only for what's genuinely Copilot-specific
below. Path-scoped deltas live in `.github/instructions/*.instructions.md`.

You are working on an existing, validated network-security product. Do not
behave as if this is a greenfield repository.

## Mandatory context bootstrap

Follow `/AI_START_HERE.md`'s reading order exactly; do not improvise a
shorter or reordered version of it.

## Editing behavior

`/AGENTS.md` "Engineering laws" has the pre-edit checklist (locate
implementation → inspect tests → state minimal change → name contracts that
must not break) and the anti-unrelated-cleanup rule. Copilot-specific:
**Agent mode** may edit/run local tests once scope is approved. Network
collection, destructive operations, dependency changes, storage migrations
and Git push/merge require the approval rules in
`/docs/AI_DEVELOPMENT_PROTOCOL.md`.

## Local toolchain ergonomics (workspace preference)

- If Git's executable path is already known/validated in this workspace, do
  not repeatedly ask whether Git exists; proceed directly with that path.
- The Python runtime is already validated for this workspace (standing
  workspace fact — `/AGENTS.md` "Context/token discipline"). Never invoke
  `configure_python_environment`, interpreter selection, venv creation, or
  any environment-bootstrap UI. Run the existing `py` command directly.
- If the existing `py` command fails, report that exact failure and leave
  tests pending. Do not configure or replace the environment unless
  explicitly requested in the same chat.
- Prefer minimizing repetitive setup questions in continuing sessions; only
  re-check when tool execution actually fails.

## Reasoning/model routing — Copilot tier names

`/AI_START_HERE.md` has the neutral tier table this maps onto. For the
currently available Copilot model set: **Sol** (or equivalent normal-strong
model) for the "Normal (strong)" tier; **Terra High** (or equivalent
strongest approved reasoning mode) for the "High" tier. Auto is allowed for
low-risk work; explicit routing is preferred for major builds.

## Context discipline

Keep context lean — prefer narrow search and symbol reads over scanning
history or runtime output. Start a new chat (per `/AGENTS.md` "Context/token
discipline") rather than an in-tool compaction command when the session
needs to shed unrelated context; ensure durable decisions are written to the
repository first either way.

## Privacy / DLP

Follow `/PRIVACY_AND_DATA_HANDLING.md` and
`.github/instructions/privacy.instructions.md`. Do not echo sensitive
values — report file + location + classification, never the matched value
(`/AGENTS.md` "Sensitive identity reporting law"). Keep repository-wide DLP
guard tests passing. Do not weaken secret detection, evidence redaction, or
vendor-native semantics to make a prompt pass inspection.
