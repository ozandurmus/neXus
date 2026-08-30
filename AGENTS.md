# AGENTS.md — SecurityExpert Agent Contract

SecurityExpert product principle: `SEE → VERIFY → TRACE → RECOVER → OPERATE`.

## Authority and memory

Repository state is authoritative; chat history is transient working memory.
Every new AI session must be able to reconstruct the current project from the
repository without requiring a historical chat transcript.

The cold-start entry point and the fixed reading order are in
`AI_START_HERE.md`. Follow that order; this file is the canonical law it points
to, and `docs/AI_DEVELOPMENT_PROTOCOL.md` is the detailed lifecycle. Historical
detail is a data pattern: `project/build_history.json` is the structured
timeline index, `docs/history/INDEX.md` the one-line view, and the archived
agreements / validation reports live under `docs/history/`.

Do not read `docs/history/**` (phase docs, validation reports, the Continuation
Pack) by default — reach a specific record only through its
`project/build_history.json` link when a concrete gap requires it.

Do not scan `data/`, `output/`, `logs/`, CAS/runtime objects, support artifacts,
or credential stores by default.

## Engineering laws

- Work incrementally; changes must be testable and rollback-friendly.
- Evidence over assumptions; explicit `UNKNOWN` over invented certainty.
- Preserve mature collectors, evidence semantics and UI behavior unless the
  current build explicitly changes them.
- Management plane = discovery/topology/intent/provenance; direct device =
  actual/effective evidence.
- Configuration and Alignment are separate product planes.
- Secrets never enter browser/shareable artifacts or repository metadata.
- No automatic network-device write/change operations at the current maturity.
- Targeted tests first; expand regression according to blast radius.
- Automated validation and real-environment validation are separate gates.
- Stability is more important than collection speed.
- Do not increase polling/concurrency until the CP interaction-safety gate permits it.
- Do not silently broaden scope while fixing privacy, DLP, filesystem or workflow issues.

## Mandatory session start

At the start of every build/task, before code changes, produce a compact
`SESSION START`.

**First, a `PROJE ÖZETİ` (Turkish, plain language, for a non-developer
stakeholder)** — 4–6 short lines, no jargon:

- **Proje nedir:** SecurityExpert bir cümlede ne yapar.
- **Bu görev nedir:** şimdi ne yapacağız, sade dille.
- **Neden / ne kazanırız:** bu iş ürüne ne katar, hangi faydayı sağlar.
- **Tür:** yeni özellik / hata düzeltme / büyük özellik / sağlamlaştırma /
  dokümantasyon / mimari.
- **Gelecekte ne çözer / neyi açar:** ileride neyi mümkün kılar.

This block stays Turkish even though the working language is English; it exists
so the product owner can judge value without reading code. Everything after it
in `SESSION START` remains English:

- authoritative product baseline and engineering baseline,
- requested build/task and explicit scope,
- movement type (`READ_ONLY_AUDIT`, `ARCHITECTURE`, `IMPLEMENTATION`,
  `VALIDATION`, `ROOT_CAUSE`, `UI`, `DOCS`, `RELEASE_HANDOVER`),
- source/tests expected to be inspected,
- important invariants and risks,
- context intentionally not loaded,
- recommended model/reasoning level for the next action,
- recommended Git lane for this build (`feature/*`, `build/*`, or direct `main` hotfix),
- merge-to-`main` gate recommendation and required evidence,
- deployment direction for this task (`local validation only`, `staging-like`, or `production-gated`).
Do not ask the user to repeat settled project context that the repository can answer.

## Mandatory build lifecycle

For meaningful builds use:

`SCOPE → AUDIT → CONTRACT → IMPLEMENT → TARGETED_TEST → REGRESSION → HUMAN_REAL_ENV → STATE_UPDATE → HANDOVER`

Not every tiny fix needs a separate architecture document, but every build must
have one coherent objective and an explicit Definition of Done.

Status progression:

`PLANNED → IMPLEMENTED → AUTOMATED_VALIDATED → REAL_ENV_VALIDATED → DONE`

Never mark a network-facing behavior `DONE` from automated tests alone when the
build requires real-environment evidence.

## Mandatory session/build close

Before declaring a build complete, update durable project state and produce a
compact `SESSION CLOSE` containing:

- what was completed,
- what changed and what was deliberately preserved,
- tests and validation evidence,
- unresolved risks/gaps,
- roadmap/backlog/build-history changes,
- exact next build/task,
- recommended next movement type,
- recommended model/reasoning level,
- whether the next chat should continue this session or start fresh,
- recommended branch/PR target and explicit `main` merge decision (`approved` or `blocked`) with reason,
- exact non-interactive Git dispatch commands for the recommended path (stage/commit/push/PR base).
- explicit `main.py/UI effect` note: what should be visible in UI after a
  normal run, or explicit confirmation that backend-only work should produce no
  visible UI change when the existing UI is healthy.
