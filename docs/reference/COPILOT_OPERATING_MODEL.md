# SecurityExpert — Copilot-Native Operating Model

## Purpose

This document defines how SecurityExpert is developed when GitHub Copilot is
the primary repository-native engineering surface. It is designed so a new chat
can start productively without replaying previous conversations.

## Repository memory model

The authoritative chain is:

`AGENTS.md → CURRENT_STATE.md → project metadata → current build/phase → source/tests → historical context only if required`

Chat history is useful working memory but is never the authoritative project
state. Git history records accepted source changes; project metadata records
product/build state and unresolved work.

## Standard build chat

A normal build chat has five phases:

1. `SESSION START / SCOPE`
2. `READ_ONLY_AUDIT` and, when needed, `ARCHITECTURE`
3. `IMPLEMENTATION`
4. `VALIDATION`
5. `STATE_UPDATE / SESSION CLOSE`

The same chat should normally cover these phases for one coherent build. A new
chat starts for a materially different build/phase or when an independent
review is desired.

## SESSION START template

See `AI_START_HERE.md` § "SESSION START" — the single owner of this schema.

## Architecture gate

Use an explicit architecture/implementation contract before code when the task
has one or more of these properties:

- cross-subsystem impact,
- new device interaction/command,
- security/privacy boundary,
- storage/CAS/history semantics,
- vendor-semantic ambiguity,
- deployment/server/container architecture,
- large producer/consumer graph,
- major phase closure.

For a deterministic narrow fix, a short change contract is enough.

## Implementation gate

Implementation begins only after scope is sufficiently deterministic. The agent
must preserve explicit invariants and avoid unrelated cleanup. If source audit
reveals materially larger coupling than the approved scope, stop and reclassify
the movement as `ARCHITECTURE` or `ROOT_CAUSE` rather than silently expanding.

## Validation gate

Use targeted tests first. Expand regression according to blast radius. Human
real-environment evidence is a separate gate for network-facing behavior.

A failed real-environment validation reopens the responsible build even when
unit tests passed. Do not hide failures by recreating legacy runtime folders or
adding compatibility fallbacks that violate the current architecture.

## State update gate

An accepted build updates durable state before handover. At minimum evaluate:

- `CURRENT_STATE.md`
- `project/roadmap.json`
- `project/backlog.json`
- `project/feature_registry.json`
- `project/build_history.json`
- build/design document

Only update files whose semantics actually changed.

## SESSION CLOSE template

See `AI_START_HERE.md` § "SESSION CLOSE" — the single owner of this schema.

## Movement and reasoning matrix

See `AI_START_HERE.md` § "Reasoning / model routing tiers" — the single
owner of this table.

## PO + Orchestrator provider and model routing

### Decision record — 2026-09-11

The following decisions are durable and must be used by a future PO without
replaying chat context:

- Codex is the PO + Orchestrator; workers own implementation. Codex
  synthesizes the evidence; an independent seat on a different provider
  reviews (`docs/design/GOV_PO_ROLE_MIGRATION.md` Amendment A-2026-09-11).
- The PO selects the actual provider, current model, and reasoning per
  movement. There is no permanent Sonnet/Opus/Luna/Haiku default.
- Prefer Claude when credit/license is available; use Codex when Claude is
  unavailable. Model choice is based on scope, risk, contract state, and cost.
- Architecture/security/contract work may invoke the formal council when its
  triggers hold. The PO chooses the lightest suitable model and effort for
  each seat; Terra, Opus, Sol, Fable, or another currently available model may
  be appropriate depending on scope. Fable is not mandatory, Astra is an
  optional independent second opinion, Claude worker seats are valid when
  Claude credit is available, and Codex synthesizes/reviews and freezes the
  decision.
- OpenRouter `NXS-LOCAL-0065` / `3143bb6` is an optional advisory review POC,
  not a worker fallback or an orchestrator provider.
- The current orchestrator has no unattended backlog queue-runner; the PO
  advances movements explicitly and parks blocked questions safely.
- Graphify is retained only as a narrow relationship locator until a separate
  canonical-scope rebuild; the existing oversized graph is not authority.
- Worker notifications use the ten-field Relay/Work/Provider/Model/Reasoning/
  PID/Phase/Worktree/First activity/Relay publication format below.

This routing is the durable source for the handover pointer in
`AI_HANDOVER.md`; see `AI_START_HERE.md` § "Reasoning / model routing tiers"
for the vendor-neutral tier table.

## PO worker status notification format

Every worker-start or meaningful worker-state notification uses the following
fields and reports the actual observed values; no model/provider is inferred
from the job name:

1. Relay: `NXS-LOCAL-xxxx`
2. Work: concise movement/job name
3. Provider: actual CLI/provider
4. Model: actual selected model alias/name
5. Reasoning: actual effort/tier
6. PID: process id, or `—` when not running
7. Phase: actual orchestrator phase
8. Worktree: separate worker worktree path/branch
9. First activity: first observed log activity, or `—`
10. Relay publication: commit/URL/status, or `—`

Status updates also state live-worker count, stale records, the next PO action,
and the PO's current model/reasoning/token-use line when requested. This format
is reporting only; it does not authorize a dispatch or imply that a worker
completed work.

## Full-scale development rule

High reasoning should produce a decision/contract, not automatically perform
all mechanical edits. Once architecture is frozen, hand implementation to a
normal strong model/Agent when practical. This keeps cost predictable while
preserving reasoning quality.

## Git workflow principle

After DEV.1 baseline acceptance, ZIP handover is no longer the normal workflow.
The normal unit of handover is:

`branch/commit + diff + tests + CURRENT_STATE/project metadata`

Do not push/merge until the local privacy gate, tests and staged-file review
pass. Real-environment evidence may follow in a separate validation commit/state
update when appropriate.

## DLP / privacy

The repository must remain compatible with approved enterprise inspection.
Known repository-owned DLP collision forms are guarded by tests. Do not weaken
secret detection or native vendor behavior to make AI inspection pass. Runtime
operational identities and secrets remain outside the repository.
