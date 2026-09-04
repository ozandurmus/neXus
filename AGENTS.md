# AGENTS.md — SecurityExpert Agent Constitution

Durable, model/vendor-neutral engineering and security law. Rules here change
rarely and apply to any coding/reasoning agent working in this repository —
Claude, Copilot, ChatGPT, or a human. Never phrase a rule here as "Claude
should..." or "Copilot should..."; use "the agent MUST...". Vendor-specific
files (`CLAUDE.md`, `.github/copilot-instructions.md`,
`.github/instructions/*.instructions.md`) may translate a rule into a
product's own tool names or model tiers, but must not restate the rule itself
— point back here.

SecurityExpert product principle: `SEE → VERIFY → TRACE → RECOVER → OPERATE`.

## Authority hierarchy

When two sources disagree, higher wins. **Never silently reconcile a
disagreement between two authorities below — report the contradiction and let
the human or the higher authority resolve it.**

1. This file — durable engineering/security constitution.
2. The active FROZEN contract for the current scope (a `docs/history/phase/*.md`
   or `docs/design/*.md` document whose own status line says `FROZEN`, or
   equivalent canonical wording) — scope-specific design law. A document
   whose status line says `DRAFT`, `DO NOT FREEZE`, `SUPERSEDED` or
   `DEPRECATED` may guide investigation but **must not** be treated as
   implementation authority, cited as approving a command/schema/identity
   model, or have its `UNKNOWN`s silently reinterpreted as decided.
3. `project/*.json` — machine-readable project-state authority
   (`project/README.md` defines which file owns what;
   `utils/project_plan._cross_authority_warnings` plus
   `tests/test_architecture_convergence.py` enforce internal JSON↔JSON and
   JSON↔`CURRENT_STATE.md` agreement).
4. `CURRENT_STATE.md` — concise, hot, human-readable projection of #3. Must
   never contain a claim `project/*.json` doesn't support.
5. Source + tests — implementation reality.
6. `docs/design/*.md`, `docs/reference/*.md`, `docs/history/**` — targeted
   evidence and historical record, reached by lookup, not scanned by default.
7. Chat/session memory — transient, **never authoritative**. Every new
   session must be able to reconstruct the current project from the
   repository alone, without a historical chat transcript.

## Engineering-output language law

Repository artifacts, engineering reports, `SESSION START` / `SESSION CLOSE`
reports, PR descriptions, commit messages, structured checkpoints, code
comments and agent-to-agent engineering handovers are **English by
default**. The language a human converses in does **not** change the
language of engineering output. Another language is used only when a
repository-owned artifact explicitly requires it for that artifact. Vendor
CLI commands, API fields and code identifiers stay verbatim. No engineering
report carries a non-English preamble; a stakeholder-facing summary in
another language, if ever wanted, is a separately requested artifact, not
part of the session contract. This section is the single owner of the rule:
`AI_START_HERE.md`, `CLAUDE.md` and `.github/copilot-instructions.md`
point here and must not restate a different version.

The cold-start entry point and fixed reading order are in `AI_START_HERE.md`.
Follow that order. `docs/AI_DEVELOPMENT_PROTOCOL.md` is a detailed reference
for the network-device command gate, approval boundaries, and render-harness
mechanics — consult it, don't restate it.

Do not read `docs/history/**` (phase docs, validation reports, the
Continuation Pack) by default — reach a specific record only through its
`project/build_history.json` link when a concrete gap requires it. Do not
scan `data/`, `output/`, `logs/`, CAS/runtime objects, support artifacts, or
credential stores by default.

## Engineering laws

- Work incrementally; changes must be testable and rollback-friendly.
- Evidence over assumptions; explicit `UNKNOWN` over invented certainty.
- Preserve mature collectors, evidence semantics and UI behavior unless the
  current build explicitly changes them.
- Configuration and Alignment are separate product planes: Inventory
  describes runtime/operational state; Configuration describes current
  configured state; Alignment compares expected intent against actual/
  effective state. Do not collapse these merely because they share data.
