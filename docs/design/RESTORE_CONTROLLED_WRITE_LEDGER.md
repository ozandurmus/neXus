# Controlled restore-write admission contract — design

**Status: FROZEN — PRODUCT OWNER APPROVED, 2026-09-10.** Council disclosure:
two same-model-family, fresh-context seats (Security Reviewer and Senior
Python Architect) reviewed independently; this was not a cross-model review.
Both returned `FREEZE WITH CHANGES`, and relay `NXS-LOCAL-0060` seq 21 records
that their required changes were satisfied before Product Owner approval. This
document specifies no code and edits no other file. It is the admission
contract item 3 of `docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md`
§5's follow-up amendment list, prepared after the Product Owner selected
**Option A** (a new, narrowly-scoped `utils/action_taxonomy.py` class between
`CLASS_1_RECOVERY_WRITE` and `CLASS_2_OPERATIONAL_STATE_CHANGE`, plus a new
`C4` device-directed restore step kind). Companion documents: the D1 decision
document (recommendation, §4's provenance argument, the full options
comparison), `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md`
(FROZEN) §5.2–§5.7 and §6.2 (restore plan compilation, preconditions,
`OUTCOME_UNKNOWN` handling, per-operation approval — all carried forward
unchanged here, never redefined), `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_
GATE_RESOLUTION_CONTRACT.md` (FROZEN) §2.3/§3.3 (closed step-kind set,
gate-resolution rule), and `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md`
(the `CLASS_1_RECOVERY_WRITE` precedent this document's fail-closed posture
and evidence-plane placement are modeled on, but not copied uncritically —
§1 below states exactly why the two ledgers differ).

**This document does not edit `utils/action_taxonomy.py`, `C4`, `C7`, any
FROZEN document, any test file, or any `ui2/`/Python product source.** It is
read alongside `docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md`,
which carries the literal proposed edits (steps 1, 2, 4, 5 of the D1 follow-up
list) that a successor implementation movement would apply once this
document and that bundle are reviewed and, where GOV.PO.1 §7 requires it,
taken through council. **That required bounded council review was completed
before this freeze, as recorded in relay `NXS-LOCAL-0060` seq 21; it was
invoked from the Product Owner governance path**
(`docs/design/GOV_PO_ROLE_MIGRATION.md` §7, council trigger (b): "a freeze
candidate introducing a security, identity, credential, storage-schema or
write boundary" — this is exactly that kind of freeze candidate).

**Revision note (this pass).** Consolidates and revises the initial DRAFT
against two Product Owner decisions recorded on the canonical
`relay/NXS-LOCAL-0060-ui2-d1-device-write-class-decision.json` relay and
council findings raised against it. Both decisions are **binding inputs**
to this revision, not proposals this document argues for or against:

- **seq 5 — `ActionClass.level=1.5`, non-renumbering representation**, with
  an explicit requirement that the successor movement inventory every
  `level` consumer and wire field. §3.2 below (new) carries that inventory.
- **seq 7 — narrowly restore-scoped `SIGNED_OFF` eligibility, with
  independent `C2` runtime admission**: "`C4` static sign-off and `C2`
  runtime admission are separate predicates." This document's §3 (the
  admission model) is revised below to state that separation explicitly —
  the earlier draft's three-leg gate was, on re-reading, ambiguous about
  whether it was one blended condition or two independent ones; it is
  **two independent predicates**, per seq 7, not one.

Every other council finding this pass resolves (ledger identity scope,
reconciliation representation, the `C2` admission/retry row, the numeric
freshness bound, the command-gate registration path, `C4`'s "five classes"
wording) is addressed section-by-section below and cross-indexed in
`docs/design/UI2_0_D1_OPTION_A_CONSOLIDATED_REVIEW.md`, which is the primary
review entry point for this pass — read it first; it names exactly what
changed in this document and in the amendment bundle, and why.

**Second revision note (this pass, relay seq 9 + PO freshness
clarification).** Two more things changed after the revision note above
was written:

- **Relay seq 9**: the PO selected the recommended `C2`/`C7` integration
  path — fold the ledger's admission facts into `C7` §5.3's existing
  checks 1/2, **do not** retain the standalone `C2` §6 check 7 the first
  revision proposed. §3.1 below is rewritten accordingly.
- **PO clarification (chat, superseding the first revision's own
  proposal)**: **the proposed 15-minute connectivity freshness bound is
  explicitly rejected.** §3.3 below no longer proposes any fixed number;
  it instead specifies freshness as **configurable policy**, separates
  background polling cadence from claim-time restore safety, and names
  two alternative evidence sources (mandatory active probe; optional
  cached telemetry/SNMP under strict, explicitly-configured conditions,
  with a hard requirement that SNMP-style reachability is never treated
  as proof of write-channel readiness absent an established vendor-
  specific contract). A short, explicitly non-authoritative research note
  on public BackBox documentation is included per instruction, at §3.3.2.

**Third revision note (this pass, relay seq 13 + further council
findings).** Relay seq 13 (`RELAY_DECISION`, PO) **narrowly supersedes
seq 9** for one specific fact this document had folded into `C7` §5.3's
compile-time battery: *"add an explicit restore-ledger reconciliation-
pending check at C2 claim time, with fail-closed behavior... This narrowly
supersedes the prior no-standalone-check-7 preference only for the
claim-time safety fact that cannot remain compile-time-only... static `C4`
sign-off remains independent from runtime `C2` admission."* §3.1 is
rewritten below to state, explicitly, the distinction between a
compile-time early-refusal check and a claim-time authoritative check —
the reconciliation-pending fact now has **both**, because a fact this
consequential must be re-verified fresh at the moment of claim, not
trusted from however long ago the `restore_plan` was compiled. New §3.1.1
defines the ledger's key/scope including `VSX`/ClusterXL implications; new
§3.1.2 defines `restore_run`/step cardinality; new §3.1.3 defines retry
behavior for this specific check, distinguished from the class's own
never-auto-retry rule (§3.4, unaffected). `C4`'s static sign-off predicate
(§3.0) is **not reopened** by this decision — it remains fully independent
of runtime admission, exactly as seq 7 established. Further council
findings addressed this pass, per instruction: the `C7` check mapping is
made fully explicit (§3.1); compile-time vs. claim-time re-evaluation is
its own stated distinction (§3.1); any future cached-telemetry semantic-
sufficiency contract must itself pass `GOV_PO_ROLE_MIGRATION.md` §7
trigger (b) council review before being referenced (§3.3.1, revised); the
append-only decision is restated unambiguously in §6 (previously it still
carried stale "one narrow exception" language from an earlier draft that
§4.0 had already superseded); and cached-telemetry sufficiency contracts
are now explicitly keyed per vendor/platform (§3.3.1), not a single global
flag.

