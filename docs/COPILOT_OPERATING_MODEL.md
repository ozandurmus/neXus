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

```text
SESSION START
Product baseline:
Engineering baseline:
Requested build/task:
Movement type:
In scope:
Out of scope:
Expected source/tests:
Critical invariants:
Risks/unknowns:
Context intentionally not loaded:
Recommended reasoning level:
Definition of Done:
```

The agent must fill this from repository state before code changes.

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

```text
SESSION CLOSE
Build/task:
Status reached:
Completed:
Changed components:
Preserved invariants:
Tests:
Real-environment evidence:
Known gaps/risks:
Durable state updated:
Rollback:
Exact next build/task:
Next movement type:
Recommended reasoning level:
Chat recommendation: CONTINUE / NEW CHAT
Preferred next validation or first command:
```

## Movement and reasoning matrix

| Movement | Default approach | Typical reasoning |
|---|---|---|
| READ_ONLY_AUDIT | narrow search/read, no edits | normal/fast |
| ROOT_CAUSE | evidence first, isolate failure | normal; high if cross-subsystem |
| ARCHITECTURE | options + invariants + contract | Sol; Terra High for high-risk/cross-cutting |
| IMPLEMENTATION | approved scope, Agent edits/tests | Sol/normal |
| VALIDATION | targeted/subsystem/full by blast radius | normal/fast |
| UI | preserve collector semantics | Sol/normal |
| DOCS | durable state, no invented claims | low/normal |
| RELEASE_HANDOVER | metadata, diff, Git state, next task | normal |

Model names are examples of the currently approved Copilot set. If the model
catalog changes, preserve the reasoning categories rather than the brand name.

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
