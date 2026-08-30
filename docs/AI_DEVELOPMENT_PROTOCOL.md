# SecurityExpert — AI Development Protocol

## Goal

Use AI as a repository-native engineering team without making conversation
history the project database.

**Repository is memory. Chat is transient. Git is the change ledger.**

This protocol applies to Copilot, ChatGPT, Claude and future approved coding
agents. Copilot is the primary repository-native implementation surface once
DEV.1 establishes the controlled Git baseline.

## Context hierarchy

### Tier 1 — always small

- `AGENTS.md`
- `CURRENT_STATE.md`
- `.github/copilot-instructions.md` when using Copilot
- only current `project/*` metadata needed for the task

### Tier 2 — current task

- current build/phase/design document
- relevant source
- relevant tests
- path-specific instructions

### Tier 3 — historical only when required

All under `docs/history/`, reached through `project/build_history.json` links:

- `docs/history/SECURITYEXPERT_AI_CONTINUATION_PACK.md`
- `docs/history/phase/PHASE*.md`
- `docs/history/validation/VALIDATION*.txt`
- old telemetry

Do not read Tier 3 merely because it exists.

## Session lifecycle

### Start

Every meaningful session begins with `SESSION START`.

It opens with a **`PROJE ÖZETİ`** (Turkish, plain language, 4–6 lines, no
jargon) for the non-developer product owner: what SecurityExpert is, what this
task is, why we are doing it / the benefit, its type (feature / bug fix / major
feature / hardening / docs / architecture), and what it enables or solves in
future. This block is Turkish by design; the rest of `SESSION START` is English:

1. authoritative product baseline,
2. authoritative engineering baseline,
3. requested build/task,
4. movement type,
5. scope and explicit exclusions,
6. expected source/tests,
7. invariants/risks,
8. intentionally unloaded context,
9. recommended reasoning level.

The agent must resolve routine ambiguity from repository state before asking the
human to restate project history.

### During

Use one active movement type:

`READ_ONLY_AUDIT | ARCHITECTURE | IMPLEMENTATION | VALIDATION | ROOT_CAUSE | UI | DOCS | RELEASE_HANDOVER`

Default lifecycle:

`SCOPE → AUDIT → CONTRACT → IMPLEMENT → TARGETED_TEST → REGRESSION → HUMAN_REAL_ENV → STATE_UPDATE → HANDOVER`

For narrow deterministic fixes, AUDIT/CONTRACT may be compacted into the same
step. For security/storage/vendor-semantic work, keep them explicit.

### Close

Every meaningful session ends with `SESSION CLOSE`:

- achieved build status,
- completed changes,
- preserved contracts,
- tests/evidence,
- unresolved gaps,
- project metadata updates,
- exact next build/task,
- next movement type,
- recommended reasoning level,
- whether to continue or start a fresh chat.

Before a chat is abandoned/compacted, durable decisions must be written to the
repository.

## Build status model

`PLANNED → IMPLEMENTED → AUTOMATED_VALIDATED → REAL_ENV_VALIDATED → DONE`

`PARTIAL` or `BLOCKED` must be used when evidence does not justify advancement.
Automated tests alone do not make network-facing behavior DONE.

## Build size

Default build: one coherent objective, roughly 3–10 relevant source files,
focused tests and one preferred real-environment validation path.

A larger build is acceptable when producer/consumer consistency or shared-core
atomicity requires it; explain why before implementation.

Avoid bundling unrelated architecture, UI, collector and storage work.

## Reasoning/model routing

Route by ambiguity/risk, not habit:

- Fast/normal reasoning: result/log interpretation, narrow retrieval, tiny fix,
  documentation, validation.
- Strong normal reasoning (Copilot Sol or equivalent): routine multi-file audit,
  implementation, UI, tests, bounded implementation contracts.
- High reasoning (Terra High or equivalent): new architecture, security/storage/
  CAS, vendor-semantic ambiguity, deployment/server/container, major root cause,
  cross-subsystem design, phase closure.

Use high reasoning to decide; use normal reasoning to implement when the
contract becomes deterministic. Do not use the strongest model for mechanical
work. Record/recommend the next reasoning level at each meaningful checkpoint.

Auto mode is acceptable for low-risk work, but explicit model routing is
preferred for important builds so cost and reasoning quality remain observable.

