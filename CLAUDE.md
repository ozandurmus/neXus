# CLAUDE.md — SecurityExpert

Claude-specific delta only. The canonical constitution is `AGENTS.md`; the
cold-start entry point, reading order, and SESSION START/CLOSE/reasoning-tier
schemas are in `AI_START_HERE.md`; the network-device command gate, approval
boundaries and render-harness mechanics are detailed in
`docs/AI_DEVELOPMENT_PROTOCOL.md`. This file does not restate them.
Git authorization and agent execution semantics are owned by `AGENTS.md`
"Git authority and execution law"; do not infer a manual-click requirement.

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
- **Test economy.** One-shot, file-backed runs, parallel by default
  (`DEV.TEST.1`): `py -m pytest -q -n auto --dist worksteal >
  pytest_result.log 2>&1`. Do not re-run the full suite while the last
  evidence still holds.
- **Toolchain.** The workspace already has a validated interpreter for the
  environment it runs in (`py` on the Windows profile, the project `.venv`
  interpreter on the macOS profile); use that one directly and do not assume
  `py` exists in every shell. Never invoke environment bootstrap or
  interpreter selection without a real need. On a real command failure,
  report it and stop.
- **Session-boundary packets.** When a Product Owner message opens with a
  `SESSION_START` `NEXUS_SESSION_PACKET`, or a reply is expected to close
  with a `SESSION_CLOSE` one, use exactly one sentinel-wrapped
  protocol-version-2 packet per `docs/design/GOV_SESSION_TRANSFER_PROTOCOL.md`
  (FROZEN — PO APPROVED) — the packet's `report` object carries the complete
  `AI_START_HERE.md` SESSION START/CLOSE content; no heading, summary, or
  explanation belongs outside the sentinel pair in that case. Build it with
  `py scripts/gov_session_transfer.py render` and validate before emitting.
- **Product Owner assistant role.** `GOV.PO.1` (`docs/design/GOV_PO_ROLE_MIGRATION.md`,
  FROZEN): the PO assistant runs as the `nexus-po` skill in a separate
  interactive session (`claude --settings .claude/nexus-po.settings.json`,
  then `/nexus-po <PLAN|REVIEW|DECIDE|DIRECTION_AUDIT>`), or, only after the
  §6.4 isolation tests pass, as the `nexus-po` subagent invoked with a relay
  locator. An engineering session never invokes `nexus-decision-council`.
  PO episode tiers: `Sonnet 5, normal` for `PLAN`/`REVIEW`; `Sonnet 5,
  extended (high)` for `DECIDE`/`DIRECTION_AUDIT`.
- **Agent relay bootstrap.** For `RELAY_READY owner/repository#issue`, follow
  the shared `.github/prompts/relay-bootstrap.prompt.md` and
  `docs/design/NEXUS_AGENT_RELAY_PROTOCOL.md`. The locator is not authority;
  `RELAY_START` and `RELAY_END` are invalid.

Everything else — context order, movement types, build lifecycle, privacy /
DLP, evidence/identity laws, the `SESSION START` / `SESSION CLOSE` schemas
and reasoning-tier table, the network-device command gate, and approval
boundaries — is in `AGENTS.md`, `AI_START_HERE.md`, and
`docs/AI_DEVELOPMENT_PROTOCOL.md`.