If implementation is complete but human validation is pending, say so and do
not advance durable state beyond the evidence.

## Project-state update rule

A build that changes scope, delivery state, architecture, debt or sequencing
must update as applicable:

- `CURRENT_STATE.md`
- `project/roadmap.json`
- `project/backlog.json`
- `project/feature_registry.json`
- `project/build_history.json`
- current build/design document
- `tests/fixtures/uitest/` — whenever a `configuration_ui` / `compliance_overview`
  / `crypto` / `discovery` / `project_plan` payload field or a UI module / tab
  changes, so the render harness (`docs/AI_DEVELOPMENT_PROTOCOL.md`) keeps
  exercising the real path.

Do not silently rewrite historical outcomes. Append/rebase explicitly.

A build that touches `templates/index.html`, `static/app.js`, `static/style.css`
or a payload builder must show the HTML render harness green
(`tests/test_html_render_harness.py` / `docs/AI_DEVELOPMENT_PROTOCOL.md`)
alongside the full suite and the privacy gate.

## AI reasoning / movement routing

Default routing is task-driven, not model-brand-driven:

- `READ_ONLY_AUDIT`, log/result interpretation, narrow validation: normal/fast reasoning.
- Deterministic implementation with approved architecture: normal reasoning / Agent mode.
- Cross-file architecture, security, storage/CAS, vendor-semantic ambiguity,
  deployment, major root cause or phase closure: high reasoning first, then
  normal reasoning for implementation.
- Mechanical documentation/test cleanup: low-cost reasoning is preferred.

For the currently available Copilot model set, prefer Sol for normal source
audit/implementation and reserve Terra High (or equivalent strongest approved
reasoning mode) for genuinely cross-cutting/high-risk decisions. Auto is allowed
for low-risk work, but explicit routing is preferred for major builds.

At every meaningful checkpoint recommend the next movement type and reasoning
level. Do not use high reasoning merely because it is available.

**Explicit model + reasoning recommendation, every checkpoint.** At each of
`SESSION START`, contract freeze, before implementation, before validation and
`SESSION CLOSE`, the agent must state — to the user, in plain terms — an explicit
`model + reasoning tier` recommendation for the next step, and must say plainly
when the strongest available tier would be overkill and a lighter one is enough.
Default down, not up: name the lightest tier that covers the task, and only
escalate with a stated reason (new architecture, security/storage/CAS,
vendor-semantic ambiguity, cross-subsystem root cause, phase closure). If the
user has pre-selected a tier, still say whether it fits or is more than the task
needs.

## Context/token discipline

- Search/symbol-driven inspection before large-file reads.
- Read the minimum source set that can answer the task.
- Do not repeatedly ingest unchanged historical documents.
- This workspace already has a validated VS Code/Python/PowerShell development
  runtime. Never invoke an environment-configuration/bootstrap workflow or ask
  the human to select/create an interpreter. Use the existing `py` command
  directly. If it fails, report the concrete failure and stop; do not launch
  environment configuration unless the human explicitly requests it in that
  same chat.
- SAFE SUMMARY is preferred over full operational logs.
- When the same build continues, keep the chat; compact if needed.
- Start a fresh chat when the build/objective materially changes or the current
  context is polluted by unrelated work.
- Durable decisions belong in repository files, not only in chat.

## Privacy and DLP

Follow `PRIVACY_AND_DATA_HANDLING.md` and the local repository privacy gate.
Known enterprise DLP collision forms are repository constraints and must remain
covered by automated guards. Do not weaken security detection or native vendor
semantics merely to satisfy DLP. Do not reproduce real secrets or operational
identities in prompts, docs, tests or metadata.

## Check Point

- Validated enterprise administrator login shell is Expert; Gaia Clish must be
  invoked explicitly with `clish -c` where required.
- Some estate devices land directly in Clish; treat this as a capability, not a
  platform identity.
- Do not infer Spark/Gaia Embedded solely from direct-Clish behavior.
- VSX actual identity = physical endpoint + VSID; Expert `vsenv <VSID>` is a
  validated context mechanism.
- Raw `show configuration` is sensitive.
- Production SSH requires trusted host keys.
- New CP commands require the network-device command gate before implementation.

## Palo Alto

- Panorama = discovery/intent/provenance.
- Direct firewall = actual/effective evidence.
- Primary current configuration evidence = `effective-running`.
- Direct evidence requires identity verification.
- Production TLS requires trusted corporate CA verification.
- PAN authentication transport convergence remains a hardening concern; do not
  silently normalize behavior without an explicit build.
