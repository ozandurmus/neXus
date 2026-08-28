# SecurityExpert — GitHub Copilot Operating Instructions

## Language and role

Communicate with the user in English for all conversation, analysis and commit
messages. Keep source identifiers, schemas, filenames and vendor-native commands
verbatim. Provide a Turkish translation or explanation only when explicitly
asked.

You are working on an existing, validated network-security product. Do not
behave as if this is a greenfield repository.

Cold-start entry point and fixed reading order: `AI_START_HERE.md`. Canonical
law: `AGENTS.md`. Detailed lifecycle: `docs/AI_DEVELOPMENT_PROTOCOL.md`.

## Mandatory context bootstrap

For every new chat/task:

1. Read `/AGENTS.md`.
2. Read `/CURRENT_STATE.md`.
3. Read only the current `project/*` metadata needed to resolve scope/state.
4. Read the current build/phase document if the task references one.
5. Locate relevant source and tests with narrow search.
6. Load historical PHASE docs or the Continuation Pack only when a concrete
   unresolved question cannot be answered from current state/source.

Do not scan runtime/sensitive directories by default: `data/`, `output/`,
`logs/`, CAS/runtime objects, support artifacts or credential stores.

## First response contract — SESSION START

Before editing code, return a short `SESSION START` with:

- Current product baseline
- Current engineering baseline
- Requested build/task
- Movement type
- In scope / Out of scope
- Files/components expected to inspect
- Invariants/risks
- Context deliberately not loaded
- Recommended reasoning level for the current/next action
- Recommended Git lane for this build (`feature/*`, `build/*`, or direct `main` hotfix), with rationale
- Merge gate recommendation (what must be true before merge to `main`)
- Deployment direction for this chat (`local validation only`, `staging-like`, or `production-gated`) and required evidence

If repository state already answers a question, do not ask the user to repeat it.

## Movement types

Use one explicit movement type per active step:

- `READ_ONLY_AUDIT` — inspect and establish evidence; no edits.
- `ARCHITECTURE` — compare options and freeze an implementation contract; no edits unless explicitly requested.
- `IMPLEMENTATION` — implement an approved/deterministic scope.
- `VALIDATION` — tests, safe summaries, acceptance evidence; avoid unrelated edits.
- `ROOT_CAUSE` — isolate a failure before changing code.
- `UI` — presentation/interaction work without collector-semantic drift.
- `DOCS` — repository memory/documentation only.
- `RELEASE_HANDOVER` — close state, metadata, Git/commit handover.

If the movement type changes materially, say so before proceeding.

## Reasoning/model routing

Choose effort based on risk and ambiguity:

- Normal/fast: log interpretation, narrow audit, deterministic patch, tests,
  documentation, validation.
- Sol or equivalent normal strong model: routine multi-file source audit,
  implementation, UI, test work, implementation contracts with bounded scope.
- Terra High or equivalent strongest approved reasoning: new architecture,
  storage/CAS, security boundary, vendor-semantic ambiguity, deployment/server/
  container architecture, major cross-subsystem root cause, phase closure.

Do not spend high reasoning on mechanical work. After every meaningful step,
recommend the next movement type and reasoning level.

## Build lifecycle

Default lifecycle:

`SCOPE → AUDIT → CONTRACT → IMPLEMENT → TARGETED_TEST → REGRESSION → HUMAN_REAL_ENV → STATE_UPDATE → HANDOVER`

A build may skip a separate CONTRACT only when the change is genuinely narrow
and deterministic. Do not skip STATE_UPDATE for meaningful accepted builds.

Use status progression:

`PLANNED → IMPLEMENTED → AUTOMATED_VALIDATED → REAL_ENV_VALIDATED → DONE`

Automated tests alone do not prove network-facing real-environment behavior.

## Editing behavior

Before modifying code:

1. locate the actual implementation,
2. inspect relevant tests,
3. state the minimal intended change,
4. identify contracts that must not change.

Prefer coherent edits over telling the human to manually edit many files.
Avoid unrelated cleanup and large rewrites.

Agent mode may edit/run local tests after scope is approved. Network collection,
destructive operations, dependency changes, storage migrations and Git push/
merge require the approval rules in `docs/AI_DEVELOPMENT_PROTOCOL.md`.

## Testing

Targeted tests first. Expand to subsystem regression based on blast radius.
Run full regression for shared-core changes, release candidates and phase
closure. Do not run a full device collection for UI-only or filesystem-only work.

## Local toolchain ergonomics (workspace preference)

- If Git executable path is already known/validated in this workspace, do not repeatedly ask whether Git exists; proceed directly with that path.
- The Python runtime is already validated for this workspace. Never invoke
   `configure_python_environment`, interpreter selection, venv creation or any
   environment-bootstrap UI. Run the existing `py` command directly.
- If the existing `py` command fails, report that exact failure and leave tests
   pending. Do not configure or replace the environment unless the human
   explicitly requests environment setup in the same chat.
- Prefer minimizing repetitive setup questions in continuing sessions; only re-check when tool execution actually fails.
Known xfails remain evidence, not a mechanism for hiding regressions.

## Durable project memory

When an accepted build changes scope/state/architecture/debt/sequencing, update
as applicable:

- `/CURRENT_STATE.md`
- `/project/roadmap.json`
- `/project/backlog.json`
- `/project/feature_registry.json`
- `/project/build_history.json`
- current build/design document

Repository is memory; chat is not the project database.

## Session close contract — SESSION CLOSE

At the end of a build/session report:

- Build/status reached
- Completed work
- Preserved behavior/invariants
- Files/components changed
- Tests and real-environment evidence
- Remaining gaps/risks
- Durable metadata updated
- Exact next build/task
- Recommended next movement type
- Recommended model/reasoning level
- Continue current chat vs start new chat
- Recommended branch/PR target and whether `main` merge is approved or blocked
- Exact non-interactive Git dispatch commands for the recommended path (stage/commit/push/PR base)
- Explicit `main.py/UI effect` statement: expected operator-visible behavior
   after a normal run, or explicit confirmation that backend-only changes should
   not alter the existing UI when rendering is healthy.

If human real-environment validation is pending, provide one preferred safe
validation command and mark the build accordingly.

## Context discipline

Keep context lean. Prefer narrow search and symbol reads. Do not read all PHASE
files, the Continuation Pack, or runtime output simply because they exist.
Use `/compact` or start a new chat when the same session becomes too large;
ensure durable decisions are written to the repository before doing so.

## Privacy / DLP

Follow `/PRIVACY_AND_DATA_HANDLING.md` and `.github/instructions/privacy.instructions.md`.
Do not echo sensitive values. Keep repository-wide DLP guard tests passing.
Do not weaken secret detection, evidence redaction or vendor-native semantics to
make a prompt pass inspection.

## Network safety

Do not execute network collection unless explicitly requested. New device
commands require read/write, platform/shell/context, timeout, retry, frequency,
session reuse, unsupported behavior, secret-output and safe-telemetry review.
No device write/change automation is permitted at the current product maturity.
