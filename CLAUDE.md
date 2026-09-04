# CLAUDE.md — SecurityExpert

Claude-specific delta only. The canonical constitution is `AGENTS.md`; the
cold-start entry point, reading order, and SESSION START/CLOSE/reasoning-tier
schemas are in `AI_START_HERE.md`; the network-device command gate, approval
boundaries and render-harness mechanics are detailed in
`docs/AI_DEVELOPMENT_PROTOCOL.md`. This file does not restate them.

## Claude delta

- **Working language: English** — `AGENTS.md` "Engineering-output language
  law" is the single owner of the rule; it is not restated here.
- **Reasoning routing.** Use extended thinking for new architecture, storage/CAS,
  security boundaries, vendor-semantic ambiguity, deployment/server/container
  work, cross-subsystem root cause, and phase closure. Use normal reasoning for
  deterministic implementation, tests, documentation and validation. Do not
  spend high reasoning on mechanical work.
- **Model + reasoning recommendation to the user, every checkpoint** (per
  `AGENTS.md` "AI reasoning / movement routing"). Concrete tiers for this repo:
  `Sonnet 5, normal` for source audit, deterministic implementation against a
  frozen contract, tests, docs and validation; `Sonnet 5, extended thinking
  (high)` for a design/contract on new scope, a security/privacy boundary, a
  vendor-semantic call, or phase closure; `Opus / Fast` only when a genuinely
  cross-subsystem architecture decision is on the table. State the recommendation
  in plain terms, name the lightest tier that fits, and say when a pre-selected
  tier (e.g. "Sonnet high") is more than the step needs.
- **Test economy.** One-shot, file-backed runs:
  `py -m pytest -q > pytest_result.log 2>&1`. Do not re-run the full suite while
  the last evidence still holds.
- **Toolchain.** The workspace Python / PowerShell / Git setup is already
  validated. Use the existing `py` command directly; never invoke environment
  bootstrap or interpreter selection. On a real command failure, report it and
  stop.

Everything else — context order, movement types, build lifecycle, privacy /
DLP, evidence/identity laws, the `SESSION START` / `SESSION CLOSE` schemas
and reasoning-tier table, the network-device command gate, and approval
boundaries — is in `AGENTS.md`, `AI_START_HERE.md`, and
`docs/AI_DEVELOPMENT_PROTOCOL.md`.