- Secrets never enter browser/shareable artifacts or repository metadata.
- Targeted tests first; expand regression according to blast radius.
- Automated validation and real-environment validation are separate gates.
  Never mark a network-facing behavior `DONE` from automated tests alone when
  the build requires real-environment evidence.
- Stability is more important than collection speed.
- Do not increase polling/concurrency until the relevant vendor
  interaction-safety gate permits it.
- Do not silently broaden scope while fixing privacy, DLP, filesystem or
  workflow issues.

**Before editing:** locate the actual implementation, inspect its tests,
state the minimal intended change, and name any contract/invariant the
change must not break. Prefer coherent agent-made edits + diff + tests +
human validation over instructing the human to hand-edit files.

## Evidence laws

These invariants apply across every vendor and every evidence-producing
subsystem. None may be collapsed into the other merely because they are
usually true together:

- Evidence identity != operational identity. A physical/evidence entity
  (a device, a member) is not the same thing as the operational unit
  (a cluster, an HA pair, a Virtual System) it participates in.
- Configuration intent != runtime truth. A declared value answers "what was
  configured"; it never proves "what is true right now."
- Management-plane observation != direct-device runtime truth. Discovery/
  intent/provenance from a manager is not the same evidence grade as a
  direct, identity-verified read from the device itself.
- A member's report about its peer != independent peer observation. One
  side's claim about the other is one-sided until the other side
  independently corroborates it in the same evidence-collection pass.
- Presentation identity != security identity. A hostname, display label, or
  inferred ordinal (`-1`/`-2`) is never a join key or an identity gate.
- Pair/cluster existence != pair/cluster health. Whether two entities form
  one operational unit, and whether that unit is currently safe, are
  independent questions with independent evidence.
- Readiness != authorization. A green readiness assessment is one input to
  an authorization decision, never the decision itself.
- Collection success != semantic correctness. A command returning output
  does not prove the parser understood it or that the field means what its
  name suggests.
- Field presence != field semantic proof. A field existing in a response is
  not evidence of its meaning; that requires vendor documentation or
  real-environment corroboration.
- Command presence in source != command approval. A command already being
  issued somewhere does not authorize using its output for a new purpose
  without going through the network-device command gate for that purpose.
