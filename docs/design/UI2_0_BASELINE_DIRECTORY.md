# UI 2.0 — B0 baseline directory

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-09** (platform contract freeze (C1–C6 + baseline directory), per `UI2_0_BASELINE_CONTRACT.md` §2 `FREEZE-SLICING`). Open items listed in this document's own open-items section are deferred to the movements they name; they do not reopen this freeze. Previous status: DRAFT — FOR PRODUCT OWNER FREEZE. Written under
`docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
2026-09-09) and `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B0-8 (BASELINE,
revision 2), whose exact scope line this document answers verbatim: "one
index: product objects and navigation, shared screen states, feature
contribution contract, alarm lifecycle contract (outbox, de-duplication,
resolve/silence, delivery failure, causality limits), log/audit data classes,
SNMP status exposure — each with owner document and version (R-02, R-03)."

Runs concurrently with the extraction-tooling movement (Line-1 `scripts/`
tooling implementing `C6`'s fixture-generation procedure). Fully independent:
that movement touches Line-1 scripts; this document touches only
`docs/design/`. This is the second-to-last `B0` item; once both merge, `B0`
is complete and the Product Owner can move to the `C1`–`C7` freeze decision,
then to `B1`.

Nothing in this document authorizes implementation or device execution
(`UI2_0_BASELINE_CONTRACT.md` acceptance sentence A-1, unchanged and binding
here). This is a **catalogue**, not a seventh platform contract: where `C1`–
`C7` or an existing frozen document already owns a concern, this document
cites it bindingly instead of restating it; only concerns with no current
owner get new text here, kept minimal and schema-level.

---

## 1. Scope and the ownership-catalogue method

The council settled six product-baseline concerns that no single `C1`–`C7`
contract individually owns end to end (`UI2_0_DEVELOPMENT_WORKFLOW.md` §5
B0-8, restated in `UI2_0_BASELINE_CONTRACT.md` §6: "B0 opens as the primary
development backlog... the baseline directory"):

1. Product objects and navigation (§2)
2. Shared screen states — the classification vocabulary (§3)
3. Feature contribution contract (§4)
4. Alarm lifecycle contract (§5)
5. Log/audit data classes (§6)
6. SNMP status exposure (§7)

**Method.** For each concern this document states, in order of preference:

1. **Owned elsewhere, unconditionally.** Cite `<document, section, version/
   status>`; write nothing new. Applies when an existing frozen or
   already-merged document already answers the concern completely.
2. **Owned elsewhere, adopted here.** The owning document supplies the
   substance (a vocabulary, a rule, a table shape); this document states how
   it applies to the baseline-directory concern without re-deriving it.
3. **Genuinely new.** No existing document owns the concern. New text is
   written here, kept to schema/index level — never a full implementation
   design, per the workflow's own instruction that a genuinely new alarm
   lifecycle "stays schema-level... not a full implementation design."

No decision frozen in `UI2_0_BASELINE_CONTRACT.md`, `NAVIGATION_INFORMATION_
ARCHITECTURE.md`, or `CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` is
reopened by this document. Where this document's cataloguing work surfaced a
contradiction with frozen authority, it is reported in §9, never silently
resolved (`UI2_0_BASELINE_CONTRACT.md` §1 item 4 / D-6's own instruction that
"conditional" is not "skipped," applied here to "frozen" not being
"revisable in passing").

---

## 2. Product objects and navigation

**Owner: `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` (FROZEN —
PRODUCT OWNER APPROVED, 2026-09-05, revision 3, amended by `M3`'s `FA-1`…
`FA-9`, 2026-09-06) for the information-architecture principles; `docs/
design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (DRAFT — FOR PRODUCT
OWNER FREEZE) §5/§6 for the RBAC enforcement mechanism.** No new navigation
architecture is written here — UI 2.0 is its own shell (`CON.0-AMENDMENT`,
`UI2_0_BASELINE_CONTRACT.md` §2 `DIRECTORY-POSTURE` row context; `C5`), so it
implements the same *principles*, not the same routes, DOM, or shell code.

### 2.1 What UI 2.0's navigation adopts from `NAVIGATION_INFORMATION_ARCHITECTURE.md`, by decision id

| Principle | Source decision | What UI 2.0 does with it |
| --- | --- | --- |
| Left vertical rail, grouped by stable product domain, not by action | D-NAV1, D-NAV3 | UI 2.0's own rail follows the same grouping discipline; the six-root baseline (`PO-NAV-8`) is Line-1's shell inventory, not binding on UI 2.0's own root set, which is a `B1`/`B2` implementation decision against this same principle |
| Collapse changes presentation density only, never availability or authority | D-NAV2 | adopted verbatim as an invariant |
| Devices is a first-class root; device-scoped functions are tabs inside an entity workspace, never roots | D-NAV4, §2.3 (D-NAV13) | adopted; UI 2.0's device workspace (workflow B1 step 9) is this principle's first implementation |
| Product-surface eligibility (`P1`) is the only thing that decides whether a root/module is rendered; entity applicability (`P2`) and evidence state (`P3`) decide only what it *says* | D-NAV6a, D-NAV11, §7 | adopted verbatim: a UI 2.0 module never disappears because the selected device does not use that capability |
| No visual placeholder; a capability with no surface is omitted, a capability with a surface that is inapplicable/unsupported/unconfigured/blocked/evidence-poor is shown, selectable where useful, and explained in words | D-NAV6b | adopted verbatim — the honest-affordance law this document's §3 vocabulary renders against |
| Device-tab stability: a structurally inapplicable tab stays visible and selectable with a `NOT_APPLICABLE` explanation and no enabled action; a tab is omitted only on a P1 (no-surface) failure | D-NAV13 | adopted verbatim for the device workspace |
| Authorization is an additional, additive conjunct (`P4`), never a retrofit of the DOM/surface check | D-NAV9, §7.4 | superseded in its concrete mechanism for UI 2.0 by `C3`'s real `E1`–`E7` gate chain (§2.2 below) — the *principle* (additive, never a P1 proxy) is what is adopted; Line-1's `model: "none"` placeholder does not apply to UI 2.0, which ships RBAC from `B1` step 3 |
| Colour is never the only carrier of a state's meaning; red is reserved for actual fault, unsafe drift or failure | D-NAV14 | adopted verbatim; this is the same rule §3 states in the mockup's own words |

Every row not adopted above (the specific six-root Line-1 baseline, the
`localStorage`/hash-route selection mechanism, the exact DOM/`[data-module-
panel]` last-mile check, and every Line-1 shell implementation detail in
`NAVIGATION_INFORMATION_ARCHITECTURE.md` §2.2) is Line-1 shell-specific and
is **not** carried into UI 2.0; UI 2.0 implements the frozen *principles*
against its own Java shell, per `RUNTIME-DIRECTION` (workflow P-1…P-5).

### 2.2 RBAC visible-but-refused rendering

UI 2.0's navigation model's enforcement mechanism is `C3` §5/§6, not a new
mechanism written here:

- Every navigation entry and contextual action is an `action_id` in `C4`'s
  closed registry (`C3` §5.4, render path). A `GET` request rendering the
  navigation/action affordances runs `C3`'s `E1`–`E6` gate chain per
  declared `action_id` and returns `action_affordance[action_id] =
  {outcome, authority?, reason_code?}` for **every** action, including ones
  the acting role may not use (`C3` §5.4).
- The rendered entry is never hidden for an authorization reason. It is
  **shown, disabled, and labelled with the exact `reason_code`** — `C3`
  §5.2/§5.3's `DENIED(actor_not_in_required_group)` or
  `AUTHZ_NOT_EVALUATED(role_token_unbound | actor_group_set_stale)` — the
  same "visible but refused" shape Line-1's D-NAV6b/D-NAV13 already
  established for surface/applicability reasons, now extended by `C3` to
  cover the fourth predicate (`P4`, authorization) that Line-1's own
  `model: "none"` placeholder left unimplemented.
- This is exactly the pattern the "neXus 2026 Design Refresh" mockup shows
  for a role-gated action: an entry such as **"Collect now"** rendered
  visible and labelled **"console only"** rather than omitted — the mockup's
  own visual expression of the same rule `C3` specifies at the HTTP/data
  layer (`action_affordance` with a named `reason_code`, never a bare
  greyed control per `CON.0` §9's honest-affordance law, carried into `C3`
  §5.2). `UI2_0_BASELINE_CONTRACT.md`'s `VISUAL-DESIGN-LANGUAGE` row
  already states this mockup pattern is "adopted into the baseline
  directory (B0-8), not re-decided" — this subsection is that adoption.
- Layer separation: `C3` §5.4 requires the front end to hold no role concept
  and compute no visibility decision of its own (`AG-J3`) — the same
  discipline Line-1's D-NAV9 stated for its own (then-absent) authorization
  model, now real in UI 2.0 from `B1` step 3.

No new schema, endpoint, or rendering rule is introduced by this subsection;
it names how §2.1's adopted principles and `C3`'s already-specified
mechanism compose for the navigation surface specifically.

### 2.3 Product objects

The logical-subject model (§5.2 of `NAVIGATION_INFORMATION_ARCHITECTURE.md`:
logical-entity-first selection, shared-vs-member-specific value rendering,
one selected-entity context shared across modules with independent
per-module data authority) is adopted as the same principle UI 2.0's device
workspace (workflow B1 step 9, `B1-4b`'s Device/Endpoint/CredentialReference
records) implements against its own schema (`C1` §3.2). This document adds
no new object model; `C1` §3 and `C4` §2/§4 (VSX/ClusterXL multiplicity)
already own the concrete UI 2.0 schema and target-model shapes.

---

## 3. Shared screen states — the classification vocabulary

**Owner: `docs/design/UI2_0_BASELINE_CONTRACT.md` `VISUAL-DESIGN-LANGUAGE`
row (ACCEPTED 2026-09-09) for the decision that the mockup's vocabulary is
adopted, not re-decided; the "neXus 2026 Design Refresh" design canvas
itself (19 artboards; a full Material Design 3 product screen set: Overview,
Devices→Inventory, Configuration→Alignment, Compliance, Operations,
Administration, Navigation & components) for the vocabulary's exact words
and rule, as recorded verbatim in this movement's own session packet.**

### 3.1 The adopted vocabulary, in the mockup's own words

| Mockup term | What it means |
| --- | --- |
| **Aligned / healthy** | current, expected, no drift |
| **Member-specific** | a value that legitimately differs per cluster/HA member; expected, not a fault |
| **Local override (intentional)** | a deliberate per-member deviation from the shared/expected value |
| **Difference observed (unclassified)** | a difference exists and has not yet been classified as expected or as drift |
| **Effective drift (fault)** | an unexplained, unsafe deviation from expected/effective state |
| **Not applicable** | the concept does not apply to this entity type |
| **Not configured** | the capability is supported but nothing is configured yet |
| **Stale / provenance** | the evidence is aged, or its source trust is unconfirmed |

### 3.2 The severity rule, in the mockup's own words

> Red is reserved for real fault, unsafe drift, out-of-sync and failure;
> expected member differences carry no warning icon and no failure wording.

This document adopts this rule exactly as stated and writes no competing
vocabulary or rule (per this movement's own invariant).

### 3.3 Alignment with already-frozen canonical states — informative, not a re-decision

The mockup's eight terms are **UI expressions of already-frozen canonical
states**, not a new state machine (`UI2_0_BASELINE_CONTRACT.md`
`VISUAL-DESIGN-LANGUAGE` row: "are UI expressions of already-frozen rules...
and are adopted into the baseline directory (B0-8), not re-decided here").
The table below is informative cross-reference only — it neither changes
`NAVIGATION_INFORMATION_ARCHITECTURE.md` §5.4/§8's frozen mapping (Line-1's
own canonical states) nor pre-empts `C1`'s/a capability spec's own
canonical-state naming for a UI 2.0 projection table; it exists so a later
`B1`/`B2` movement implementing a UI 2.0 screen against this vocabulary can
see the semantic lineage without re-deriving it:

| Mockup term (§3.1) | Semantically aligned with (Line-1 precedent, `NAVIGATION_INFORMATION_ARCHITECTURE.md` §5.4/§8, `FA-1`…`FA-9`) |
| --- | --- |
| Aligned / healthy | `live` / config `available` / `PASS` / `READY` — "Current / available" |
| Member-specific | `MEMBER_SPECIFIC` (D-NAV14, `PO-NAV-6`) — no warning icon, no failure wording |
| Local override (intentional) | `LOCAL_OVERRIDE` — stronger attention treatment permitted |
| Difference observed (unclassified) | `DIFFERENCE_OBSERVED` — attention/warning semantics |
| Effective drift (fault) | `EFFECTIVE_DRIFT` — danger/error, error iconography |
| Not applicable | `NOT_APPLICABLE` / `NOT_A_FAILOVER_UNIT` — tab/row stays visible and selectable (D-NAV13, `FA-5`) |
| Not configured | `not_configured` / `UNPROTECTED` — actionable empty state |
| Stale / provenance | `last_known_good` ("Stale evidence", `FA-2`) and `PROVENANCE_UNVERIFIED` ("Source trust insufficient", `FA-1`/`FA-2`) — two distinct states the mockup's single term spans; a UI 2.0 screen implementing this term distinguishes them per `FA-2`'s split (stale carries an age/timestamp; source-trust-insufficient asserts no age) |

A UI 2.0 capability's own projection table and canonical states remain each
capability spec's own to define (workflow §3.1 field 5, `C4` §2.2
`evidence_shape_ref`); this table states lineage, not a binding rename of
any capability's future canonical-state column.

### 3.4 Colour/severity binding

§3.2's rule composes with `NAVIGATION_INFORMATION_ARCHITECTURE.md` D-NAV14
and `UI2_0_BASELINE_CONTRACT.md`'s `VISUAL-DESIGN-LANGUAGE` Material Design 3
system (tonal surface containers; filled/tonal/outlined/text button
hierarchy) without contradiction: colour is never the sole carrier, every
state carries an explicit textual label, and non-colour differentiation
(chip text, iconography where the rule permits it) makes every state
readable in high contrast and to a colour-blind operator — the same
requirement D-NAV14 already states, applied here to the mockup's own words.

---

## 4. Feature contribution contract

**No existing document owns this end to end. `C4` §2 (capability registry
schema) and `C2` §2.1 (job record fields) each own one half of the
mechanism; this section is the minimal new text tying the two halves
together into "how a capability adds a screen, a job type, and an alarm
source," as the workflow's own B0-8 scope line requires.**

### 4.1 The trigger: `CAP-RELEASED`

A capability contributes to the product surface only once it reaches
`CAP-RELEASED` (`UI2_0_BASELINE_CONTRACT.md` §1: `CAP-SPEC` → `CAP-OFFLINE`
→ `CAP-VALIDATED` → `CAP-RELEASED`; workflow §3.3 step d, "Integrate").
`CAP-VALIDATED` alone is not sufficient — a validated but unintegrated
capability has no screen, job type, or alarm source yet, consistent with
`C1` §3.1's "no table above is created without an owner" discipline applied
to product-surface concerns rather than schema ones.

### 4.2 What "adding a screen entry" means

A `CAP-RELEASED` capability's Integrate movement (workflow §3.3 step d)
ships:

1. its own payload/projection read path over the evidence table `C1`/`C4`
   already require the capability to declare (`evidence_shape_ref`, `C4`
   §2.2 field 5) — never a payload builder without a named owning
   capability;
2. one navigation-model row satisfying `NAVIGATION_INFORMATION_ARCHITECTURE.md`
   D-NAV6a's product-surface-eligibility test (§2.1 above), i.e. a real
   shipped read/action contract, a shell permitted to expose it, and the UI
   surface actually present;
3. rendering through §3's adopted vocabulary for any drift/comparison state
   the capability's evidence produces, and through `C3`'s visible-but-refused
   mechanism (§2.2 above) for any role-gated action the capability exposes.

No screen entry exists for a capability that has not reached `CAP-RELEASED`;
D-NAV6b's "no visual placeholder" rule already forbids advertising a
not-yet-shipped surface.

### 4.3 What "adding a job type" means

A capability *is* a job type: `C2` §2.1 fixes `job_type` / `capability_id`
as "reference into the `C4` capability registry," fixed at job creation. A
capability's Implement movement (workflow §3.3 step b) is what makes it
submittable — the capability row (`C4` §2.2) supplies `action_class` (from
`utils.action_taxonomy`, ported per `RUNTIME-DIRECTION` P-4), the closed
step sequence, and the gate-registry resolution (`C4` §3) that `C2`'s
pre-execution checks (`E7`, `C2` §6) consult before any device contact.
Adding a job type is therefore not a separate registration step: it is the
same `capability_registry` row §4.1/§4.2 already require, consumed by `C2`
as its own `job_type` reference. No second job-type table or vocabulary is
introduced by this document.

### 4.4 What "adding an alarm source" means

A capability that wants to raise alarms declares itself as a **source** in
§5's `alarm_definitions` (§5.3 below) by referencing its own
`capability_id`/`produces_facts[]` entries (`C4` §2.5) as the fact(s) an
alarm rule may threshold or derive over — the same "capability produces a
named fact once, consumed by reference" mechanism `C4` §2.5 already
specifies for cross-capability fact sharing (worked example: HA identity
consumed by inventory, readiness and backup alike). An alarm source is
never a capability re-implementing its own notification logic; it is a
registered consumer relationship against `C4`'s existing fact-production
model. §5 states the alarm side of this relationship; `C4` §2.5 remains the
sole owner of what a "fact" is and how it is produced/consumed.

### 4.5 Ordering invariant

A capability cannot be a job type before `CAP-OFFLINE` (`C2`/`C4` need a
real registry row) and cannot be a screen entry or an alarm source before
`CAP-RELEASED` (product-surface eligibility, §2.1, and D-NAV6b both require
a real shipped surface, not a plan). This mirrors, at the feature-
contribution level, the same maturity-gating discipline
`UI2_0_BASELINE_CONTRACT.md` §1 already states for capability execution in
general.

---

## 5. Alarm lifecycle contract

**Genuinely new — no existing document owns it.** Checked and confirmed
absent: `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` mentions "alarms" only
as a real-device HA readiness signal to observe (§10, "alarms / critical
pnotes: none critical on the new active"), not as a product notification
mechanism; no `docs/design/*ALARM*` or `*ALERT*` document exists in this
repository; `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` names the alarm
lifecycle as B0-8's own scope (§5, B0 row 8; §5 B2 row 3: "implements the
alarm lifecycle contract **from the baseline directory**") rather than
pointing to a prior owner. Per this movement's own invariant, this section
therefore stays **schema-level**: it names the tables, states and
boundaries an alarm pipeline needs, citing `C1`'s table-ownership/audit
model and `C2`'s job/notification boundary — it is not a full alert-engine
implementation design, and it authorizes no `ui2/` source (out of scope,
§ scope in `.nexus/approved_task.json`).

### 5.1 What this is and is not

An **alarm** is a product-raised notification derived from evidence a
capability already produced (§4.4) — never a new device-contacting
mechanism. Raising, delivering, and resolving an alarm touches no device;
only the capability whose fact triggered the alarm ever contacts a device,
under its own existing gate record. This section defines the alarm's own
lifecycle table shape and states, not a rules-authoring UI (workflow B2
item 3, "thresholds/derivations over projections," is the later movement
that implements against this contract).

### 5.2 Ownership boundary, stated once

| Concern | Owner |
| --- | --- |
| The fact an alarm rule thresholds/derives over | the producing capability, via `C4` §2.5's `produces_facts[]` — this document adds no new fact-production mechanism |
| The alarm row itself, its states, and its audit trail | this section (§5.3–§5.5), following `C1` §3.5's audit-trigger pattern and §4's data-class table |
| Outbound delivery transport (SMTP/HTTP-out, syslog, SNMP-trap-out) | `UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B2 items 4/5 — later movements; this document owns only the delivery **outcome** the alarm row records (§5.5), never a transport adapter's own implementation |
| Job/worker execution model an alarm-evaluation task runs under, if implemented as a scheduled job | `C2`'s existing state machine (§3.1) and crash-safe lease/heartbeat model (§4) — an alarm evaluator is not a new execution engine; if it runs periodically, it is a `C2` job like any other, never a second scheduler |

### 5.3 Alarm record — schema-level shape

Following `C1` §3's sketch discipline (illustrative columns that fix
load-bearing decisions; the literal Flyway SQL is a later movement's own):

```
alarm_definitions
├─ alarm_definition_id      opaque (identity law)
├─ source_capability_id     references C4's capability_registry (§4.4)
├─ source_fact_id           references the specific produces_facts[] entry thresholded/derived over
├─ rule_ref                 opaque pointer to the threshold/derivation rule (owned by the B2 alert-pipeline movement, not this contract)
├─ severity                 closed vocabulary aligned with §3's adopted rule -- red reserved for real fault only
└─ created_at / created_by_actor_fingerprint   C1 §3.5 audit-trigger pattern, C3 actor shape

alarm_instances
├─ alarm_instance_id        opaque
├─ alarm_definition_id      references alarm_definitions
├─ target_ref               opaque device_id/entity_id this instance concerns
├─ dedup_key                deterministic function of (alarm_definition_id, target_ref, causal_fact_version) -- see 5.4
├─ state                    OPEN | ACKNOWLEDGED | SILENCED | RESOLVED  (5.4)
├─ raised_at / resolved_at / silenced_until
├─ causal_provenance_ref    references the C1 provenance_records row (or set) that proved the triggering fact -- never a raw fragment, C1 §5
└─ audit linkage            C1-1 mechanism (trg_audit_capture), identical discipline to every other mutation-bearing table

alarm_delivery_attempts
├─ delivery_attempt_id      opaque
├─ alarm_instance_id        references alarm_instances
├─ channel_ref              opaque pointer to the delivery channel (SMTP/HTTP-out/syslog/SNMP-trap-out -- B2 items 4/5's own row shape)
├─ outcome                  DELIVERED | DELIVERY_FAILED | DELIVERY_UNKNOWN
└─ attempted_at
```

`alarm_instances` and `alarm_delivery_attempts` are audit-linked exactly as
`C1` §3.5 requires of any mutation-bearing table (`trg_audit_capture`,
one trigger per table, `SET LOCAL app.actor_fingerprint`/`app.action_id`
discipline) — the alarm pipeline introduces no exception to `C1-1`'s
mechanism, the same way `C3` §3.5 extended it to `sessions`/`role_bindings`
rather than inventing a parallel audit path.

### 5.4 State machine — outbox, de-duplication, resolve/silence, causality

**Outbox.** `alarm_instances` is itself the transactional outbox: an alarm
is raised in the same database transaction as the evaluation that derived
it (mirroring `C1-1`'s "a mutation and its audit row commit atomically or
neither does"), and `alarm_delivery_attempts` rows are appended
asynchronously against an already-committed `alarm_instances` row — never
the reverse. This is the same separation `C2` already uses between a job's
own state (committed at each transition) and its step-attempt records
(appended as execution proceeds, §5 of `C2`): the alarm's existence is never
contingent on a delivery channel succeeding.

**De-duplication.** `dedup_key` (a deterministic function of the
definition, the target, and the causal fact's version/provenance) is the
mechanism: a repeated evaluation that reproduces the same causal fact
against the same target and an already-`OPEN` (or `ACKNOWLEDGED`) instance
updates that instance's `last_confirmed_at`-style bookkeeping rather than
creating a second row — the same duplicate-avoidance principle `C2` §2.3
uses for a job's `idempotency_key`, applied to alarm evaluation instead of
job submission. A causal fact that changes version (the underlying evidence
was re-derived and differs) is a new `dedup_key`, never silently folded
into the prior instance — the same "never collapse two invariants that are
usually true together" discipline `AGENTS.md` states for evidence, applied
here to "same alarm" vs. "same target, new evidence."

**Resolve / silence.**

| Transition | Meaning | Causer |
| --- | --- | --- |
| `(none)` → `OPEN` | evaluation derived the triggering fact fresh | evaluation task (system actor) |
| `OPEN` → `ACKNOWLEDGED` | an operator has seen the alarm and is tracking it; the underlying condition may still hold | human, audited (same `actor_fingerprint`/`action_id` discipline as any other mutation) |
| `OPEN`/`ACKNOWLEDGED` → `SILENCED` | an operator has suppressed delivery for a bounded window (`silenced_until`); the alarm row is not resolved and re-evaluation still runs | human, audited, bounded — never an indefinite silence with no `silenced_until` |
| `SILENCED` → `OPEN` | `silenced_until` elapsed and the condition still holds on re-evaluation | evaluation task (system actor) |
| `OPEN`/`ACKNOWLEDGED`/`SILENCED` → `RESOLVED` | re-evaluation no longer derives the triggering fact (the underlying condition cleared) | evaluation task (system actor) — never a human declaring an alarm resolved while the condition still evaluates true; a human may only acknowledge or silence, never force-resolve a condition that still holds |

`RESOLVED` is terminal for that `alarm_instances` row; a condition
recurring after resolution is a **new** row under a fresh `dedup_key`
(new causal-fact version), never a reopened row — the same closed-
transitions-graph discipline `C2` §3.2 and `C3` §3.3 both already use for
their own state machines (jobs never leave a terminal state; sessions never
leave `SUPERSEDED`/`EXPIRED`/`REVOKED`).

**Causality limits.** An alarm instance's `causal_provenance_ref` links to
the exact `provenance_records` row(s) (`C1` §5) that proved the triggering
fact — never a raw device fragment (`C1` §4's "evidence (projection)" data
class, `discard_raw` semantics, workflow §3.5). This bounds what an alarm
can claim: an alarm asserts what the linked provenance can prove, and
nothing else — the same evidence-of-truth discipline workflow §3.1 field 4
already requires of every capability parser ("a derived fact links to the
raw fragment that proved it, or it is not stored"), applied to alarm
causation instead of parser output. An alarm evaluation that cannot
establish a definite causal fact (`UNKNOWN`/`INSUFFICIENT_EVIDENCE`, per
`AGENTS.md` UNKNOWN/fail-closed law) does not raise an alarm claiming
certainty it does not have; it is the evaluated capability's own evidence
state (§3's vocabulary) that surfaces the gap, not a fabricated alarm.

### 5.5 Delivery failure

`alarm_delivery_attempts.outcome` follows the same three-valued discipline
`C2` §3.5 already established for job execution (`COMPLETED`/`FAILED`/
`OUTCOME_UNKNOWN`), restated for delivery instead of device execution:

- `DELIVERED` — the channel confirmed receipt (transport-specific: SMTP
  accept, HTTP 2xx, syslog write success).
- `DELIVERY_FAILED` — the channel returned a definite failure; the alarm
  instance's own state is **unaffected** — a failed delivery never silently
  resolves or silences the alarm it failed to deliver, and a retry is a new
  `alarm_delivery_attempts` row, never a mutation of the failed one (the
  same append-only-attempt discipline `C2` §5.1 uses for step-attempt
  records).
- `DELIVERY_UNKNOWN` — the delivery request may have reached the channel
  but confirmation could not be obtained (mirrors `JOB-UNCERTAIN-OUTCOME`,
  `UI2_0_BASELINE_CONTRACT.md` §2: "a write was sent and its result could
  not be recorded" → `OUTCOME_UNKNOWN`, no automatic second write). The
  same rule applies here at the delivery layer: no automatic second attempt
  is fired blind; the retry path (if any) is an explicit, later-scoped
  decision, not assumed by this schema.

Delivery is decoupled from the alarm's own lifecycle (§5.4) by
construction: an alarm can be `OPEN` with zero, one, or many delivery
attempts across zero or more channels, and no delivery outcome ever drives
an `alarm_instances.state` transition — only re-evaluation of the causal
fact does (§5.4's table).

### 5.6 What this section deliberately does not specify

Threshold/derivation rule authoring, the rule language or UI, the exact
channel configuration schema, retry/backoff policy numbers, and escalation
policy are **not** specified here — they belong to the B2 alert-pipeline
movement (workflow §5 B2 item 3) and the B2 notification-channel movements
(items 4/5), which implement *against* this schema-level contract rather
than re-deciding its shape. This keeps the alarm lifecycle contract at the
same altitude as `C1`'s own table sketches: load-bearing shape fixed, exact
SQL and rule content left to the movement that ships them.

---

## 6. Log/audit data classes

**Owner: `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §4 ("Data
classes and retention per class"), directly and completely.** No new text
is written here beyond this citation, per this movement's own invariant
("audit data classes are C1 section 4's; do not repeat, cite").

`C1` §4 names and completes five data classes — **Job log**, **Audit**,
**Evidence (projection)**, **Encrypted artefact**, **Sanitized fragment** —
each with its owner table(s), a retention posture, and its
`PRIVACY_AND_DATA_HANDLING.md` `CLASS 0/1/2` tier (explicitly an orthogonal
axis from `utils.action_taxonomy`'s unrelated `CLASS_0`.. namespace,
`AGENTS.md` "Privacy and DLP"). §5's alarm records (§5.3 above) fall under
`C1` §4's **Audit** class for their state-transition trail (via the same
`C1-1` trigger mechanism) and under **Job log**-adjacent bookkeeping for
`alarm_delivery_attempts`' own execution narrative — no sixth data class is
introduced by this document; the alarm tables are new *rows in existing
classes*, not a new class.

---

## 7. SNMP status exposure

**Owner: `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §5 B2 item 5b for the
disposition; this section restates the read-vs-poll distinction the
workflow already states, schema-level, per this movement's own invariant
("restate this distinction from workflow section 5 B2-5b clearly").**

### 7.1 The distinction, restated

SNMP status exposure is the product **answering** NMS reads about its own
and its devices' already-recorded state (workflow §5 B2 item 5b: "the
product answers NMS reads about its own and its devices' recorded state...
no device polling implied"). This is explicitly **not**:

- **Device polling.** UI 2.0 does not initiate SNMP GETs against managed
  devices to derive their state. Every fact SNMP status exposure answers
  with is already produced by an existing capability under its own
  network-device command-gate record (§4's feature-contribution model,
  `C4` §2.5's `produces_facts[]`) — SNMP status exposure is a **read
  projection over already-collected evidence**, the same posture Line-1's
  own product-maturity axis already applies to every read surface
  (`AI_START_HERE.md`: "collects and reconciles... publishes"). It adds no
  new device contact class and requires no network-device command-gate
  entry of its own, because it issues no device command.
- **SNMP-trap-out.** Workflow §5 B2 item 5 ("Syslog + SNMP-trap-out") is a
  separate, **outbound-only** notification channel — the product pushing an
  SNMP trap to an NMS when an alarm (§5 above) fires. SNMP status exposure
  (item 5b) is the reverse direction and a separate contract: the NMS
  **pulls** a status read from the product; the product never initiates
  contact toward the NMS under this capability. The two share the protocol
  name and nothing else in this taxonomy — a trap-out delivery is one of
  §5.5's `alarm_delivery_attempts` channels; a status-exposure read is
  answered by an agent role the product runs, with no alarm instance
  necessarily involved at all.

### 7.2 Schema-level shape

SNMP status exposure is an **SNMP agent role** the UI 2.0 service exposes,
answering reads (SNMP GET/GETNEXT/GETBULK semantics, protocol detail
deferred to the B2-5b movement itself) over an MIB surface backed by
existing projection tables (`C1` §3.1's `cp_inventory_projection` and every
later capability's own evidence table) and, where applicable, `alarm_
instances`' current `state` (§5.3) — never a new evidence-producing
mechanism, never a write path, and never console-submittable in the sense
`utils.action_taxonomy` uses for device-facing write classes (this is a
read-class product-facing agent, not a device action at all, so the
taxonomy's five classes do not directly apply to it the way they apply to a
capability's own steps — a distinction B2-5b's own contract states
precisely, not this document).

### 7.3 What this document does not decide

Community-string vs. SNMPv3 posture, exact MIB design, which projection
fields are exposed, and rate/access controls on the agent are **not**
decided here — they are B2-5b's own contract, which this section names as
the owner of everything beyond the read-vs-poll/read-vs-trap-out
distinction restated above.

---

## 8. Acceptance criteria for the B1/B2 movements this catalogue feeds

At least eight, each independently testable, for the four movements this
catalogue names as its consumers: `B1-9` device workspace, `B2-1` capability
registry UI, `B2-3` alert pipeline, `B2-5b` SNMP status exposure.

1. **Navigation surface eligibility (`B1-9`).** A device workspace tab for a
   capability whose entity-type applicability (§2.1's `P2`) does not match
   the selected entity renders visible, selectable, and states
   `NOT_APPLICABLE` naming which entity types support it; it is never
   omitted for this reason (D-NAV13, adopted §2.1).
2. **Navigation surface omission (`B1-9`).** A capability with no shipped
   product surface in the current build is omitted from the workspace
   entirely (P1 failure, the only omission case, §2.1); a test asserts no
   tab renders for a capability whose registry row lacks a shipped payload/
   API contract.
3. **RBAC visible-but-refused (`B1-9`, `B2-1`).** An operator whose role
   token is not bound to the group a given `action_id` requires sees that
   action rendered, disabled, and labelled with `C3`'s exact `reason_code`
   (`actor_not_in_required_group`); the response is never a `200` with the
   action silently absent from the payload (§2.2).
4. **Screen-state vocabulary fidelity (`B1-9`, `B2-1`).** A `MEMBER_SPECIFIC`
   -aligned row (§3.3) renders with no warning icon and no failure wording;
   an `EFFECTIVE_DRIFT`-aligned row renders with error iconography and the
   danger treatment; a test asserts the two are visually and textually
   distinguishable and that colour is never the only differentiator (§3.2/
   §3.4).
5. **Feature-contribution ordering (`B2-1`).** A capability at
   `CAP-VALIDATED` but not yet `CAP-RELEASED` contributes no screen entry,
   no alarm source, and remains submittable as a job type only through its
   already-existing `C4` registry row (§4.1/§4.5); a test asserts the
   capability registry UI shows its maturity state accurately and does not
   render a screen/alarm-source affordance for it.
6. **Capability-registry UI shows gate coverage and `D1`–`D7` state
   (`B2-1`).** For a selected capability and target, the UI shows the
   capability's own maturity state, its gate-reference resolution (`C4` §3)
   and its `D1`–`D7` capability-state per device (workflow §5 B2 item 1);
   a test asserts an `UNKNOWN` gate reference renders as blocking execution
   without blocking the spec/offline view (`C4` §3.5, adopted).
7. **Alarm de-duplication (`B2-3`).** Two evaluation runs that derive the
   identical causal fact for the identical target against an already-`OPEN`
   alarm instance produce **no** second `alarm_instances` row (same
   `dedup_key`); a test asserts exactly one row exists after both runs
   (§5.4).
8. **Alarm resolve is evaluation-driven, not human-forced (`B2-3`).** An
   `ACKNOWLEDGED` alarm whose underlying causal fact still evaluates true on
   re-evaluation cannot be transitioned to `RESOLVED` by a human action; only
   a re-evaluation that no longer derives the triggering fact produces the
   `RESOLVED` transition (§5.4); a test asserts an attempted human
   force-resolve on a still-true condition is refused.
9. **Alarm delivery failure never mutates alarm state (`B2-3`).** A
   `DELIVERY_FAILED` or `DELIVERY_UNKNOWN` outcome on an `alarm_delivery_
   attempts` row leaves the parent `alarm_instances.state` unchanged; a test
   asserts the alarm instance's state before and after a simulated delivery
   failure is identical, and that a retry produces a new attempt row, never
   a mutation of the failed one (§5.5).
10. **SNMP status exposure issues no device contact (`B2-5b`).** A test
    asserts the SNMP agent's response path touches only existing projection
    tables and issues zero calls into any transport adapter (`C4` §5) or
    collection-engine step executor (`C2` §5) — proving the read-vs-poll
    distinction (§7.1) holds structurally, not only by documentation.
11. **SNMP status exposure is distinct from SNMP-trap-out (`B2-5b`).** A
    test asserts the SNMP agent role (inbound reads, §7.2) and the
    SNMP-trap-out delivery channel (outbound, one of §5.5's `channel_ref`
    values) are implemented as separate components with no shared
    initiate-contact code path between them.

---

## 9. Contradictions and open items for the Product Owner

**Contradictions with FROZEN authority: none found.** Documents checked
while writing this catalogue: `AGENTS.md` (identity law, evidence laws,
UNKNOWN/fail-closed law, privacy/DLP), `docs/design/UI2_0_BASELINE_CONTRACT.md`
(FROZEN — PRODUCT OWNER APPROVED, 2026-09-09, including the `VISUAL-DESIGN-
LANGUAGE` row and acceptance sentence A-1), `docs/design/UI2_0_DEVELOPMENT_
WORKFLOW.md` (BASELINE, revision 2, §5 B0-8's own scope line and §5 B2 items
3/5/5b), `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` (FROZEN,
revision 3, amended by `M3`'s `FA-1`…`FA-9`), `docs/design/CAPABILITY_STATE_
VOCABULARY_AND_PRESENTATION.md` (FROZEN — PRODUCT OWNER APPROVED,
2026-09-06 — checked and not engaged: its `D1`–`D7` capability-state axis is
a different question from this document's §3 screen-state vocabulary, and
this document neither cites it as authority for §3 nor redefines it),
`docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md`, `docs/design/UI2_0_C2_
JOB_EXECUTION_CONTRACT.md`, `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_
CONTRACT.md`, `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_
CONTRACT.md`, `docs/design/UI2_0_C6_CAPABILITY_EXTRACTION_CONTRACT.md`
(checked specifically for an existing alarm/alert design — none found),
`docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md`,
`docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` (checked for "alarm" — only a
real-device HA readiness signal, not a product notification mechanism; no
contradiction), `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md`.
This document reopens no PO-reserved or already-frozen decision: `VISUAL-
DESIGN-LANGUAGE`'s mockup vocabulary and severity rule are adopted verbatim
(§3), not re-decided; `DIRECTORY-POSTURE` (D-6) and every other `C3`-owned
Phase 0 ruling are cited, not touched; the six-root Line-1 navigation
baseline (`PO-NAV-8`) is explicitly *not* carried over as UI 2.0's own root
set (§2.1) — a scope clarification, not a re-decision of Line-1's own frozen
architecture, which this document leaves entirely unchanged.

**Open items, not contradictions:**

1. **UI 2.0's own root set is not fixed by this document.** §2.1 adopts
   `NAVIGATION_INFORMATION_ARCHITECTURE.md`'s *principles* but explicitly
   does not carry over Line-1's six-root baseline as UI 2.0's own — that is
   a `B1`/`B2` implementation decision against the adopted principles, not
   yet made. Flagged so a later movement does not assume the root set is
   pre-decided by silence.
2. **Alarm severity vocabulary's exact enum values.** §5.3's `severity`
   column is stated as "aligned with §3's adopted rule" but its closed
   enumeration (how many levels, exact names) is left to the `B2-3` alert-
   pipeline movement, consistent with this document staying schema-level
   for the alarm lifecycle (§5.6).
3. **`Stale / provenance`'s two-state split (§3.3).** The mockup's single
   term spans two distinct frozen states (`last_known_good` vs.
   `PROVENANCE_UNVERIFIED`, `FA-2`). This document states the split as
   informative guidance for whichever movement implements the term in a
   UI 2.0 screen; it is not itself a re-decision of `FA-2`, which remains
   Line-1's own frozen correction.
4. **SNMP status exposure's relationship to `utils.action_taxonomy`
   (§7.2).** This document states the taxonomy's five action classes do not
   directly apply to a read-only, non-device-contacting product-facing
   agent, but leaves the precise framing (e.g., whether it is classed as an
   implicit "class 0" read for taxonomy-completeness purposes, or sits
   outside the taxonomy's device-action scope entirely) to `B2-5b`'s own
   contract, since this document's invariant is to state the read-vs-poll
   distinction, not to extend or amend `utils.action_taxonomy` itself.

---

## 10. Cross-references

- `docs/design/UI2_0_BASELINE_CONTRACT.md` (FROZEN — PRODUCT OWNER APPROVED,
  2026-09-09) — direction, Phase 0 decisions, `VISUAL-DESIGN-LANGUAGE` row,
  acceptance sentence A-1.
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` (BASELINE, revision 2) — §5
  B0-8 (this document's own scope line), §5 B1 step 9, §5 B2 items 1/3/4/5/5b.
- `docs/design/NAVIGATION_INFORMATION_ARCHITECTURE.md` (FROZEN, revision 3,
  `M3` `FA-1`…`FA-9`) — §2.1/§2.2's principle source; D-NAV1…D-NAV14; §5.4/§8
  screen-state precedent (§3.3's informative cross-reference).
- `docs/design/CAPABILITY_STATE_VOCABULARY_AND_PRESENTATION.md` (FROZEN —
  PRODUCT OWNER APPROVED, 2026-09-06) — the separate `D1`–`D7`
  capability-state axis; checked, not engaged (§9).
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` — §3.5 (`C1-1` audit
  mechanism, extended by §5.3 above the same way `C3` extended it), §4 (data
  classes, cited directly in §6), §5 (provenance records, cited in §5.4).
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` — §2.1 (`job_type`, cited
  in §4.3), §2.3 (idempotency, the model §5.4's de-duplication mirrors),
  §3.5 (`OUTCOME_UNKNOWN`, the model §5.5's `DELIVERY_UNKNOWN` mirrors).
- `docs/design/UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` — §5 (RBAC
  visible-but-refused, cited in §2.2), §6 (`E1`–`E7` gate chain).
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` —
  §2.2 (capability row), §2.5 (shared facts, cited in §4.4), §3 (gate-
  registry resolution, cited in §4.3/§8 criterion 6).
- `docs/design/UI2_0_C6_CAPABILITY_EXTRACTION_CONTRACT.md` — checked for an
  existing alarm/alert owner (§9); none found.
- `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` — no
  direct dependency; checked for contradiction (§9), none found.
- `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §10 — checked for an
  existing "alarm" usage (device HA signal, not a product notification
  mechanism); no contradiction.
- `AGENTS.md` — identity law, evidence laws, UNKNOWN/fail-closed law,
  privacy/DLP (§4's unrelated `CLASS_0`.. namespace warning, applied in §6).
- `utils/action_taxonomy.py` — the five action classes; §7.2 states why they
  do not directly frame SNMP status exposure.
