# Controlled restore-write admission contract — design

**Status: DRAFT — CANDIDATE FOR PRODUCT OWNER REVIEW / NOT FROZEN.** This
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
taken through council. **Before this document may move from DRAFT to
FROZEN, it must go through the `nexus-decision-council` skill's bounded
review, invoked from a `nexus-po` `PLAN` or `DECIDE` episode**
(`docs/design/GOV_PO_ROLE_MIGRATION.md` §7, council trigger (b): "a freeze
candidate introducing a security, identity, credential, storage-schema or
write boundary" — this is exactly that kind of freeze candidate). No
engineering session invokes that council directly.

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

### 3.0 Static sign-off and runtime admission are separate predicates, not one blended gate

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

A `controlled-restore-write` capability that is `SIGNED_OFF` (predicate 1)
but whose target has an unreconciled prior outcome (predicate 2, folded
into `C7` §5.3 check 2 per §3.1 below) is refused at claim — the row's
existence in the registry never substitutes for this document's own
runtime check, and vice versa: a target with no unreconciled history but a
capability row that is not (or not yet) `SIGNED_OFF` is refused by `C4`
§3.5's execution-eligible view
before this document's checks are ever reached. Neither predicate is
sufficient alone; both are necessary, evaluated by different documents, at
different times, never merged into one combined score.

### 3.1 Predicate 2 in full — folded into `C7` §5.3's existing precondition-battery model (revised per relay seq 9; supersedes this document's original standalone-`C2`-check proposal)

**Revision note.** The initial DRAFT of this section proposed a brand-new,
standalone `C2` §6 check (row 7) to carry predicate 2's three facts. The
Product Owner's relay seq 9 decision supersedes that proposal: *"fold the
restore ledger admission facts into `C7`'s existing check 1/check 2 model;
do not retain a separate check 7."* This subsection is rewritten to state
the folded model directly; §3.4 below (renamed) now covers only the one
genuinely separate `C2` concern that decision does not touch — the
per-class **retry rule**, which is not an admission check at all.

Predicate 2's three original facts map onto `C7` §5.3's existing
compile-time precondition battery (unchanged in shape — six checks,
re-verified fresh at `C2` claim per that document's own text) as follows:

| Original fact | Disposition |
|---|---|
| Valid, unexpired, unrevoked `restore_approval` for this `plan_id`, `requested_by ≠ approved_by` | **Already covered, no fold needed.** `C7` §6.2 states this is already `C2` §6 check 1's "restore-specific instance" — existing FROZEN text, unmodified by this document at any point. |
| Connectivity evidence fresh enough to trust | **Folds into `C7` §5.3 check 1** (Connectivity) — the natural, direct home; that check already names connectivity as its subject, and §3.3 below (revised) supplies the configurable freshness policy this check now consults instead of an unstated "bounded freshness window." |
| No unreconciled prior restore-write outcome against this target | **Proposed fold: `C7` §5.3 check 2** (currently "Backup validity — ... `V2`+"), reframed to a broader "artefact and target admission validity" check that additionally requires this ledger's `has_unreconciled_prior` to be `False`. This is **this document's own proposed mapping, not the only defensible one** — check 5 ("no concurrent restore or backup against the same target") is thematically closer in one sense (both gate "is this target currently blocked"), but check 5's own text is scoped to *live* `REQUESTED`/`CLAIMED`/`EXECUTING` jobs, not a *terminal-but-unreconciled* outcome, which is a different temporal concept; check 2 is proposed instead because the PO's own decision names "check 1/check 2" specifically. A reviewer or the council may re-map this to check 5 (or split it as its own bullet within check 2's existing text) without disturbing anything else in this document. |

No new row is added to `C7` §5.3's table under this fold — checks 1 and 2
are **amended in place** (their existing numbers, existing position in the
ordered battery, existing re-verification-at-claim behavior all unchanged);
this is a smaller, more conservative edit than the original DRAFT's
standalone new-check proposal, and it reuses a battery that already runs
at both compile time and claim time rather than adding a second parallel
mechanism `C2` would also have to invoke.

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
| **Semantic sufficiency** | **SNMP (or any read-only telemetry protocol) reachability does NOT by itself prove the write channel (`ssh_exec`/`xml_api_call`) is usable**, and this document does not claim otherwise. A cached-telemetry source may be used as claim-time evidence **only if a vendor-specific contract (a gate-registry-equivalent, reviewed document) establishes that this particular telemetry signal is a sufficient proxy for this particular write channel's own readiness** for the vendor/platform in question. Absent such an established contract, cached telemetry is **never sufficient alone**, regardless of how fresh or well-identity-bound it is — this is the one condition this document treats as a hard requirement, not a tunable, because it is a semantic claim about vendor behavior this document has no evidence for (see §3.3.2's research note below on why even public third-party documentation does not substitute for this). |
| **Explicit configuration** | The evidence source for a given deployment/vendor-class is an explicit, named policy choice (§3.3.1's `evidence_source` field) — never an implicit default inferred from "a cache happens to exist." |

Any condition failing (stale TTL, no identity binding, no established
semantic-sufficiency contract, or no explicit configuration) means
**fail closed to path (1)**: a fresh active probe is required before the
claim can proceed; the claim is never admitted on the strength of
insufficient cached evidence, and a probe failure/timeout is `BLOCKED`,
never treated as "no evidence, so proceed."

#### 3.3.1 Proposed policy schema (fields, precedence, bounds as proposals — none frozen)

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
    cached_evidence_semantic_sufficiency_contract_ref: str | None
        # a pointer to the vendor-specific contract establishing this
        # telemetry signal proves this write channel's readiness; None
        # means "no such contract exists yet" and cached_telemetry MUST
        # NOT be selected as evidence_source regardless of any other field

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

### 3.4 The `C2` retry rule this class needs (proposed; `C2` itself unedited; admission checks are no longer proposed here — see §3.1)

`docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (FROZEN) §5.3's
per-action-class retry table is not edited by this document. **Revision
note:** the original DRAFT proposed both a new `C2` §6 admission check
(row 7) and this retry-rule row in the same subsection. Per relay seq 9,
the admission check is withdrawn — those facts now fold into `C7` §5.3
checks 1/2 (§3.1 above). Only the retry-rule proposal below survives
unchanged from the original DRAFT, because it answers a genuinely
different question (what happens on failure/retry) that the seq 9
decision does not touch:

| Action class | Retry rule |
|---|---|
| `CLASS_1B_CONTROLLED_RESTORE_WRITE` (`controlled-restore-write`) | **Never auto-retries, at any stage, for any reason** — identical rule to `CLASS_1_RECOVERY_WRITE`'s row, for a stronger reason: `C7` §5.7 already mandates this at the job-state level ("closes only via `RECONCILED`... no exception for restore"; a new attempt is always a **new** `restore_plan`/job, never a retried one). This row makes `C2`'s own per-class retry table state the same rule explicitly, rather than leaving the new class implicitly covered only by `C7`'s restatement of `C2` §3.5's generic mechanism. |

This proposed row is additive to `C2`'s existing retry table; no existing
row, class-0/1/2/3/4 rule, or `C7` check is modified beyond §3.1's
in-place amendment of `C7` §5.3 checks 1/2.

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
reconciliation-pending ledger, `C7` §6.2 per-operation approval, `C7` §5.3
precondition battery) **and** an approved gate entry whose
`sign_off_state` may reach `SIGNED_OFF` only for a capability row scoped to
a `restore_push` step (or a restore-apply `exec`/`poll` step) under
`C4` §3.3 step 7's restated rule — mirroring class 1's own two-part
requirement (recovery contracts **and** gate entry) with the class-1.5
equivalents substituted for each part."* This keeps the section's existing
shape (a stated contract requirement plus a stated gate-entry requirement)
rather than inventing a new shape for one class.

## 4. `RestoreWriteLedger` — module API sketch (non-binding; a successor movement writes the real module; identity scope and reconciliation representation now resolved, see §4.0)

### 4.0 Two representation questions resolved this pass

**Ledger identity scope: `device_id`, resolved.** The initial DRAFT left
open whether this ledger's key should be `device_id` or the finer-grained
`endpoint_id` (`C7` §5.2's `restore_plan` carries both). **Resolved:
`device_id`**, the coarser scope. Rationale: a reconciliation-pending state
on any one endpoint of a multi-endpoint device means that device's actual
configuration is not fully confirmed — admitting a second restore-write
against a *different* endpoint of the *same physically uncertain device*
does not reduce risk, it compounds it (a device mid-outage on one endpoint
is not verifiably healthy on another until the whole device is
reconciled). This is the more conservative reading and matches this
document's own fail-closed posture elsewhere (§5); it is not the only
defensible choice, but it is the one consistent with every other
fail-closed rule in this document, so it is adopted rather than left open.

**Reconciliation representation: append-only, tamper-evident, resolved.**
The initial DRAFT offered a targeted `UPDATE` of `reconciled_at`/
`reconciliation_ref` on the original attempt row as one option, alongside a
second-linked-row alternative, without choosing. **Resolved: append-only,
a second linked row** — `record_reconciliation` below **inserts** a new
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

```python
@dataclass(frozen=True)
class RestoreWriteLedgerEntry:
    device_id: str
    restore_run_id: str            # C2 jobs.job_id / restore_run.run_id
    restore_approval_id: str       # C7 §6.2 restore_approval.approval_id
    recorded_at: datetime          # tz-aware UTC; written the moment the
                                   # restore-write step is sent (mirrors
                                   # RECOVERY_OPERATIONAL_WRITE_LEDGER.md §6's
                                   # "record at send time, not at confirm time")
    outcome: str                   # "applied_verified" | "applied_unverified"
                                   # | "outcome_unknown" | "failed"
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
        attempt). Raises RestoreWriteLedgerUnreadableError if the store
        cannot be read — never conflates 'unreadable' with 'no
        unreconciled entry'."""

    def record_attempt(self, *, entry: RestoreWriteLedgerEntry) -> None:
        """Append one entry_kind="attempt" entry, called once per
        restore-write step the moment it is sent to the device — mirrors
        RECOVERY_OPERATIONAL_WRITE_LEDGER.md §6's send-time recording
        rule, not confirm-time."""

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

| Ledger state for `device_id` (append-only, per §4.0) | Meaning | Decision |
|---|---|---|
| **Absent** (no entry ever recorded for this device) | no prior restore-write | **admit** (subject to checks 1 and 3) |
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
with a stronger, not merely analogous, justification.

## 6. Retention and privacy

Same posture as `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §9: `device_id` is
already a `safe_component`; `restore_run_id`/`restore_approval_id`/
`reconciliation_ref` are internal identifiers; timestamps and `outcome` are
value-free. **No manifest path, no artefact bytes, no restore payload, no
credential, no raw device transcript ever enters this ledger** — the
`RAW-RETENTION` (D-2d) default-NO and `C7` §1.4's raw-retention-vs-artefact
distinction both apply unchanged; this ledger is neither the artefact store
nor a device-transcript log. Append-only at the attempt-record level (§4's
`reconciliation_ref` note names the one narrow, audited exception a
successor movement's schema review may keep or replace with a
second-row model).

## 7. Relationship to `C2` and `C7` — no new job-execution mechanism

This document adds **zero** new fields to `restore_plan`/`restore_run`/
`restore_approval` (`C7` §5.2/§5.6/§6.2, all unchanged) and **zero** new
step kinds beyond the one already proposed in the amendment bundle
(`restore_push`, item 2 of the D1 follow-up list). Per relay seq 9, §3.1
above folds the ledger's admission facts into `C7` §5.3's existing checks
1/2 **in place** — no new row, no new table, no new mechanism in either
`C2` or `C7`; only §3.4's retry-rule row is a genuinely additive row, in
`C2` §5.3's existing per-class retry table. Neither amendment introduces a
new state or transition to `C2`'s state machine (§3.1 of that document,
unchanged) or to `C7`'s own precondition-battery shape (six checks,
compile-time then re-verified at claim, unchanged). The new action class's
`permitted` predicate, evaluated at `C2` claim time alongside the class's
own gate-resolution outcome (`C4` §3's algorithm, unchanged, per §3.0
above's two-predicate split), consults `C7`'s own (amended) precondition
results the same way `CLASS_1_RECOVERY_WRITE`'s admission consults
`RECOVERY_OPERATIONAL_WRITE_LEDGER.md` — a sibling check, not a parallel
execution path.

## 8. Test obligations a successor implementation movement would need (descriptive, not delivered here)

No test file is added or edited by this document. Listed so a successor
movement's `TARGETED_TEST` plan is not written from nothing; updated this
pass for the append-only reconciliation model (§4.0), the `C7`-fold model
(§3.1), and the configurable freshness policy (§3.3):


- (a) a restore-write job claim against a device with no unreconciled prior
  entry, valid approval, and a passing precondition battery is admitted;
- (b) a restore-write job claim against a device with an `outcome_unknown`,
  unreconciled prior `"attempt"` entry is refused, zero device contact,
  before `C2`'s own claim-time checks even run;
- (c) an unreadable ledger blocks admission (filesystem: corrupt JSON;
  Postgres: unreachable DSN), mirroring RB.3b's obligation (b);
- (d) `record_reconciliation` appends a new `"reconciliation"` entry
  naming the matching prior `"attempt"` entry's `restore_run_id`, and the
  **original attempt row is byte-for-byte unchanged** afterward (the
  append-only assertion this pass's §4.0 revision requires); a **new**
  `restore_plan`/job compiled per `C7` §5.7's `superseding_prior_run_id`
  rule is then admitted — never the same job re-claimed;
- (e) filesystem and Postgres backends return the same admit/block decision
  for the same synthetic history (mirroring RB.3b obligation (d));
- (f) the ledger read occurs inside the same admission section `C2` claim
  processes, never before a job is legitimately claimable, and never
  bypassable by a direct call outside that path;
- (g) **(new)** no code path issues an `UPDATE` or `DELETE` against any
  existing ledger row — a static/property test mirroring
  `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §10 obligation (e), extended here
  to cover reconciliation specifically, since §4.0 makes append-only a
  load-bearing property of this ledger, not an incidental implementation
  choice;
- (h) **(new)** `test_the_five_classes_exist_and_are_ordered` and a new
  sibling assertion guarding `controlled-restore-write`'s
  non-console-submittability are both updated together (§3.2's inventory)
  — a test asserting one without the other is an incomplete edit;
- (i) **(revised)** a restore-write job claim whose active-probe connectivity
  evidence fails (`C7` §5.3 check 1, amended per §3.1/§3.3) is refused
  before device contact; a cached-telemetry evidence source configured but
  failing any of §3.3's four conditions (TTL, identity binding, semantic
  sufficiency, explicit configuration) falls back to a fresh active probe,
  never silently proceeds — both paths tested independently;
- (j) **(new)** `evidence_source="cached_telemetry"` with no
  `cached_evidence_semantic_sufficiency_contract_ref` set is refused at
  policy-validation time (never reaches claim time) — the one condition
  in §3.3's table this document treats as non-negotiable;
- (k) **(new)** `audit_evidence_source_used`, `audit_evidence_age_at_use_
  seconds`, `audit_fallback_triggered`, and `audit_policy_config_snapshot_
  ref` are recorded on every claim attempt, regardless of admit/block
  outcome — an audit trail assertion, not merely a functional one.

## 9. Open items / unresolved semantics for review

Several items the initial DRAFT left fully open are now resolved (§4.0,
§3.2) per this pass's governing PO decisions and council findings; §3.3's
freshness bound was **resolved-then-reopened** by the PO's own
clarification (a fixed number was rejected in favor of configurable
policy) — recorded honestly as such, not silently smoothed over. What
remains genuinely open is narrower:

1. **This document's own freeze path is gated on the taxonomy/`C4`
   amendments it presupposes landing first** — it specifies the admission
   contract *for* the new class, but the class does not exist until
   `docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` step 1 is
   itself reviewed and applied; this document and that bundle are designed
   to be reviewed together, in one council round if GOV.PO.1 §7's trigger
   (b) is invoked, not sequentially.
2. **The exact Flyway/table-level schema for the append-only
   reconciliation model** (§4.0) — whether `"attempt"` and
   `"reconciliation"` are one table with an `entry_kind` discriminator
   column (as sketched) or two separate tables sharing a `device_id`/
   `restore_run_id` foreign key — is left to whichever movement writes the
   real Postgres/filesystem schema; both satisfy §4.0's append-only
   invariant identically, and this document does not mandate one over the
   other.
3. **§3.3's connectivity freshness policy has no decided numeric value —
   by explicit PO instruction, not by omission.** The proposed 15-minute
   bound from the prior revision is **rejected**; `active_probe_timeout_s`
   and `cached_evidence_ttl_seconds` are `UNKNOWN`, with only illustrative
   ranges named for discussion. The successor movement (or council) sets
   real numbers informed by actual per-vendor probe-latency evidence this
   document does not have — this item is intentionally left open, not a
   gap to silently fill later.
4. **Whether any vendor/platform this repository actually targets has an
   established semantic-sufficiency contract for cached telemetry
   (§3.3's hard condition) is itself `UNKNOWN`** — this document does not
   claim one exists for Check Point Gaia, PAN-OS, or any other managed
   platform; until one is written and reviewed, `evidence_source` should
   default to `active_probe` for every real deployment, and
   `cached_telemetry` remains a documented-but-unusable option.
5. **Restore-write's own gate-registry rows** (the literal `restore_push`
   command/call template's ten-field gate entry — vendor, timeout, retry,
   frequency, session reuse, unsupported behavior, secret-output risk, safe
   telemetry) are not specified by this document; they are per-vendor,
   per-capability detail that `C4` §2.4's worked-example pattern already
   shows how to produce once a concrete restore capability is authored —
   out of scope here, named so it is not mistaken for already covered.
6. **§3.2's inventory is scoped to this repository** — it cannot rule out
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
  §5.2–§5.7, §6.2 — unchanged, referenced not restated.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
  (FROZEN) §2.3, §3.2, §3.3, §3.5 — unchanged, referenced not restated.
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (FROZEN) §5.3, §6 —
  unchanged; §3.4 above proposes text for a successor movement to add.
- `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` — the fail-closed and
  evidence-plane-placement precedent; §1 above states exactly where this
  document diverges from it and why.
- `docs/design/GOV_PO_ROLE_MIGRATION.md` §7 — council-trigger process this
  document's own eventual freeze must pass through.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate" — §3.5
  above proposes the addition that closes this gap.
- `relay/NXS-LOCAL-0060-ui2-d1-device-write-class-decision.json` seq 5, 7 —
  the two PO decisions governing this revision.