- Automated validation != real-environment validation (restated here because
  it governs evidence, not just build status: a fixture-constructed test
  proves the parser, not the vendor's real output shape).

## Identity law — identifiers are opaque

Identifiers are opaque unless a FROZEN vendor contract proves otherwise.
This covers serials, UUIDs, object/group IDs, VSIDs represented as
identifiers, certificate fingerprints, and any vendor-generated device
identity token.

The agent MUST NOT casually:

- cast an identifier to integer,
- strip leading zeroes,
- apply digit-only or other numeric normalization,
- truncate or pad an identifier,
- infer equality from formatting similarity,
- normalize case/punctuation unless the semantics are proven,
- invent an equivalence rule merely to make evidence match.

Allowed representation normalization must be justified by vendor semantics,
a FROZEN repository contract, or a proven representation-only transformation
(e.g. whitespace stripping already applied identically on both sides before
comparison). If equality cannot be proven this way, report `UNKNOWN`,
`MISMATCH`, or `NOT_EVALUABLE` — never a guessed equality.

## Sensitive identity reporting law

Default real-environment reporting pattern for sensitive/local identities:

```
compare locally → report the relationship, not the values
```

Preferred vocabulary: `MATCH` / `MISMATCH` / `MISSING` / `NOT_EVALUABLE` /
`AMBIGUOUS`. Do not unnecessarily reproduce serials, management addresses,
HA/control-link addresses, credentials, usernames/principals, host-key
material, or other raw device identity values into chat, project docs, Git
metadata, screenshots intended for sharing, or support reports — a model
must not retrieve or echo a raw value merely because it is available.

When reporting a repository privacy finding specifically, report **file +
location + classification**, never the matched value.

## UNKNOWN / fail-closed law

Absence of evidence is not evidence of absence. Collection failure is not a
known-bad state. Unconfirmed vendor semantics are not inferred semantics. A
missing peer observation is not a failed peer. Configuration/runtime
disagreement must not erase an already-established operational identity. A
one-sided peer claim is not bidirectional corroboration. When a claim cannot
be proven, prefer `UNKNOWN` / `INSUFFICIENT_EVIDENCE` / `COLLECTION_FAILED` /
`RELATIONSHIP_INCONSISTENT` / `UNSUPPORTED` over fabricated certainty.

## Vendor semantics law

A field name is not its contract. A command name is not its semantics. A
command returning output does not prove the parser understands it. Required
evidence hierarchy for a vendor-sensitive claim: repository source + real-
environment evidence + official vendor documentation where the claim is
safety-critical. If official documentation cannot establish a load-bearing
semantic, mark it `UNKNOWN` — do not fill the gap from general model/product
knowledge. Official documentation research is mandatory before freezing new
safety-critical network-command semantics the repository does not already
have a proven contract for.

## Diagnostic-path law

Do not create a parallel diagnostic credential/network path when the
existing controlled application path can already answer the question.
Preferred pattern: existing authenticated transport → existing network
operation → bounded parser/diagnostic projection → sanitized derived
evidence. Diagnostic convenience alone never justifies a new credential
boundary; a genuinely separate path requires its own security/command review.

## Raw-evidence law

Do not persist raw vendor responses merely to make debugging easier.
Preferred lifecycle: response in memory → parse the minimum required
semantics → safe enums/counters/tokens/relationships → discard the raw
response. Prefer field-name enumeration, presence booleans, safe semantic
classes, local equality comparisons, and tokenized identities over raw
retention. A raw response may be retained only when an explicit evidence/
forensics contract authorizes it and its privacy handling is defined.

## Contract-status law

Every contract-bearing document states its status explicitly: `DRAFT`,
`DO NOT FREEZE`, `FROZEN`, `SUPERSEDED`, or `DEPRECATED` (or an existing
canonical equivalent). `DRAFT`/`DO NOT FREEZE` may guide investigation; it
must never authorize implementation of the load-bearing semantics it leaves
unresolved. `SUPERSEDED` is historical only. Never silently treat a draft as
implementation authority — see Authority hierarchy, item 2.

## Mandatory session start / close

At the start of every build/task produce a `SESSION START`; before declaring
a build complete, update durable project state and produce a `SESSION
CLOSE`. The schemas and the reasoning-tier table live in `AI_START_HERE.md`;
both reports are English with no preamble ("Engineering-output language
law" above); this rule only states that both are mandatory, not skippable,
and that
`AI_HANDOVER.md` (non-authoritative — see below) must be rewritten as part of
every `SESSION CLOSE`.

Movement types: `READ_ONLY_AUDIT`, `ARCHITECTURE`, `IMPLEMENTATION`,
`VALIDATION`, `ROOT_CAUSE`, `UI`, `DOCS`, `RELEASE_HANDOVER`.

## Mandatory build lifecycle

For meaningful builds use:

`SCOPE → AUDIT → CONTRACT → IMPLEMENT → TARGETED_TEST → REGRESSION → HUMAN_REAL_ENV → STATE_UPDATE → HANDOVER`

Not every tiny fix needs a separate architecture document, but every build
must have one coherent objective and an explicit Definition of Done. A
frozen contract is required before implementation when the task introduces
new vendor semantics, new network-device commands, new identity/operational-
unit semantics, CLASS 2+ behavior, storage/schema migration, or a major
security boundary; a deterministic fix inside an already-frozen contract does
not need a new one.

Status progression:

`PLANNED → IMPLEMENTED → AUTOMATED_VALIDATED → REAL_ENV_VALIDATED → DONE`

`PARTIAL` or `BLOCKED` when evidence does not justify advancement. Never mark
a network-facing behavior `DONE` from automated tests alone.

## Handover economy

`AI_HANDOVER.md` (explicitly non-authoritative — a convenience pointer, never
a state authority) and the hot section of `CURRENT_STATE.md` exist so a cold
chat can resume in **one read each** — not to narrate a session. A new chat
reconstructs the task from: `AI_START_HERE.md` → `CURRENT_STATE.md` (hot
section) → `AI_HANDOVER.md` → the **one** phase/design doc the task names. If
it needs more than that, the handover was too thin or the phase doc too
vague — fix those, do not pad the rotating docs.

- `AI_HANDOVER.md`: snapshot (≤6 lines), what changed this session (bullets),
  exact next action, test delta, new risks. No decision re-litigation, no
  doc-editing mechanics, no restating the phase/contract doc. If anything
  here looks stale, `project/roadmap.json` and `CURRENT_STATE.md` win.
- `CURRENT_STATE.md` "Active build": status + one-line scope + link to the
  phase doc. Predecessor builds are one line each — or left entirely to
  `project/build_history.json`.
- `project/build_history.json`: obey its own `record_contract` — summary ≤ 2
  sentences, detail lives in the linked doc.
- Never copy the same paragraph into three files. The phase/design doc is
  the spec; the rotating docs point at it and carry only what is *not* in it.

## Project-state update rule

A build that changes scope, delivery state, architecture, debt or sequencing
must update as applicable: `CURRENT_STATE.md`, `project/roadmap.json`,
`project/backlog.json`, `project/feature_registry.json`,
`project/build_history.json`, the current build/design document, and
`tests/fixtures/uitest/` whenever a `configuration_ui` / `compliance_overview`
/ `crypto` / `discovery` / `project_plan` payload field or a UI module/tab
changes (`docs/AI_DEVELOPMENT_PROTOCOL.md` render-harness section).

Do not silently rewrite historical outcomes. Append/rebase explicitly. A
build touching `templates/index.html`, `static/app.js`, `static/style.css`,
or a payload builder must show the HTML render harness green
(`tests/test_html_render_harness.py`) alongside the full suite **and** the
repository privacy gate.

## AI reasoning / movement routing

Routing is task-driven, not model-brand-driven. Default down, not up: name
the lightest tier that covers the task, only escalate with a stated reason
(new architecture, security/storage/CAS, vendor-semantic ambiguity,
cross-subsystem root cause, phase closure), and say plainly when a
pre-selected tier is more than the step needs. Recommend the next movement
type and reasoning level at every meaningful checkpoint — `SESSION START`,
contract freeze, before implementation, before validation, `SESSION CLOSE`.
Do not use high reasoning merely because it is available. The concrete
tier-name table (which model/tier maps to which task category) lives in
`AI_START_HERE.md`; tool-specific tier names live only in that tool's own
delta file (`CLAUDE.md`, `.github/copilot-instructions.md`).

## Context/token discipline

- Search/symbol-driven inspection before large-file reads.
- Read the minimum source set that can answer the task.
- Do not repeatedly ingest unchanged historical documents.
- This workspace has an already-validated development runtime (interpreter,
  shell, package manager) as a standing workspace fact, not a per-session
  claim to re-verify. Never invoke an environment-configuration/bootstrap
  workflow or ask to select/create an interpreter. Use the existing runtime
  command directly. If it fails, report the concrete failure and stop; do
  not launch environment configuration unless explicitly requested in that
  same chat.
- SAFE SUMMARY is preferred over full operational logs.
- When the same build continues, keep the chat; compact if needed. Start a
  fresh chat when the build/objective materially changes, a major phase
  boundary is crossed, an independent architecture review is wanted, or the
  current context is polluted by unrelated work — only after durable
  decisions are written to the repository.
- Durable decisions belong in repository files, not only in chat.

## Privacy and DLP

Follow `PRIVACY_AND_DATA_HANDLING.md` (the CLASS 0–3 data-sensitivity
vocabulary lives there, not here — do not restate it) and the local
repository privacy gate. Known enterprise DLP collision forms are repository
constraints and must remain covered by automated guards. Do not weaken
security detection or native vendor semantics merely to satisfy DLP. Do not
reproduce real secrets or operational identities in prompts, docs, tests or
metadata.

Note the data-sensitivity `CLASS 0-3` scheme in `PRIVACY_AND_DATA_HANDLING.md`
and the operational-risk `utils.action_taxonomy` `CLASS_0..CLASS_4` scheme
are unrelated namespaces that happen to share small integers and the word
"class" — do not conflate them.

## Network action taxonomy

`utils/action_taxonomy.py` is the single source of truth for what the
product may execute — `CLASS_0_READ` through `CLASS_4_POLICY_DEPLOYMENT`.
Documentation references it; do not redefine the taxonomy in prose
(`AI_START_HERE.md` carries the current human-readable table). No automatic
(unscheduled-trigger, non-ledgered) network-device write/change operation is
permitted at the current maturity; class 1 controlled recovery writes are
permitted only through their `RB.x` contracts, are never console-submittable,
and are not "the product is read-only" — that shorthand stopped being true
once `RB.x` shipped and must not be restored anywhere in this repository.

New/changed device commands require the network-device command gate
(`docs/AI_DEVELOPMENT_PROTOCOL.md`) before implementation — vendor,
read/write class, shell/context, timeout, retry, frequency, session reuse,
unsupported behavior, secret-output risk, safe telemetry. A parse-scope
extension of a command already issued (same command/session/timeout/
frequency) is not a command addition and needs no new gate entry.

## Architectural invariants (test-enforced, not merely current)

- No Browser → device path. The operator console submits typed intent
  (`job_type` + `entity_id`) against a closed module-level registry; no
  command, argv fragment, path, or API route ever originates in the browser.
- `utils/failover/` contains only read-only assessment/evidence modules
  (`assessment.py`, `preflight_model.py`, `preflight_readiness.py`) with
  exactly one verdict roll-up (`assessment._verdict_for`). The absence of a plan,
  executor, or vendor adapter is enforced by
  `tests/test_architecture_convergence.py`, not a current-phase courtesy.
- `OP.0a`'s HA readiness assessment cannot emit `SAFE_TO_FAILOVER` or
  `DEGRADED_PROCEED_WITH_RISK` — enforced over a generated matrix, not
  merely undocumented.
- Corporate Git push/merge remains human-controlled.

## Check Point

- Validated enterprise administrator login shell is Expert; Gaia Clish must
  be invoked explicitly with `clish -c` where required.
- Some estate devices land directly in Clish; treat this as a capability,
  not a platform identity. Do not infer Spark/Gaia Embedded solely from
  direct-Clish behavior.
- VSX actual identity = physical endpoint + VSID; Expert `vsenv <VSID>` is a
  validated context mechanism.
- ClusterXL member differences are `MEMBER_SPECIFIC` unless expected-state
  evidence proves otherwise — do not infer drift from a peer comparison
  alone.
- Raw `show configuration` is sensitive: parse to safe fields in memory,
  never persist or surface it raw (see Raw-evidence law).
- Production SSH requires trusted host keys.
- New CP commands require the network-device command gate before
  implementation.

## Palo Alto

- Panorama = discovery/intent/provenance, including Template/Template Stack
  and Device Group assignment; direct firewall = actual/effective evidence.
- Primary current configuration evidence = `effective-running`.
- Direct evidence requires identity verification wherever the product
  contract defines an identity gate.
- Production TLS requires trusted corporate CA verification. Historical POC
  TLS-verification exceptions are technical debt, never production design.
- PAN authentication transport convergence remains a hardening concern; do
  not silently normalize behavior without an explicit build.