**Fourth revision note (this pass, PO review decision on VSX scoping).**
The Product Owner reviewed this document against the repository's own
actual schemas and corrected a factual error in the prior pass: **VSX
virtual-system-scoped restore was framed as an already-live targeting
option** needing a conservative-default admission-scope choice, when in
fact **no current restore capability, and no current CP Gaia backup
capability, targets an individual virtual system at all** —
`BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 7/§7.7 (FROZEN) state plainly
that Check Point Gaia backup collection is categorically excluded from
VSX virtual-system contexts, and `C7` §5.2's `restore_plan` schema carries
no `virtual_system_ref` field at all. §3.1.1 is rewritten to state the
scope this repository's evidence actually supports: physical
`device_id`/`endpoint_id` only; VSX-context restore is explicitly
unsupported and out of scope, requiring its own future, separate
vendor/platform-and-target-scope contract, not a parameter of this
ledger. Relay seq 17 subsequently clarifies that ClusterXL member identity
separation is not restore-safety evidence: member restore is unsupported
until its own cross-member safety contract exists. This pass also adds
§3.6, the explicit `C7` §5.3
companion amendment the Product Owner requested: proposed replacement
text resolving the direct contradiction between `C7` §5.3's own frozen
note ("check 4 is `NOT_APPLICABLE` for a restore job," written before
this class existed) and this document's own §3.1.0 (which repurposes
check 4's slot for restore's reconciliation-pending check) — the
proposed text states plainly that class 1 (backup) keeps the existing
`RB.x` cadence-ledger interpretation of check 4 unchanged, class 1.5
(restore) reads check 4 as this document's own reconciliation-pending
check, and every other class remains `NOT_APPLICABLE`.

**Fifth revision note (this pass, council-informed PO decision, relay
seq 17).** This pass narrows the open item this document's §9 previously
left unresolved: which existing `C7` §5.3 check absorbs the compile-time,
non-authoritative "no unreconciled prior restore-write outcome" pass. Two
independent, fresh-context council seats (a Security Reviewer and a
Senior Python Architect) reviewed folding it into check 2 or check 5 and
**both rejected folding into either**, citing the same risk: mixing two
independently-failing predicates under one recorded result loses which
one actually failed, inconsistent with how this document's own claim-time
extension of `C2` §6 check 4 was already done (independently named and
tracked, never blended). The Product Owner accepted this input and
decided: add a **new** `C7` §5.3 check — check 7 — appended after check 6,
never reordering or renumbering checks 1–6. This is recorded as
**council-informed, not council-decided** — the seats named the risk, the
Product Owner made the placement call. §3.1's fact-mapping table and §3.1.0
are revised to reflect check 7 instead of a check-2 fold; new §3.7 gives
the literal proposed `C7` §5.3 table addition and states the
minimum-disruption append-only positioning explicitly.

---

## 1. Why this exists, and why it is not `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` reused

`RECOVERY_OPERATIONAL_WRITE_LEDGER.md` (RB.3b) exists to enforce a **cadence
ceiling** on a recurring, bounded-resource `CLASS_1_RECOVERY_WRITE` operation
(`add backup local`, at most once per endpoint per 24 h). `C7` §5.3's own
note states plainly that this model is **`NOT_APPLICABLE`** to restore:
*"restore is not recurring and is not admitted by cadence at all"* — its own
mutual-exclusion mechanism is `C7` §5.3 check 5, `C2`'s existing per-target
job admission (no concurrent `REQUESTED`/`CLAIMED`/`EXECUTING` job against
the same `device_id`).

What restore's own write genuinely lacks an admission mechanism for is
**not** cadence — it is:

1. **A per-operation, single-use approval boundary** (`C7` §6.2's
   `restore_approval` record already specifies this record's *shape*; this
   document specifies how the new action class's `permitted` gate *consults*
   it, which `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` never had to do because
   `CLASS_1_RECOVERY_WRITE` carries no per-operation approval at all — its
   admission is entirely cadence/credential/allowlist-based, §5 of that
   document).
2. **A reconciliation-pending block**, distinct from a cadence window: `C7`
   §5.7 states a restore `OUTCOME_UNKNOWN` job "closes only via `RECONCILED`
   ... no exception for restore," and that a **new** restore attempt after
   reconciliation is a **new** plan/job, never a retried one, gated on a
   fresh approval **and** a `job_reconciliation` record referencing the
   prior attempt. `C2`'s own per-target job admission (§5.3 check 5) blocks
   a second *concurrent* job; it does **not** by itself block a second
   *sequential* restore attempt against the same target once the first job
   has left `EXECUTING` and landed in `OUTCOME_UNKNOWN` — nothing today
   prevents a fresh `RestorePlan`/job request against that target before
   reconciliation happens, because compilation (`C7` §5.3) checks live job
   state, not terminal-but-unreconciled state. This is the genuine new gap
   this document closes: **the new class's admission gate must itself
   refuse to execute a restore-write step against a target that has an
   unreconciled prior restore-write outcome**, independent of and prior to
   `C2`'s own claim-time checks.
3. **A durable, cross-container record of restore-write attempts** for
   audit and for the reconciliation check above to consult — mirroring why
   `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §1 needed durable state (a
   process-local, in-memory coordinator "does not prevent a second backup
   ten minutes after the first, and a process/container restart discards
   whatever it knew") — the same argument applies here: a container restart
   between a restore's `OUTCOME_UNKNOWN` landing and its reconciliation must
   not lose the fact that this target has an unreconciled restore-write
   outstanding.

Cadence (RB.x's problem) and reconciliation-pendency (this document's
problem) are different admission questions with different failure
consequences, which is exactly why `AGENTS.md`'s evidence laws warn against
collapsing distinct invariants "merely because they are usually true
together" — this document does not reuse `RECOVERY_OPERATIONAL_WRITE_
LEDGER.md`'s table/module wholesale; it defines a sibling with a materially
different admission rule, built on the same fail-closed and evidence-plane
placement principles.

## 2. Placement — evidence plane, per `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §2's reasoning, restated for this class

Same placement, same three reasons, restated because they hold identically
here: this ledger holds **derived operational metadata** only (`device_id`,
`restore_run_id`, `restore_approval_id`, timestamps, outcome, reconciliation
status — never a manifest path, never artefact bytes, never a credential);
it must be readable even when the recovery/artefact volume is unavailable;
and it must be shared across containers when
`SECURITYEXPERT_EVIDENCE_BACKEND=postgres` is configured, for the same
cross-container race reason RB.3b §7 states. It is **not** on the recovery
plane: nothing here is encrypted or RMA-grade, and a reader learns only "a
restore-write attempt against device X reached state Y at time T," which is
already implied by the `C2` `jobs`/`restore_run` rows this ledger derives
its entries from.

## 3. The admission model — two independent predicates (revised per relay seq 7)

### 3.0 Static sign-off and runtime admission are separate predicates, not one blended gate (relay seq 13 reaffirms, does not reopen, this section)

The initial DRAFT of this document described a "three-leg gate" without
being explicit about whether satisfying it was the same question as the
capability row being `SIGNED_OFF` in `C4`'s registry. Relay seq 7 resolves
that ambiguity directly: **"`C4` static sign-off and `C2` runtime admission
are separate predicates."** This document adopts that split exactly:

- **Predicate 1 — `C4` static sign-off (spec/registry-load time, no device
  contact).** Whether a `controlled-restore-write` capability row's gate
  entry **may exist as `SIGNED_OFF` at all** — a narrow, restore-scoped
  question the amendment bundle's Step 2 (§3.3 step 7 restatement) answers,
  never this document's concern. This predicate governs whether the
  *command* is authorized to be in the registry; it says nothing about any
  one execution.
- **Predicate 2 — `C2` runtime admission (claim time, immediately before
  device contact).** Whether **this specific job**, against **this
  specific target**, **right now**, may proceed — the question this
  document answers. It is evaluated **every time**, independent of
  predicate 1 having already been satisfied once at registry-load time; a
  `SIGNED_OFF` gate row does not carry any of predicate 2's checks forward,
  and predicate 2 passing today says nothing about tomorrow's claim.

**Relay seq 13 (this pass) only revises *where within predicate 2* the
reconciliation-pending fact is checked** (§3.1: an explicit `C2` §6
check-4-slot check, not a compile-time-only fold) — it does not touch,
reopen, or narrow this section's own two-predicate split, which is exactly
what seq 13's own text reaffirms ("static `C4` sign-off remains independent
from runtime `C2` admission"). Predicate 1 and predicate 2 remain as
described above, unconditionally.

A `controlled-restore-write` capability that is `SIGNED_OFF` (predicate 1)
but whose target has an unreconciled prior outcome (predicate 2's
authoritative claim-time check, `C2` §6 check 4's slot per §3.1 below) is
refused at claim — the row's existence in the registry never substitutes
for this document's own runtime check, and vice versa: a target with no
unreconciled history but a capability row that is not (or not yet)
`SIGNED_OFF` is refused by `C4` §3.5's execution-eligible view before this
document's checks are ever reached. Neither predicate is sufficient alone;
both are necessary, evaluated by different documents, at different times,
never merged into one combined score.


### 3.1 Predicate 2 in full — compile-time early refusal (`C7` §5.3) plus an explicit claim-time check (`C2` §6, revised per relay seq 13)

**Revision note.** The initial DRAFT of this section proposed a brand-new,
standalone `C2` §6 check (row 7) to carry predicate 2's facts. Relay seq 9
superseded that with a fold into `C7` §5.3's existing checks 1/2. Relay
seq 13 **narrowly supersedes seq 9 in turn**, for exactly one fact: the
reconciliation-pending check cannot live at compile time only, because
compile time and claim time can be arbitrarily far apart, and a **new**
unreconciled outcome can appear on the target in that gap (a second,
unrelated restore attempt against the same device landing in
`OUTCOME_UNKNOWN` between this plan's compilation and its claim). A
compile-time-only check would silently trust stale evidence at exactly
the moment this document's own fail-closed posture (§5) says "cannot tell"
must never be read as "safe to proceed."

#### 3.1.0 Compile-time vs. claim-time re-evaluation, stated explicitly (addresses a council finding)

`C7` §5.3's own text already establishes the general principle this
document now applies concretely: *"Checks 1–6 above are the compile-time
battery; `C2` §6's own six checks... still run again at claim time,
unchanged, exactly as they do for a backup job — compile-time checking
does not replace claim-time re-checking, it adds an earlier, human-facing
refusal point."* Two distinct properties follow, and this document treats
them as genuinely distinct, not interchangeable:

- **Compile-time (`C7` §5.3, when a `restore_plan` is first compiled)** —
  an **early, human-facing, non-authoritative** refusal point. Its purpose
  is UX and cost-avoidance: an operator proposing a doomed restore sees the
  refusal before requesting approval (`C7` §5.2), rather than discovering
  it only when a worker claims the job. A compile-time pass does **not**
  certify anything about the state of the world at claim time, which may
  be minutes, hours, or (if approval is pending) potentially longer after
  compilation.
- **Claim-time (`C2` §6, immediately before device contact)** — the
  **sole authoritative** gate. Every fact re-checked here is re-read fresh,
  against current state, no matter how long ago compile-time checks
  passed. A fact whose staleness has a real safety consequence (this
  document's reconciliation-pending check; the connectivity-freshness
  policy, §3.3) **must** have a claim-time instance; a fact whose staleness
  has no safety consequence within the operation's own timeframe (e.g.
  `restore_approval`'s validity window, already re-checked at claim per
  `C7` §6.2) is adequately covered by the existing generic re-check.

This document's own two facts are resolved differently under this
principle, and the table below states each explicitly rather than
collapsing them into one shared disposition:

| Fact | Compile-time (`C7` §5.3) | Claim-time (`C2` §6) |
|---|---|---|
| `restore_approval` validity, `requested_by ≠ approved_by` | Not this document's concern — `C7` §6.2 states this is already `C2` §6 check 1's "restore-specific instance," unmodified by this document. | Same as compile-time column: `C2` §6 check 1, existing FROZEN text, unaffected by any revision in this document. |
| Connectivity evidence meets §3.3's freshness policy | **Folds into `C7` §5.3 check 1** (Connectivity) — an early pass using whatever evidence (active probe or cached telemetry, per §3.3) is available at compile time. Non-authoritative; a pass here is not carried forward. | **`C2` §6 check 2** (Connectivity precondition), whose scope is extended by this document to explicitly cover `controlled-restore-write` jobs (its FROZEN text today reads "for a class-1 profile" — this document proposes reading that as inclusive of the new class-1.5, consulting the same §3.3 policy, re-verified fresh, "not only at schedule-enable time" per that check's own existing wording). **Authoritative.** |
| No unreconciled prior restore-write outcome against the ledger's key (§3.1.1) | **NEW, per council-informed PO decision: `C7` §5.3 check 7, `check_id=C7_RESTORE_NO_UNRECONCILED_PRIOR`** (appended after check 6; seq 17 preserves the separate earlier check-1 amendment and leaves checks 2–6 unchanged) — an independently named, independently recorded early pass, exactly as connectivity above: useful for UX/cost-avoidance, not authoritative, and can go stale before claim (§3.1.0's own definition of what a compile-time pass is and is not). | **`C2` §6 check 4**, whose slot already exists and is already class-scoped (`RB.x` ledger, currently stated `NOT_APPLICABLE` for restore, per relay seq 13). This document proposes reading check 4 as: *for `controlled-restore-write` jobs, this ledger's own `has_unreconciled_prior` check, fail-closed on an unreadable ledger; `NOT_APPLICABLE` for every other class, unchanged.* **Authoritative.** |

**Why compile-time gets its own new check (check 7), while claim-time
reuses check 4's slot — two different placement questions, two different
answers, both deliberate.** The initial DRAFT (and the prior revision of
this table) proposed folding the compile-time pass into `C7` §5.3 check 2
instead, reframing it as a broader "artefact and target admission
validity" check. **This is superseded.** Two independent, fresh-context
council seats (a Security Reviewer and a Senior Python Architect) reviewed
that fold — and the alternative of folding into check 5 instead — and
**both rejected folding into either check**, for the same reason: mixing
two independent predicates (backup/artefact validity, or target
concurrency, and reconciliation-pending) under one recorded pass-or-fail
result loses which one actually failed, and is inconsistent with how this
document's *own* claim-time extension of check 4 was already done —
independently named and independently tracked, never silently blended
into another check's prose. The Product Owner accepted this — the
decision is **council-informed, not council-decided**: the council seats
independently identified and named the risk; the Product Owner made the
placement call based on that input, per this movement's own procedure
(fresh-context seats inform, the Product Owner decides). **Check 4's
claim-time slot is different**: it was already class-scoped and already
`NOT_APPLICABLE` for a class other than the one it names, so extending its
applicability to a second class reuses a genuinely fitting existing slot
rather than mixing two predicates into one recorded result — the same
"independently named, independently tracked" property the council sought,
achieved by two different mechanisms because the two check batteries
(`C7` §5.3's six checks, `C2` §6's six checks) do not share the same
existing shape at the position each fact needs to occupy. **Check 7 is
appended, not inserted** — after check 6 (credential resolution), so
the separate earlier check-1 connectivity amendment remains in force,
checks 2–6 keep their existing text/number/order, and check 7 is appended.
Consumers/tests that assume a six-check cardinality must explicitly move
to seven; this is the smallest-disruption
placement consistent with giving the fact its own independently-tracked
row, exactly the property the council flagged as missing from either fold.