## Copilot chat/session strategy

Use the same chat while the build/root-cause remains coherent. Start a new chat
when:

- the build/objective changes materially,
- moving from one major phase to another,
- the context is polluted by unrelated work,
- an independent architecture review is desired.

Do not create a new chat for every prompt merely to reduce usage. If the same
build becomes long, compact it only after state/decisions are durable.

A new chat must be able to recover from `AGENTS.md + CURRENT_STATE.md + project
metadata + Git/source`; no hand-written historical chat summary should be
required for normal continuation.

## Testing tiers

Tier 1: affected tests, syntax, security invariants.

Tier 2: affected subsystem/vendor regression.

Tier 3: full regression for shared-core changes, phase closure and release
candidates.

Do not run expensive real-device collection for UI-only or documentation work.

## Test execution economy (mandatory)

Use one-shot, file-backed local test runs to prevent repeated token/credit burn:

- Full suite (parallel): `py -m pytest -q -n auto --dist worksteal > pytest_result.log 2>&1`
  (requires `pip install -r requirements-dev.txt`; ~44s on 16 cores vs ~110s
  serial). `scripts/pytest_one_shot.ps1` does this by default; pass `-Serial`
  (or `-n0`) to run serially when debugging a single failure.
- Read evidence from file (prefer Unicode read on Windows):
  `Get-Content pytest_result.log -Encoding Unicode -Tail 40`
- Re-run full suite only when source changes after that evidence.

When the same session already validated Python/runtime terminal setup, do not
re-bootstrap PATH/interpreter repeatedly. Only apply minimal correction on an
actual command failure and continue.

## HTML render harness (mandatory for any UI / payload change)

Any build that changes `templates/index.html`, `static/app.js`,
`static/style.css`, or a payload builder (`configuration_ui` / `compliance` /
`crypto` / `discovery` / `project_plan`) must show the render harness green
alongside the full suite:

```
py -V:3.12 scripts/render_uitest.py --out <dir>
bun tools/render-harness/check-render.mjs <dir>/output/index.html
```

`tests/test_html_render_harness.py` runs both (the JSON-validity half needs no
JS engine; the headless-navigation half skips cleanly when `bun` is absent).
The generated report is one inline `<script>` — if it fails to parse or throws
before the nav listeners attach, every button is dead while the page still looks
loaded (the `0.7.4a` failure). If the change touches a payload field or UI
module, also extend `tests/fixtures/uitest/` (see its README growth rule) so the
harness actually exercises the new path.

## Human / agent responsibility split

AI may perform coherent source edits and local tests. Git must expose exact
changes. Human performs or explicitly approves real-environment/network
acceptance and sensitive operational actions.

Avoid the workflow where the AI instructs the human to manually edit file after
file. Prefer agent changes + diff + tests + human validation.

## Build report contract

Every meaningful implementation reports:

- existing behavior,
- changed behavior,
- files/components changed,
- preserved invariants,
- risks,
- targeted/full tests,
- rollback,
- Definition of Done,
- one preferred real-environment validation command when applicable,
- project metadata changes,
- next movement/model recommendation.

## Network-device command gate

Before adding/changing a device command, document:

1. why it is required,
2. read-only vs write,
3. vendor/platform/shell/context,
4. timeout,
5. retry,
6. maximum execution frequency per endpoint,
7. existing-session reuse,
8. unsupported behavior,
9. secret-bearing output risk,
10. safe telemetry.

No new write command at the current product maturity.

## Approval boundaries

Generally allowed without additional approval after scope is accepted:
source edits, local unit tests, render-only validation, static analysis,
documentation and explicitly requested read-only local checks.

Require explicit human approval: dependency additions/upgrades, schema/storage
migration, destructive local-data operations, full-fleet collection when not
already requested, new network-access patterns, Git push/merge until DEV.1 Git
workflow is accepted.

Prohibited at current maturity: firewall configuration writes, policy install,
commit, reboot/shutdown, forced failover, interface/routing change, credential
change and automatic remediation.

## Token/context discipline

Before reading a large file ask whether the task requires it. Prefer
search/symbol-driven inspection. Do not re-read unchanged large files, scan all
historical docs, ingest runtime output directories, paste full logs when SAFE
SUMMARY is sufficient, or repeatedly explain settled architecture.

Persist durable decisions in repository metadata/docs.