**`C4` static sign-off is untouched by this table.** Nothing above alters
§3.0's two-predicate split — `C4`'s `SIGNED_OFF` determination remains a
purely static, spec-time predicate, independent of every row in this
table, exactly as seq 7 established and as seq 13's own text reaffirms
("static `C4` sign-off remains independent from runtime `C2` admission").

#### 3.1.1 Ledger key/scope: physical `device_id`/`endpoint_id` only; VSX virtual-system restore is out of scope (revised per PO review decision, correcting this document's own prior over-speculation)

**PO review decision, correcting this subsection's own prior text.** The
previous revision of this subsection treated VSX virtual-system-scoped
restore as an already-live targeting option needing an admission-scope
policy choice (`device_id`-wide default vs. `virtual_system_ref`-scoped
override). Checked against this repository's own actual schemas, that
framing was wrong: **no current restore capability, and no current
backup capability for the one vendor with a VSX model (Check Point),
targets an individual virtual system at all.** This subsection is
rewritten to state the actual current scope, not a hypothetical one.

**The evidence, read directly from this repository's own frozen
contracts, not assumed:**

- `C7` §5.2's `restore_plan` schema carries exactly `target_device_id` and
  `target_endpoint_id` — **no `virtual_system_ref` field exists on
  `restore_plan` at all**, unlike `backup_artefact` (§3.2 of that
  document), whose `virtual_system_ref` column is `C4`'s generic
  composite-target-model field, populated only where a capability's own
  target model actually uses it.
- `BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 7 and §7.7 state, for the one
  vendor this repository's restore precedent (`cp_gaia_backup_local`, per
  `C4` §2.4) is modeled on, **explicitly and in FROZEN text**: *"Per
  physical endpoint only. A VSX virtual-system entity
  (`<device>__vsid_<vs_id>`) is never contacted and is never credited
  with its host's attestation — a Gaia snapshot of a VSX host is not a
  per-virtual-system recovery artifact"* and (§7.7) *"Not valid inside a
  VSX virtual-system context."* Check Point Gaia backup collection — the
  only concrete backup precedent this repository has for the vendor that
  has a VSX model at all — is **categorically excluded** from VSX
  virtual-system contexts, by the vendor's own command semantics, not by
  an admission-policy choice this document could relax or tighten.
- Consequently, **`backup_artefact.virtual_system_ref` is, for every
  artefact this repository's actual collectors can produce today, always
  `NULL`** — it is a generic `C4` field present on the table for a
  capability model that might one day need it, not evidence that a
  VSX-scoped backup or restore capability exists or is even contemplated
  as buildable today.

**Resolved scope: physical `device_id`/`endpoint_id`, full stop, no
virtual-system dimension.** This ledger's key is `device_id` (§4.0's
original resolution, unchanged), read against the same physical
`devices`/`endpoints` identity `C1` §3.2 and `restore_plan` itself already
use — never a virtual-system-qualified identity, because no restore
capability this repository can currently build has one to qualify against.

**VSX virtual-system-context restore is explicitly UNSUPPORTED, blocked,
and not in this document's or `C7`'s current scope** — restated plainly,
not left to be inferred from an absent field: a capability whose target
would require a `virtual_system_ref` (a hypothetical future "restore
this specific VS's configuration" operation, distinct from a physical-
device Gaia restore) does not exist in `C7` §5 today, is not compiled by
`C4` §2.4's worked example, and is not something this ledger's admission
model is designed to gate. **Any future VSX-context restore capability
requires its own, separate vendor/platform-and-target-scope contract** —
a new capability model (what "restoring a virtual system's own
configuration" even means at the vendor-command level, since Check
Point's Gaia restore precedent is physical-device-only by the same
constraint that excludes VSX from backup), a new `restore_plan` target
shape (adding `virtual_system_ref` where `C7` §5.2 does not carry one
today), and its own admission-ledger design — none of which this document
invents, proposes, or gestures at a shape for. Building such a contract
is squarely a `GOV_PO_ROLE_MIGRATION.md` §7 trigger-(b) freeze candidate
in its own right (a new write boundary for a target scope this product
does not admit today), not a parameter this ledger's existing design
could absorb by widening a key.

**`RestoreWriteLedgerEntry`'s `virtual_system_ref` field (§4) is removed
this pass** — it was added in the prior revision specifically to support
the now-corrected VSX-scoping framing above; carrying an always-`NULL`
field for a target dimension no current capability populates is dead
schema, not forward-compatibility, and a genuine future VSX-restore
contract would define its own key shape rather than inherit an unused
column from this one.

**ClusterXL identity separation does not establish restore safety; member
restore is unsupported and out of current scope (relay seq 17).** Per
`C4` §4.2/§4.3, each ClusterXL cluster member is its own, independently
registered *physical* device with its own `device_id` —
`cluster_member_ref` is a display/grouping label only, never a row of its
own and never an identity. That proves identity separation only. It does
**not** prove operational independence, nor that restoring one member
cannot affect its peer or trigger a role transition. `C7` §9.8 cannot be
used to close that gap: its text is specifically about **CP management-HA
backup consistency groups**, not ClusterXL gateway members. Because no
restore capability exists for any platform today (`C7` §7.3/§7.4 both
record `NOT_BUILT`), the present contract does not admit a ClusterXL
member restore at all. Any future ClusterXL physical-member restore
requires its own vendor/platform, target-scope, and cross-member safety
contract before this ledger can be applied to it. The ledger's
`device_id` key remains valid for admitted standalone physical-device
targets; it is not a claim that two cluster-member keys may be acted on
independently.

#### 3.1.2 `restore_run`/step cardinality — one ledger attempt entry per `restore_run`, never per step

**One `RestoreWriteLedgerEntry` of `entry_kind="attempt"` is recorded per
`restore_run` (`C2` job), never per individual write step within that
run.** Rationale, tied directly to `C7`'s own existing model:

- `C7` §5.6 already models `restore_run` as **one** `C2` job row (1:1 with
  `job_id`) — the ledger's own granularity matches the granularity of the
  thing it is gating admission for (a new `restore_run`/job request
  against a target), not a finer-grained sub-unit `C2`/`C7` do not
  themselves expose as separately admissible.
- `C7` §5.7's `OUTCOME_UNKNOWN` handling is explicitly **job-level**: *"`C2`'s
  own durable state cannot, by construction, distinguish [a fully-applied-
  but-confirmation-lost outcome] and [a partially-applied outcome] from
  each other"* — even `C7` itself, which has far more visibility into the
  step sequence than this ledger does, does not attempt step-level
  outcome tracking for reconciliation purposes. A restore capability's
  step sequence may genuinely contain multiple device-write steps
  (transfer, then a separate vendor apply/commit action, `C7` §5.7 case 3)
  — this ledger's single attempt entry records the **`restore_run`'s own
  terminal outcome** (`applied_verified` / `applied_unverified` /
  `outcome_unknown` / `failed`), drawn from whatever `C7`/`C2` ultimately
  resolve the job to, not an independent per-step judgment this ledger
  would have no way to make more precise than `C7` already can.
- `record_attempt` (§4) is therefore called **once per `restore_run`**, at
  the moment the run's *first* device-write step is sent (mirroring
  `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §6's send-time-not-confirm-time
  rule, applied to the run as a whole rather than to each of its steps),
  and the entry's `outcome` field is updated only in the append-only sense
  §4.0 already establishes: a **later** entry (a `"reconciliation"` entry,
  or — see below — a terminal-outcome update) references the run, it does
  not go back and edit the original attempt entry's own recorded fields.
  **Revision to §4's API surface**: `record_attempt`'s original docstring
  said "called once per restore-write step," which this subsection
  corrects — restated in full in §4 below.

#### 3.1.3 Retry behavior for the claim-time reconciliation check, distinguished from the class's own no-auto-retry rule

Two different questions, easily conflated, are answered separately:

- **"Does a job that is `BLOCKED` at claim by this check get retried?"**
  Yes, in the ordinary `C2` §6 sense every other precondition check
  already has: a job whose claim is refused by a precondition (this check,
  or any of `C2` §6's other five) **never left `REQUESTED`** —
  `mutation_boundary_crossed` was never set, no device was contacted, and
  the job simply remains claimable again on a future claim attempt,
  exactly like a credential-resolution failure (check 6) or a coordination-
  window conflict (check 3) would. This is **not** the class's own
  never-auto-retry rule (§3.4) — that rule governs a job that reached
  `EXECUTING` and then failed or hit `OUTCOME_UNKNOWN`; a job refused at
  claim, before `EXECUTING`, was never attempted in the sense §3.4's rule
  is about, and remains eligible for a normal future claim cycle without
  any new `restore_plan`, new approval, or reconciliation being required
  — the block simply lifts once the prior outcome is reconciled (or, for
  the connectivity check, once a fresh probe succeeds).
- **"Can a job stuck `BLOCKED` on this check forever eventually be
  surfaced or expired?"** This document does not invent a new expiry
  mechanism — a `REQUESTED` job repeatedly refused at claim is visible to
  an operator via its own `precheck_results`/refusal-reason trail (`C2`
  §6's existing per-check outcome recording, extended to this check per
  §3.1.0's table), naming the specific unreconciled `restore_run_id`
  blocking it; whether such a job should eventually auto-cancel is a
  generic `C2` job-lifecycle question this document does not have the
  authority or the scope to answer, and is named as an open item (§9)
  rather than silently assumed either way.

### 3.2 Level-consumer and wire-field inventory (relay seq 5's required inventory)

Relay seq 5 (`ActionClass.level=1.5`) requires the successor movement to
"inventory every `ActionClass.level` consumer, including browser/API wire
fields... preserve closed-set invariants" before any taxonomy edit lands.
This inventory was run against the current repository state as part of
this revision (read-only; no file listed below is edited by this
document):

| Consumer | Location | Impact of `level=1.5` |
|---|---|---|
| Ordering/equality assertion | `tests/test_architecture_convergence.py::test_the_five_classes_exist_and_are_ordered` | Asserts `[c.level for c in tax.ACTION_CLASSES] == [0, 1, 2, 3, 4]` — a literal list-equality check. **Must be edited** by the successor movement to `[0, 1, 1.5, 2, 3, 4]` (or equivalent); this is exactly the kind of test-file edit named in the amendment bundle's Step 1, not performed here. |
| Console job-registry mapping | `tests/test_architecture_convergence.py::test_no_console_job_type_is_class_2_or_above` | Uses `jt.action_class.level >= 2`. `1.5 >= 2` is `False`, so this assertion's *pass* condition is unaffected — but it also means this test does **not** guard against a future `controlled-restore-write` job type being added to `console/registry.py`'s `JOB_REGISTRY` (a real gap, since that class is `console_submittable=False` and must never be console-reachable). **Successor movement must add a sibling assertion** naming `1.5`/`controlled-restore-write` explicitly, not rely on this test's existing `>= 2` boundary to catch it. |
| Browser/API wire field | `console/app.py:294` — `"action_class_level": jt.action_class.level` (a job-type listing endpoint's JSON response field) | **The one genuine wire-field consumer found.** No class currently registered in `JOB_REGISTRY` maps to `controlled-restore-write` (none will, until a restore job type is authored in a later movement — out of scope here and there), so no *existing* API response emits `1.5` today. JSON itself does not distinguish int/float at the wire-protocol level (both serialize as a JSON `number`), so no schema-breaking change occurs merely by widening the Python type; the risk is entirely on the *consumer* side, if one exists that parses this field as a strict integer. |
| Wire-field consumer (int-equality test) | `tests/test_con2_console_job_engine.py:354,357` — `assert by_id["cp_gaia_backup"]["action_class_level"] == 1`, `assert by_id["report_rebuild"]["action_class_level"] == 0` | Both assertions are against **existing** classes (`0`, `1`), which keep integer `level` values unchanged by this proposal — **unaffected**. No test asserts `== 1.5` today because no job type resolves to the new class yet; the successor movement adds such an assertion only once/if a restore job type is registered. |
| Frontend (JS/TS/template) consumers | repository-wide search (`templates/`, `static/`, any `*.js`/`*.ts`) | **None found.** No frontend asset in this repository parses or type-checks `action_class_level`; the console/app.py endpoint is consumed by whatever external client calls it, which is outside this repository's own source and cannot be inventoried from here — named as a residual, not-closable-from-this-repo risk, not a false "zero risk" claim. |
| Ordering/comparison call sites (`sorted`, `key=lambda c: c.level`, direct `<`/`>` comparisons) | repository-wide search across `utils/`, `console/`, `ui2/` | **None found** beyond `utils/action_taxonomy.py`'s own tuple literal ordering and the two test files above. `console/registry.py` references `ActionClass` only via its `action_class` property, never `.level` directly. |
| `docs/AI_DEVELOPMENT_PROTOCOL.md` command-gate table | "why required... its `utils/action_taxonomy.py` class (0 read / 1 controlled recovery write / 2 operational state change / 3 configuration write / 4 policy-remediation)" | Enumerates classes by integer in prose, not code — no runtime impact, but the prose itself needs the new class named; §3.5 below (command-gate registration path) proposes the exact addition. |

**Conclusion of this inventory:** exactly one non-test source consumer
(`console/app.py:294`) and two test files require a successor-movement edit
(`test_architecture_convergence.py`'s ordering assertion, and a new
console-submittability-guard assertion this inventory itself identifies as
missing); no frontend, no `sorted()`/comparison call site, and no other
wire-field consumer was found. No closed-set invariant this repository
currently enforces in code is broken by `level=1.5` — the two tests named
above are the entire blast radius, both to be edited by the movement that
actually adds the class, not by this document.

### 3.3 Connectivity freshness — configurable policy, not a fixed number (revised: the proposed 15-minute value is REJECTED per PO clarification)

**This value is explicitly rejected; do not freeze 15 minutes anywhere in
a successor movement.** The prior revision of this document proposed a
concrete 15-minute connectivity freshness bound. The Product Owner's
clarification supersedes that proposal outright: *"do not freeze 15
minutes... model freshness as configurable policy, explicitly separating
background polling cadence from claim-time restore safety."* No numeric
default in this subsection is a decided value — every bound named below is
either an illustrative range (explicitly marked as such) or `UNKNOWN`,
left for the successor movement's own contract-freeze step to set,
informed by real vendor telemetry-latency evidence this document does not
have.

**Two concepts this document was previously conflating, now separated:**

- **Background polling cadence** — the interval at which a *separate*,
  already-existing telemetry/inventory mechanism (a class-0 connect-check
  scheduler, an SNMP poll loop, or equivalent) refreshes its own cached
  view of a device's reachability, independent of any particular restore
  job. This cadence is an operational/performance concern (how much load
  the polling itself puts on the device and the collector), and its value
  is out of this document's scope entirely — it belongs to whatever
  subsystem owns that poller.
- **Claim-time restore safety** — the wholly separate question this
  document actually owns: *at the moment a specific restore-write job is
  about to be claimed*, is the evidence that the target is currently
  reachable **trustworthy enough, right now**, to proceed with a write
  whose failure mode (`C7` §5.7) can leave a device mid-outage? A cache
  refreshed on a background cadence is not automatically an answer to this
  question merely because the cadence exists — the two must never be
  treated as the same test, which is exactly the conflation the initial
  DRAFT's single "15 minutes" number risked normalizing.

**Two alternative evidence sources for claim-time restore safety, not one:**

**(1) Mandatory claim-time active connectivity/protocol probe — the
authoritative path, always available.** A live, synchronous class-0
connect-check (the same `backup_profile_connect_check`-equivalent job
`C7` §5.3 check 1 already names) executed as close as practicable to the
claim moment itself, using the **actual restore-write transport/protocol**
(the same `ssh_exec`/`xml_api_call` session kind `C4` §5 would use for the
real write) — not a different, lower-fidelity protocol. This path proves
the one fact that matters: the specific channel the restore-write will
use is live, right now. This path is **always available as the fallback**
regardless of what is configured for path (2) below; a deployment may
choose to use it as the *sole* evidence source (never configuring cached
telemetry at all), in which case §3.3's policy fields below are moot for
that deployment.

**(2) Cached telemetry/SNMP — optional, lower device/collector load,
conditional on policy, never the sole source unless every condition below
is met.** A deployment **may** configure a cached-telemetry evidence
source (SNMP reachability, an inventory poller's last-known state, or
equivalent) to avoid a live probe on every single claim, **but only when
all four of the following hold**; failing any one **falls back to path
(1)** (active probe), never silently proceeds on stale or insufficient
cached evidence:

| Condition | Requirement |
|---|---|
| **TTL** | The cached evidence's own timestamp is within a configured `cached_evidence_ttl_seconds` policy field (§3.3.1 below) — a per-deployment, per-vendor-class configurable value, not a fixed constant this document sets. |
| **Identity binding** | The cached evidence is bound to the **same device identity** the restore-write targets (matching `device_id`/hostname-fingerprint, the same identity-match discipline `C7` §5.3 check 3 already requires for the artefact itself) — a cache entry for the wrong device, or one that cannot prove which device it describes, is treated as absent, not stale-but-usable. |
| **Semantic sufficiency** | **SNMP (or any read-only telemetry protocol) reachability does NOT by itself prove the write channel (`ssh_exec`/`xml_api_call`) is usable**, and this document does not claim otherwise. A cached-telemetry source may be used as claim-time evidence **only if this vendor/platform pair has an entry in §3.3.1's `cached_evidence_semantic_sufficiency_contract_ref` mapping** — a vendor-specific, council-reviewed contract establishing that this particular telemetry signal is a sufficient proxy for this particular write channel's own readiness, for this particular vendor/platform (never a global flag covering every vendor at once). Absent such an established, per-vendor/platform-keyed contract, cached telemetry is **never sufficient alone** for that pair, regardless of how fresh or well-identity-bound it is — this is the one condition this document treats as a hard requirement, not a tunable, because it is a semantic claim about vendor behavior this document has no evidence for (see §3.3.2's research note below on why even public third-party documentation does not substitute for this). |
| **Explicit configuration** | The evidence source for a given deployment/vendor-class is an explicit, named policy choice (§3.3.1's `evidence_source` field) — never an implicit default inferred from "a cache happens to exist." |

Any condition failing (stale TTL, no identity binding, no established
semantic-sufficiency contract, or no explicit configuration) means
**fail closed to path (1)**: a fresh active probe is required before the
claim can proceed; the claim is never admitted on the strength of
insufficient cached evidence, and a probe failure/timeout is `BLOCKED`,
never treated as "no evidence, so proceed."

#### 3.3.1 Proposed policy schema (fields, precedence, bounds as proposals — none frozen; contract reference now keyed per vendor/platform, per council finding)

```python
@dataclass(frozen=True)
class RestoreConnectivityFreshnessPolicy:
    evidence_source: str          # "active_probe" | "cached_telemetry" —
                                   # explicit per-deployment/vendor-class
                                   # configuration; UNKNOWN/unset defaults
                                   # to "active_probe" (the safe default:
                                   # absence of configuration never silently
                                   # enables the lower-assurance path)

    # --- path (1): active probe --------------------------------------
    active_probe_protocol: str     # the same transport/protocol the real
                                   # restore-write step will use; UNKNOWN
                                   # until a concrete capability names it
    active_probe_timeout_s: float  # UNKNOWN -- proposal only, no default
                                   # claimed here; must be short enough not
                                   # to itself become the bottleneck at
                                   # claim time, long enough not to produce
                                   # false BLOCKED results on a healthy but
                                   # slow device -- the successor movement's
                                   # own number to set, informed by real
                                   # per-vendor probe latency evidence

    # --- path (2): cached telemetry (optional) ------------------------
    cached_evidence_ttl_seconds: float | None
        # UNKNOWN / proposal-range-only (see below) -- NOT 15 minutes,
        # NOT any other single frozen value. Illustrative range only,
        # for discussion, not a decided bound:
        #   min_bound_seconds: 60        (illustrative floor)
        #   max_bound_seconds: 3600      (illustrative ceiling)
        #   default: UNKNOWN             (explicitly not set here)
    cached_evidence_identity_binding_required: bool  # always True;
        # not a tunable -- an unbound cache entry is never usable evidence
    cached_evidence_semantic_sufficiency_contract_ref: dict[tuple[str, str], str]
        # REVISED (this pass): keyed per (vendor, platform_role_scope) --
        # e.g. {("check_point", "cp_gaia_gateway"): "<contract doc ref>"} --
        # mirroring C4 §3.2's own gate_registry key shape (vendor,
        # platform_role_scope, ...), never a single global flag. A vendor/
        # platform pair absent from this mapping has NO established
        # contract; cached_telemetry MUST NOT be selected as evidence_source
        # for that pair regardless of any other field, exactly as the
        # single-flag version already required -- this only prevents one
        # vendor's established contract from being silently read as if it
        # applied to every vendor/platform this product manages, which a
        # single shared flag would risk.
        #
        # EVERY entry in this mapping is itself a freeze candidate: per
        # this document's own §9 open item and GOV_PO_ROLE_MIGRATION.md §7
        # trigger (b) ("a freeze candidate introducing a security,
        # identity, credential, storage-schema or write boundary"), a
        # cached-telemetry semantic-sufficiency contract is exactly such a
        # candidate -- it establishes that a read-only signal (SNMP or
        # equivalent) may substitute for direct proof of write-channel
        # readiness, a security-relevant claim about vendor behavior. NO
        # entry may be added to this mapping without first passing council
        # review from a nexus-po PLAN or DECIDE episode; a vendor/platform
        # pair's absence from this mapping is therefore the SAFE default,
        # never a gap to fill informally.

    # --- precedence, staleness, audit --------------------------------
    fallback_on_insufficient: str  # "active_probe" -- fixed, not
        # configurable: whatever the configured evidence_source, an
        # insufficient cached-telemetry read (any condition in the table
        # above failing) always falls back to an active probe, never to
        # "proceed anyway" and never to a second cached source
    stale_or_unknown_behavior: str  # "BLOCK" -- fixed, not configurable;
        # matches this document's own fail-closed posture (§5) exactly
    audit_evidence_source_used: str        # recorded per claim: which
                                            # path actually supplied the
                                            # evidence for this attempt
    audit_evidence_age_at_use_seconds: float | None  # recorded per claim
    audit_fallback_triggered: bool                    # recorded per claim
    audit_policy_config_snapshot_ref: str             # recorded per claim:
        # a reference to the exact policy configuration in force at claim
        # time, so a later audit can reconstruct why a claim was admitted
        # or blocked without re-deriving today's live configuration
```

**Precedence, stated plainly:** `active_probe` is always the authoritative
and always-available path. `cached_telemetry`, if configured, is
consulted first only to avoid unnecessary device/collector load, but its
result is used **only** when every condition in the table above is
satisfied; any failure of any condition **falls back to `active_probe`
automatically and unconditionally** — a deployment can configure whether
to *attempt* cached telemetry first, it cannot configure the fallback
behavior itself, which stays fixed as stated.

**None of this schema's numeric fields (TTL bounds, probe timeout) are
decided values.** They are named here as **proposals with illustrative
ranges** so the successor movement/council has a concrete shape to review
and fill in with real evidence — not so this document can claim a number
was chosen. Where the initial DRAFT said "15 minutes," this revision says
`UNKNOWN`, explicitly, everywhere that number previously appeared.

#### 3.3.2 Research note: BackBox public documentation (informational only, NOT authoritative for neXus)

Public BackBox documentation (a third-party commercial network backup/
recovery product, unrelated to this repository) describes, in its own
marketing/support materials, that its restore workflow performs automated
pre-restore availability and credential checks, and validates backup
integrity both at creation time and again immediately before restore
(sources: `backbox.com/backup-and-recovery/`, BackBox community/support
user-guide pages, accessed via public web search 2026-09-10 for this
note). This is recorded here **purely as external context that a
comparable commercial product performs some analogous pre-restore
checks** — it is **not** treated as evidence for how neXus's own
connectivity-freshness or evidence-sufficiency semantics should work, per
`AGENTS.md`'s vendor-semantics law ("a command name is not its semantics...
if official documentation cannot establish a load-bearing semantic, mark
it `UNKNOWN`") and per this repository's own standing rule against filling
a semantic gap from general product knowledge. BackBox's own product
behavior is not a vendor whose devices neXus manages, is not evidence
about Check Point/PAN-OS/any managed device's actual telemetry semantics,
and **establishes nothing** about whether SNMP or any other cached signal
proves write-channel readiness for any vendor this repository actually
targets — that determination remains exactly what §3.3's "semantic
sufficiency" condition requires: a vendor-specific contract this document
does not have and does not invent one for.

### 3.4 The `C2` retry rule this class needs (proposed; `C2` itself unedited; admission checks live in §3.1, both compile-time and claim-time)

`docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (FROZEN) §5.3's
per-action-class retry table is not edited by this document. **Revision
note (updated this pass):** the original DRAFT proposed both a new `C2`
§6 admission check (row 7) and this retry-rule row in the same
subsection; relay seq 9 withdrew the standalone check in favor of a `C7`
fold; relay seq 13 then required an explicit claim-time check after all,
resolved in §3.1 above as a repurposing of `C2` §6 check 4's existing
slot (not a new row). None of that affects this subsection's own answer —
the retry rule below is a genuinely different question (what happens
after a claimed job fails or reaches `OUTCOME_UNKNOWN`, not whether a job
may be claimed at all) — and it survives unchanged from the original
DRAFT through every revision so far:

| Action class | Retry rule |
|---|---|
| `CLASS_1B_CONTROLLED_RESTORE_WRITE` (`controlled-restore-write`) | **Never auto-retries, at any stage, for any reason** — identical rule to `CLASS_1_RECOVERY_WRITE`'s row, for a stronger reason: `C7` §5.7 already mandates this at the job-state level ("closes only via `RECONCILED`... no exception for restore"; a new attempt is always a **new** `restore_plan`/job, never a retried one). This row makes `C2`'s own per-class retry table state the same rule explicitly, rather than leaving the new class implicitly covered only by `C7`'s restatement of `C2` §3.5's generic mechanism. **This rule governs a job that reached `EXECUTING`** — a job refused earlier, at claim, by §3.1.0's table (including the new §3.1.3 claim-time reconciliation check) never reached `EXECUTING` at all, and is governed instead by §3.1.3's own retry-eligibility answer, not this row; the two are not the same event and this document does not conflate them. |

This proposed row is additive to `C2`'s existing retry table; no existing
row, class-0/1/2/3/4 rule, `C7` check, or `C2` §6 check-4 slot is modified
beyond §3.1's own in-place amendments.

### 3.5 Command-gate registration path for the new class (proposed; `docs/AI_DEVELOPMENT_PROTOCOL.md` itself unedited)

`docs/AI_DEVELOPMENT_PROTOCOL.md`'s "Network-device command gate" section
states a path for class 1 ("a new class 1 command requires the recovery
contracts in `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` **and** an approved
gate entry") and a blanket prohibition for classes 2–4 ("No new class 2, 3
or 4 command at the current product maturity"). It has **no stated path**
for a class that is neither — the gap named in this document's original
§9 item 4, now given a concrete proposed fix:

**Proposed addition** (new sentence immediately after the existing class-1
sentence in that section): *"A new `controlled-restore-write` (class 1.5)
command requires this document's own admission contract
(`docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`: per-target
reconciliation-pending ledger — checked both at `restore_plan` compile
time (`C7` §5.3, non-authoritative) and at `C2` claim time (`C2` §6 check
4's slot, authoritative, per §3.1.0), `C7` §6.2 per-operation approval,
`C7` §5.3 precondition battery) **and** an approved gate entry whose
`sign_off_state` may reach `SIGNED_OFF` only for a capability row scoped to
a `restore_push` step (or a restore-apply `exec`/`poll` step) under
`C4` §3.3 step 7's restated rule — mirroring class 1's own two-part
requirement (recovery contracts **and** gate entry) with the class-1.5
equivalents substituted for each part."* This keeps the section's existing
shape (a stated contract requirement plus a stated gate-entry requirement)
rather than inventing a new shape for one class.

### 3.6 `C7` §5.3 companion amendment: resolving the check-4 contradiction (new this pass, per PO instruction)

**The contradiction, stated plainly.** `C7` §5.3 (FROZEN) carries its own
note on `C2` §6 check 4, written before this class existed: *"that check
is `NOT_APPLICABLE` for a restore job. The ledger's 24-hour ceiling exists
to bound a *recurring* class-1 resource-consuming operation... restore is
not recurring and is not admitted by cadence at all."* This document's
§3.1.0 now proposes reading check 4, for `controlled-restore-write` jobs
specifically, as **this ledger's own reconciliation-pending check** — a
different admission question than the `RB.x` cadence ceiling, but still
occupying the *same numbered slot* `C7` §5.3's own frozen note describes
as unconditionally `NOT_APPLICABLE` for any restore job. Left as written,
`C7` §5.3's note and this document's §3.1.0 **directly contradict each
other** about what check 4 means for a restore-class job — a reader of
`C7` alone would conclude check 4 never applies to restore; a reader of
this document alone would conclude it does, for the reconciliation fact
specifically. This is not a disagreement to leave for a successor movement
to notice by accident; it is named here and given the exact proposed fix.

**Proposed replacement text for `C7` §5.3's existing note** (a successor
`C7`-amendment movement would replace the quoted paragraph above with the
following, preserving the surrounding section's structure and its own
frozen numbering of checks 1–6):

> **Note on `C2` §6 check 4, per-class interpretation (revised for
> `CLASS_1B_CONTROLLED_RESTORE_WRITE`, `docs/design/D1_DEVICE_WRITE_
> CLASS_AND_STEP_KIND_DECISION.md`/`UI2_0_BASELINE_CONTRACT.md` §2
> `DEVICE-WRITE-CLASS`):** check 4's applicability is class-scoped, not a
> single restore-blanket `NOT_APPLICABLE`:
>
> - **`CLASS_1_RECOVERY_WRITE` (backup) — unchanged.** Check 4 remains the
>   `RB.x` operational-write ledger (`RECOVERY_OPERATIONAL_WRITE_LEDGER.md`),
>   the existing 24-hour cadence-ceiling interpretation, exactly as before
>   this amendment. Nothing about backup's own admission changes.
> - **`CLASS_1B_CONTROLLED_RESTORE_WRITE` (restore) — new.** Check 4 reads
>   `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`'s own reconciliation-
>   pending check instead of the `RB.x` cadence ledger — a materially
>   different admission question (whether a *prior* restore-write against
>   this same target left an unresolved `OUTCOME_UNKNOWN` outcome, not
>   whether *this* operation has run too recently; that document's §1
>   explains at length why a cadence ceiling does not apply to restore at
>   all). Fail-closed on an unreadable ledger, per that document's own §5.
> - **Every other class — unchanged.** `NOT_APPLICABLE`, exactly as
>   before.
>
> This replaces this section's prior text (*"that check is `NOT_APPLICABLE`
> for a restore job"*), which predates the `controlled-restore-write`
> class and its own ledger, and was accurate only because no restore
> class existed yet to give check 4 a second meaning. Check 5 above (no
> concurrent restore or backup against the same target) remains restore's
> own **additional**, unaffected mutual-exclusion mechanism — a live-job
> concurrency lock, distinct from check 4's now-class-scoped
> reconciliation-pending question about a *terminal, unreconciled* prior
> outcome; the two checks answer different temporal questions and neither
> substitutes for the other.

**Scope of this proposal.** This is proposed text for a successor `C7`-
amendment movement to apply, per this document's own standing scope
boundary — `C7` is FROZEN and is not edited here. It is named as its own
subsection, distinct from §3.5's `docs/AI_DEVELOPMENT_PROTOCOL.md`
proposal, because it targets a different FROZEN document and resolves a
different kind of gap (an internal contradiction between two documents'
own text, not a missing registration path).

### 3.7 `C7` §5.3 proposed new check 7: the literal battery addition (new this pass, per council-informed PO decision)

**This is a separate proposed `C7` amendment from §3.6 above** — §3.6
amends `C7` §5.3's *prose note* about `C2` §6 check 4 (a `C2`-side slot);
this subsection proposes an actual **new row in `C7` §5.3's own
six-check compile-time table**, making it seven. Both target `C7` §5.3
but touch different things (a footnote vs. a table row) and are named
separately so a reviewer does not conflate them.

**Why a new check, not a fold into check 2 or check 5 — council-informed,
not council-decided.** The prior revision of this document proposed
folding the compile-time reconciliation-pending pass into `C7` §5.3
check 2 (reframed as a broader "artefact and target admission validity"
check), naming check 5 as a plausible alternative. Two independent,
fresh-context council seats — a Security Reviewer and a Senior Python
Architect — separately reviewed this question and **both rejected
folding into either check 2 or check 5**, for the same reason: a folded
check mixes two independently-failing predicates (e.g. artefact validity
and reconciliation-pending, under check 2; or live-job concurrency and
reconciliation-pending, under check 5) under one recorded pass/fail
result, so a compile-time refusal cannot say *which* predicate actually
failed — the same "independently named, independently tracked" property
this document's own claim-time extension of `C2` §6 check 4 already
achieves by a different mechanism (§3.1.0), and which a fold would not.
The Product Owner accepted this input and decided the placement — this
is recorded as **council-informed**, not council-decided: the seats
identified and named the risk; the placement decision itself is the
Product Owner's, per this movement's own procedure (fresh-context seats
inform, the Product Owner decides, per `GOV_PO_ROLE_MIGRATION.md`'s own
review-episode shape).

**Proposed new row, appended after check 6; the earlier check-1 amendment
is preserved and checks 2–6 are unchanged:**

| # | check_id | Check | What it re-reads |
|---|---|---|---|
| 7 | `C7_RESTORE_NO_UNRECONCILED_PRIOR` | **No unreconciled prior restore-write outcome** (`controlled-restore-write` plans only) — an early, non-authoritative, staleness-tolerant pass against this ledger's `has_unreconciled_prior` check for the plan's target, per §3.1.1; `NOT_APPLICABLE` for every other class's plan | this document's `RestoreWriteLedger` |

**Position and disruption, stated explicitly.** Check 7 is **appended at
the end** of `C7` §5.3's table, after check 6 (credential resolution) —
it does not reorder or renumber checks 1–6. Seq 17 does not undo the
separate earlier check-1 connectivity amendment; checks 2–6 keep their
existing text and order. This is the minimum-disruption placement, but
fixed-cardinality consumers/tests are affected and must explicitly move
from six to seven checks. `C7` §5.3's own introductory sentence ("checks 1–6 above are the
compile-time battery") is proposed to be updated to "checks 1–7," with no
other change to that sentence's meaning.

**Target eligibility is a separate fail-closed input.** The named
predicate `RESTORE_TARGET_TOPOLOGY_ELIGIBILITY` is evaluated at plan
compilation from authoritative registry plus current topology evidence and
MUST positively establish `STANDALONE_PHYSICAL_DEVICE`. A known ClusterXL
member yields `UNSUPPORTED_CLUSTERXL_MEMBER`; missing, stale, or conflicting
membership evidence yields `NOT_EVALUABLE`, and either outcome refuses the
plan before approval. At claim, the existing `C2` §6 check 5
registry/allowlist re-check
MUST re-evaluate the same topology eligibility and refuse with distinct named
reasons: `TARGET_CLUSTERXL_MEMBER_UNSUPPORTED`,
`TARGET_TOPOLOGY_EVIDENCE_MISSING_OR_STALE`, or
`TARGET_TOPOLOGY_EVIDENCE_CONFLICTING`. This is naturally target eligibility,
not folding the ledger predicate into check 5; the ledger predicate remains
independently recorded as check 7 and in the existing claim-time check-4 slot.

**Explicitly non-authoritative, explicitly staleness-tolerant, never a
substitute for the claim-time check.** Check 7 answers exactly the
question §3.1.0's table already assigns to `C7` §5.3's compile-time
column: an early, human-facing, UX/cost-avoidance pass whose result is
**not** carried forward and **not** trusted at claim time. The
authoritative, always-fresh instance of the same fact remains, unchanged
by this decision, `C2` §6 check 4's slot (§3.1.0, relay seq 13) — a plan
that passes check 7 at compile time and is claimed hours later, after a
*new* unreconciled outcome appeared on the same target in the interim, is
still refused at claim by check 4, exactly as §3's own fail-closed
reasoning already establishes. Check 7's own staleness is therefore never
a safety gap — it is `C7` §5.3's own existing "early, non-authoritative"
posture, applied to a seventh fact instead of a novel one.

## 4. `RestoreWriteLedger` — module API sketch (non-binding; a successor movement writes the real module; identity scope and reconciliation representation now resolved, see §4.0)

### 4.0 Two representation questions resolved this pass (identity scope restated in full at §3.1.1: physical device only, VSX out of scope)

**Ledger identity scope: `device_id`, resolved; full scope treatment
(now corrected to physical-only, VSX explicitly out of scope) moved to
§3.1.1 this pass.** The initial DRAFT left open whether this ledger's key
should be `device_id` or the finer-grained `endpoint_id` (`C7` §5.2's
`restore_plan` carries both). **Resolved: `device_id`**, the coarser
scope, for the reason §3.1.1 now states in full (a reconciliation-pending
state on one endpoint casts doubt on the whole physical device, per this
document's own fail-closed posture, §5) — including the ClusterXL
unsupported-until-separately-contracted treatment and the corrected,
evidence-grounded statement that no virtual-system dimension exists in this ledger's scope at all
(§3.1.1), which is not repeated here to avoid two documents disagreeing
by drift.

**Reconciliation representation: append-only, tamper-evident, resolved
(restated unambiguously in §6, this pass).** The initial DRAFT offered a
targeted `UPDATE` of `reconciled_at`/`reconciliation_ref` on the original
attempt row as one option, alongside a second-linked-row alternative,
without choosing. **Resolved: append-only, a second linked row** —
`record_reconciliation` below **inserts** a new
`RestoreWriteLedgerEntry`-shaped reconciliation record referencing the
original attempt's `restore_run_id`, rather than mutating the original
row. Rationale, tied directly to the council finding ("tamper-evident
history"): an `UPDATE` of an existing attempt row means the ledger's own
history of "what did this device's restore-write attempts actually look
like over time" is not append-only, and a corrupted or malicious in-place
edit of that row is indistinguishable from a legitimate reconciliation
without an external audit trail. An append-only model — the original
attempt entry is never mutated; reconciliation is its own new, separately
timestamped entry that references the attempt it closes — means the
ledger's entire history remains a pure sequence of inserts, matching
`RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §8's own "append-only, no deletion
path" invariant exactly, extended here to "no in-place mutation path"
either. `has_unreconciled_prior` (below) is then defined over the
**latest entry for the device_id, of either kind** — an attempt entry with
no later reconciliation entry referencing it is unreconciled; a
reconciliation entry closes exactly the attempt `restore_run_id` it names.
**This subsection's own wording is superseded, where it once hedged, by
§6's unambiguous restatement this pass** — §6 no longer describes this as
"one narrow, audited exception a successor movement's schema review may
keep or replace," which was stale text carried over from before this
subsection's own resolution; append-only is unqualified, full stop.

```python
@dataclass(frozen=True)
class RestoreWriteLedgerEntry:
    device_id: str                 # primary ledger key (§3.1.1) -- a
                                   # physical device identity only; no
                                   # virtual-system dimension exists,
                                   # because no current restore capability
                                   # targets one (§3.1.1); ClusterXL
                                   # member restore is unsupported pending
                                   # its own cross-member safety contract
    endpoint_id: str                # physical endpoint identity (C1 §3.2),
                                   # carried for parity with C7 §5.2's
                                   # restore_plan.target_endpoint_id;
                                   # NOT part of the ledger's blocking key
                                   # (device_id remains the coarser,
                                   # blocking scope per §4.0's original
                                   # resolution) -- recorded for audit/
                                   # traceability against the exact plan
    restore_run_id: str            # C2 jobs.job_id / restore_run.run_id --
                                   # one entry per restore_run (§3.1.2), never
                                   # per individual write step within a run
    restore_approval_id: str       # C7 §6.2 restore_approval.approval_id
    recorded_at: datetime          # tz-aware UTC; written the moment the
                                   # restore_run's FIRST device-write step is
                                   # sent (§3.1.2; mirrors RECOVERY_
                                   # OPERATIONAL_WRITE_LEDGER.md §6's "record
                                   # at send time, not at confirm time",
                                   # applied to the run as a whole)
    outcome: str                   # "applied_verified" | "applied_unverified"
                                   # | "outcome_unknown" | "failed" -- the
                                   # restore_run's OWN terminal outcome
                                   # (§3.1.2), never a per-step judgment this
                                   # ledger has no basis to make more
                                   # precisely than C7/C2 already can
    entry_kind: str                # "attempt" | "reconciliation" (new, §4.0) --
                                   # a "reconciliation" entry's own `outcome` is
                                   # always NOT_APPLICABLE; it exists to close a
                                   # prior "attempt" entry, never to record a
                                   # second attempt
    reconciles_restore_run_id: str | None  # set only on a "reconciliation"
                                            # entry; the attempt row's own
                                            # restore_run_id it closes (§4.0)
    reconciliation_ref: str | None # job_reconciliation.reconciliation_id,
                                   # set only on a "reconciliation" entry


class RestoreWriteLedgerUnreadableError(EvidenceBackendError):
    """The ledger exists but cannot be read/parsed, or the configured Postgres
    backend is unreachable. Fail-closed: the caller MUST NOT admit a new
    restore-write against any target while this ledger cannot be consulted —
    an unreadable ledger cannot prove the absence of an unreconciled prior
    outcome, and the UNKNOWN/fail-closed law forbids treating "cannot tell"
    as "safe to proceed" for a write this consequential (contrast §5 below,
    where the failure mode is the opposite of RB.3b's and the ruling is the
    same posture for a different reason)."""


class RestoreWriteLedger:
    def has_unreconciled_prior(self, *, device_id: str) -> bool:
        """True if the newest "attempt" entry for device_id has outcome
        "outcome_unknown" and no later "reconciliation" entry in this
        ledger names that attempt's restore_run_id (§4.0: append-only —
        a reconciliation is looked up by reference, never by mutating the
        attempt). Scoped by physical device_id only (§3.1.1) -- there is
        no virtual-system dimension to this check; it is not applicable
        because no current restore capability has one, not because a
        deployment configuration chooses to ignore one. Raises
        RestoreWriteLedgerUnreadableError if the store cannot be read —
        never conflates 'unreadable' with 'no unreconciled entry'. This
        method is the one consulted by BOTH the compile-time C7 §5.3
        early-refusal pass and the claim-time C2 §6 check-4-slot
        authoritative check (§3.1.0) -- one implementation, two call
        sites at two different times, never two divergent logics."""

    def record_attempt(self, *, entry: RestoreWriteLedgerEntry) -> None:
        """Append one entry_kind="attempt" entry, called ONCE per
        restore_run (§3.1.2 -- corrected this pass from the prior
        revision's "once per restore-write step," which conflated a
        run's own granularity with its individual steps), at the moment
        the run's first device-write step is sent — mirrors RECOVERY_
        OPERATIONAL_WRITE_LEDGER.md §6's send-time recording rule, not
        confirm-time, applied at the run level."""

    def record_reconciliation(self, *, reconciles_restore_run_id: str,
                               reconciliation_ref: str,
                               reconciled_at: datetime) -> None:
        """Appends one NEW entry_kind="reconciliation" entry referencing
        the attempt it closes (§4.0) -- never mutates the original attempt
        row. Insert-only, matching RECOVERY_OPERATIONAL_WRITE_LEDGER.md
        §8's append-only rule exactly, extended to forbid in-place
        mutation of any existing row, not only deletion."""
```

Backend selection follows `utils/evidence_backend.py`'s existing four-backend
pattern verbatim (`SECURITYEXPERT_EVIDENCE_BACKEND` filesystem/postgres,
`_ensure_schema`, `_write_json_atomic`, `_parse_dt`, `EvidenceBackendError`
reuse) — this document adds a fifth concern to that module the same way
`RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §3 added its fourth, and does not
re-derive the abstraction.

## 5. Fail-closed contract

| Ledger state for `device_id` (append-only, per §4.0/§6; consulted at both compile time and claim time per §3.1.0) | Meaning | Decision |
|---|---|---|
| **Absent** (no entry ever recorded for this device) | no prior restore-write | **admit** (subject to `restore_approval` validity and the rest of `C7` §5.3's/`C2` §6's own batteries) |
| **Readable, latest entry is a `"reconciliation"` entry, or an `"attempt"` entry whose outcome is a terminal non-`outcome_unknown` state** | prior attempt is closed, or was never ambiguous | **admit** |
| **Readable, latest `"attempt"` entry has `outcome = "outcome_unknown"` and no later `"reconciliation"` entry names its `restore_run_id`** | prior attempt unresolved | **BLOCK** — refuse job claim; this is the substantive check, not a degrade case |
| **Unreadable** (corrupt store, unreachable Postgres, query error) | cannot tell whether an unreconciled prior attempt exists | **BLOCK** — `RestoreWriteLedgerUnreadableError`; no device command sent |

The "unreadable ⇒ block" row is the same shape `RECOVERY_OPERATIONAL_WRITE_
LEDGER.md` §5 uses, but for a different reason: RB.3b blocks an unreadable
ledger because it cannot prove a recent backup exists (a false-proceed there
risks a redundant disk-consuming write); this ledger blocks an unreadable
read because it cannot prove a target is **not** carrying an unresolved,
possibly-mid-outage restore outcome (a false-proceed here risks compiling a
second restore-write against a device whose actual configuration state is
still unconfirmed per `C7` §5.7's cases 2/3) — a strictly more severe
consequence than a missed backup, so the same fail-closed shape is applied
with a stronger, not merely analogous, justification. This table's
decision is the **same** at both the `C7` §5.3 compile-time consultation
and the `C2` §6 claim-time consultation (§3.1.0) — only the *authority* of
the two consultations differs (non-authoritative vs. authoritative), never
the underlying fail-closed logic itself.

## 6. Retention and privacy

Same posture as `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §9: `device_id` and
`endpoint_id` are already `safe_component`-shaped identifiers, per `C1`
§3.2 — no virtual-system identity shape is carried by this ledger at all
(§3.1.1: no current restore capability has one to carry); `restore_run_id`/
`restore_approval_id`/`reconciliation_ref` are internal identifiers;
timestamps and `outcome` are value-free. **No manifest path, no artefact
bytes, no restore payload, no credential, no raw device transcript ever
enters this ledger** — the `RAW-RETENTION` (D-2d) default-NO and `C7`
§1.4's raw-retention-vs-artefact distinction both apply unchanged; this
ledger is neither the artefact store
nor a device-transcript log.

**Append-only, stated unambiguously (revised this pass — a council finding
named the prior wording as still hedged).** Every row this ledger ever
writes is an **insert**, with **no exception of any kind**: `record_attempt`
inserts one `entry_kind="attempt"` row; `record_reconciliation` inserts one
`entry_kind="reconciliation"` row that references an existing attempt by
`reconciles_restore_run_id` (§4). **No code path in this design ever issues
an `UPDATE` or a `DELETE` against any row this ledger has ever written, for
any reason, including reconciliation.** This is not "append-only with one
narrow exception a later schema review may keep or replace" — that was the
first revision's own leftover hedge from before §4.0 fully resolved the
representation question, and it is retracted here: the representation
question is **closed**, and the invariant it closed to is the unqualified
one stated in this paragraph. `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §8's
own "insert-only... no code path issues `UPDATE` or `DELETE`" rule is
matched exactly, not merely approximated, and §8 below (test obligation
(g)) is this ledger's own static/property-test equivalent of that rule.

## 7. Relationship to `C2` and `C7` — no new job-execution mechanism, `C7` check mapping made explicit (revised per relay seq 13 and this pass's council-informed check-7 decision)

This document adds **zero** new fields to `restore_plan`/`restore_run`/
`restore_approval` (`C7` §5.2/§5.6/§6.2, all unchanged) and **zero** new
step kinds beyond the one already proposed in the amendment bundle
(`restore_push`, item 2 of the D1 follow-up list). §3.1.0's table is the
authoritative statement of exactly which check, in which document, at
which time, carries which fact — restated here in one sentence per
council's "make the `C7` check mapping explicit" finding: **connectivity**
is an early, non-authoritative pass at `C7` §5.3 check 1 (compile time,
in-place, no new row) and an authoritative, freshness-policy-consulting
pass at `C2` §6 check 2 (claim time, scope extended to this class,
in-place, no new row); **no-unreconciled-prior** is an early,
non-authoritative pass at **`C7` §5.3's new check 7** (compile time,
appended after check 6, per this pass's council-informed decision — the
one genuinely additive row in `C7`'s own battery) and an authoritative,
independently named pass at `C2` §6 check 4's existing, already-class-
scoped slot (claim time, scope extended to this class per relay seq 13,
in-place, no new row). §3.4's retry-rule row is the other genuinely
additive row, in `C2` §5.3's existing per-class retry table. None of
these amendments introduces a new state or transition to `C2`'s state
machine (§3.1 of that document, unchanged); `C7`'s own precondition
battery grows from six checks to seven (check 7 appended, checks 1–6
unchanged in number, order, and text) rather than changing shape. The new
action class's `permitted` predicate, evaluated at `C2` claim time
alongside the class's own gate-resolution outcome (`C4` §3's algorithm,
unchanged, per §3.0 above's two-predicate split — **untouched by this
revision**, exactly as relay seq 13 itself reaffirms), consults this
ledger directly at claim (§3.1.0's authoritative row) the same way
`CLASS_1_RECOVERY_WRITE`'s admission consults `RECOVERY_OPERATIONAL_WRITE_
LEDGER.md` — a sibling check, not a parallel execution path.

## 8. Test obligations a successor implementation movement would need (descriptive, not delivered here)

No test file is added or edited by this document. Listed so a successor
movement's `TARGETED_TEST` plan is not written from nothing; updated this
pass for the compile-time/claim-time distinction (§3.1.0), the explicit
`C2` §6 check-4-slot claim-time check (§3.1), `C7` §5.3's new check 7
(§3.7), `restore_run` cardinality (§3.1.2), VSX/ClusterXL scoping
(§3.1.1), and the unambiguous append-only restatement (§6):


- (a) a restore-write job claim against a device with no unreconciled prior
  entry, valid approval, and a passing precondition battery is admitted;
- (b) a restore-write job claim against a device with an `outcome_unknown`,
  unreconciled prior `"attempt"` entry is refused **at the `C2` §6
  check-4-slot claim-time check specifically** (§3.1.0), zero device
  contact, regardless of whether `C7` §5.3's own compile-time pass (run
  earlier, against possibly-stale state) happened to pass;
- (b2) **(new)** a `restore_plan` whose `C7` §5.3 compile-time pass
  succeeded (no unreconciled prior entry existed at compile time) is
  still refused at `C2` claim if a **new** unreconciled entry appeared on
  the same target in the interim — the scenario §3.1's own revision note
  names as the reason compile-time-only was insufficient; this is the one
  test that most directly exercises relay seq 13's own rationale;
- (c) an unreadable ledger blocks admission at claim time (filesystem:
  corrupt JSON; Postgres: unreachable DSN), mirroring RB.3b's obligation
  (b);
- (d) `record_reconciliation` appends a new `"reconciliation"` entry
  naming the matching prior `"attempt"` entry's `restore_run_id`, and the
  **original attempt row is byte-for-byte unchanged** afterward (the
  unambiguous append-only assertion §6 now states without qualification);
  a **new** `restore_plan`/job compiled per `C7` §5.7's
  `superseding_prior_run_id` rule is then admitted — never the same job
  re-claimed;
- (e) filesystem and Postgres backends return the same admit/block decision
  for the same synthetic history (mirroring RB.3b obligation (d));
- (f) the ledger read occurs inside the same admission section `C2` claim
  processes, never before a job is legitimately claimable, and never
  bypassable by a direct call outside that path;
- (g) no code path issues an `UPDATE` or `DELETE` against any existing
  ledger row, ever, for any reason — a static/property test mirroring
  `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §10 obligation (e), now the
  direct test of §6's unqualified append-only restatement;
- (h) `test_the_five_classes_exist_and_are_ordered` and a new sibling
  assertion guarding `controlled-restore-write`'s non-console-
  submittability are both updated together (§3.2's inventory) — a test
  asserting one without the other is an incomplete edit;
- (i) a restore-write job claim whose active-probe connectivity evidence
  fails (`C2` §6 check 2, extended per §3.1.0/§3.3) is refused before
  device contact; a cached-telemetry evidence source configured but
  failing any of §3.3's four conditions (TTL, identity binding, semantic
  sufficiency, explicit configuration) falls back to a fresh active probe,
  never silently proceeds — both paths tested independently;
- (j) `evidence_source="cached_telemetry"` for a vendor/platform pair with
  no entry in §3.3.1's keyed `cached_evidence_semantic_sufficiency_
  contract_ref` mapping is refused at policy-validation time (never
  reaches claim time) — the one condition in §3.3's table this document
  treats as non-negotiable, now asserted per-pair rather than globally;
- (k) `audit_evidence_source_used`, `audit_evidence_age_at_use_seconds`,
  `audit_fallback_triggered`, and `audit_policy_config_snapshot_ref` are
  recorded on every claim attempt, regardless of admit/block outcome — an
  audit trail assertion, not merely a functional one;
- (l) **(new)** a `restore_run` whose capability's step sequence contains
  multiple device-write steps (transfer, then a separate apply/commit
  step, `C7` §5.7 case 3) produces exactly **one** ledger `"attempt"`
  entry for the whole run, written at the first write step's send time,
  never one entry per step (§3.1.2's cardinality rule);
- (m) **(new)** a `BLOCKED`-at-claim job (refused by the check-4-slot
  check, or by any other `C2` §6 check) remains `REQUESTED` and is
  claimable again on a future cycle without a new `restore_plan`, new
  approval, or reconciliation — distinguished from a `CLAIMED`/`EXECUTING`
  job's own never-auto-retry rule (§3.4), which this test asserts does
  **not** apply to a job that was never claimed (§3.1.3);
- (n) **(revised per relay seq 17)** ClusterXL member targets are refused
  as unsupported before any restore-write claim is admitted; no test may
  infer that member B is safe to restore merely because member A has a
  different `device_id`. A future ClusterXL restore contract must add its
  own cross-member safety cases. **(removed)** the prior revision's VSX-
  scoping test remains dropped — VSX virtual-system-context restore is out
  of scope entirely (§3.1.1), so no test asserts behavior for a target
  dimension this ledger does not model;
- (o) **(new)** `C7` §5.3's compile-time battery reports exactly seven
  checks (`test_the...battery` equivalent asserting the count/order),
  preserving the separate check-1 connectivity amendment, leaving checks
  2–6 unchanged, and appending check 7 last —
  a static/schema-level test distinct from (b2) above, which exercises
  the *behavioral* staleness gap between compile-time and claim-time;
  this test asserts only the *structural* seven-check shape;
- (p) **(new)** check 7's own failure at compile time is recorded and
  surfaced as its own named refusal reason, never merged into check 2's
  or check 5's own outcome field — the direct test of this pass's
  council-informed "independently tracked, never blended" requirement.

## 9. Open items / unresolved semantics for review

Several items the initial DRAFT left fully open are now resolved (§4.0,
§3.2) per this pass's governing PO decisions and council findings; §3.3's
freshness bound was **resolved-then-reopened** by the PO's own
clarification (a fixed number was rejected in favor of configurable
policy) — recorded honestly as such, not silently smoothed over. This
pass's own seq 13 decision **narrowly reopened and re-resolved** the
reconciliation-check placement (compile-time fold alone → compile-time
early pass **plus** an explicit claim-time check, §3.1) — also recorded
honestly, not silently smoothed over. **This pass's own PO review decision
additionally corrects item 5 below** (VSX scoping), which the prior
revision had wrongly framed as an open policy choice rather than a
question the repository's own evidence already answers — the correction
is recorded as such, not silently rewritten as if it had always read this
way. **A council-informed PO decision (seq 17) has now also resolved what
was previously this section's own open compile-time-mapping item**: the
compile-time pass is a new `C7` §5.3 check 7 (§3.7), not a fold into
check 2 or check 5, per two independent council seats' shared
maintainability/fail-closed objection to folding. What remains genuinely
open is narrower still:

1. **Freeze and implementation are separate gates.** This document and
   `docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` are reviewed
   together and may be frozen together after the required GOV.PO.1 §7
   council round and Product Owner decision. That freeze makes the later
   amendment movement deterministic; it does **not** create the class or
   authorize code. No taxonomy/C4/C7/C2 amendment, test change, Java/UI
   implementation, or device-write path may begin until a separately
   authorized movement first records Option A in the frozen baseline and
   applies the frozen-document amendments in the bundle's declared order.
2. **The exact Flyway/table-level schema for the append-only
   reconciliation model** (§4.0) — whether `"attempt"` and
   `"reconciliation"` are one table with an `entry_kind` discriminator
   column (as sketched) or two separate tables sharing a `device_id`/
   `restore_run_id` foreign key — is left to whichever movement writes the
   real Postgres/filesystem schema; both satisfy §6's unqualified
   append-only invariant identically, and this document does not mandate
   one over the other.
3. **§3.3's connectivity freshness policy has no decided numeric value —
   by explicit PO instruction, not by omission.** The proposed 15-minute
   bound from the prior revision is **rejected**; `active_probe_timeout_s`
   and `cached_evidence_ttl_seconds` are `UNKNOWN`, with only illustrative
   ranges named for discussion. The successor movement (or council) sets
   real numbers informed by actual per-vendor probe-latency evidence this
   document does not have — this item is intentionally left open, not a
   gap to silently fill later.
4. **No vendor/platform pair has an entry in §3.3.1's keyed semantic-
   sufficiency contract mapping today** — this document does not claim one
   exists for Check Point Gaia, PAN-OS, or any other managed platform;
   until a specific pair's contract is written and passes
   `GOV_PO_ROLE_MIGRATION.md` §7 trigger (b) council review (§3.3.1),
   `evidence_source` should default to `active_probe` for every real
   deployment, and `cached_telemetry` remains a documented-but-unusable
   option for every vendor/platform pair.
5. **RESOLVED THIS PASS, no longer open: VSX virtual-system-context
   restore is out of scope, not a conservative-default policy choice
   (§3.1.1).** The prior revision of this item read as though a
   `virtual_system_ref`-scoped restore capability already existed and
   this ledger merely had to pick a conservative blocking default for it.
   Checked against `C7` §5.2's actual `restore_plan` schema (no
   `virtual_system_ref` field) and `BACKUP_RECOVERY_CONTRACTS.md` §7.3
   point 7/§7.7's FROZEN text (Check Point Gaia backup is categorically
   excluded from VSX virtual-system contexts), no such capability exists
   or is implied to exist — this item is retired as an open policy
   question and restated as a scope statement in §3.1.1 instead. **Any
   future VSX-context restore is its own, separate vendor/platform-and-
   target-scope contract**, not a parameter of this ledger.
6. **RESOLVED FOR CURRENT SCOPE by relay seq 17: ClusterXL member restore
   is unsupported, not admitted under this contract.** Per-member
   `device_id` separation proves identity separation only; it does not
   establish operational independence or cross-member restore safety.
   `C7` §9.8 is not the owner of this question because it concerns CP
   management-HA consistency groups, not ClusterXL gateway members. Any
   future ClusterXL physical-member restore therefore requires a separate
   vendor/platform, target-scope, and cross-member safety contract before
   implementation or admission is authorized.
7. **Whether a `BLOCKED`-at-claim `REQUESTED` job should ever auto-expire
   or auto-cancel (§3.1.3) is a generic `C2` job-lifecycle question** this
   document does not have the scope to answer — named so it is not
   silently assumed either way (neither "it retries forever" nor "it
   expires after N attempts" is asserted).
8. **Restore-write's own gate-registry rows** (the literal `restore_push`
   command/call template's ten-field gate entry — vendor, timeout, retry,
   frequency, session reuse, unsupported behavior, secret-output risk, safe
   telemetry) are not specified by this document; they are per-vendor,
   per-capability detail that `C4` §2.4's worked-example pattern already
   shows how to produce once a concrete restore capability is authored —
   out of scope here, named so it is not mistaken for already covered.
9. **§3.2's inventory is scoped to this repository** — it cannot rule out
   an external client of `console/app.py`'s `action_class_level` field
   parsing it as a strict integer; that residual risk is named, not
   closed, since it is outside what this repository's own source can
   confirm.

## 10. Cross-references

- `docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md` — the
  decision document this contract implements item 3 of.
- `docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` — the
  literal proposed edits (D1 follow-up items 1, 2, 4, 5) this contract's
  new class/step kind presuppose.
- `docs/design/UI2_0_D1_OPTION_A_CONSOLIDATED_REVIEW.md` — the primary
  review entry point for this revision pass; walks through every council
  finding and names exactly what changed in this document and in the
  amendment bundle.
- `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` (FROZEN)
  §5.2–§5.7, §6.2, §9.8 — unchanged, referenced not restated; §9.8 is
  cited only to delimit its CP management-HA consistency-group scope and
  is explicitly not treated as owning ClusterXL gateway-member safety;
  §5.3's own check-4 note is the subject of
  §3.6's proposed companion amendment, and §5.3's own six-check table is
  the subject of §3.7's proposed new check 7 (council-informed, relay
  seq 17).
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
  (FROZEN) §2.3, §3.2, §3.3, §3.5, §4.2–§4.4 (VSX/ClusterXL target model,
  informing §3.1.1's identity treatment but not supplying ClusterXL
  restore-safety semantics, and this pass's VSX correction) —
  unchanged, referenced not restated.
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (FROZEN) §3.1 (state
  machine), §5.3, §6 — unchanged; §3.1/§3.4 above propose text for a
  successor movement to add, including the check-4-slot repurposing.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3 point 7, §7.7 (FROZEN) —
  the Check Point Gaia VSX-exclusion evidence §3.1.1's this-pass
  correction is grounded in, quoted not paraphrased.
- `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` — the fail-closed and
  evidence-plane-placement precedent; §1 above states exactly where this
  document diverges from it and why.
- `docs/design/GOV_PO_ROLE_MIGRATION.md` §7 — council-trigger process this
  document's own eventual freeze must pass through, and the process any
  future semantic-sufficiency contract (§3.3.1) or genuinely new
  VSX-context restore contract (§3.1.1) must itself pass before being
  referenced or built.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate" — §3.5
  above proposes the addition that closes this gap.
- `relay/NXS-LOCAL-0060-ui2-d1-device-write-class-decision.json` seq 5, 7,
  9, 13, 17, plus a PO chat clarification rejecting the 15-minute
  freshness value and a PO review decision correcting this document's
  own VSX framing — the governing decisions behind this document's every
  revision.
