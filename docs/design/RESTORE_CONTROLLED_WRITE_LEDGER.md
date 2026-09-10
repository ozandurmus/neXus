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
but whose target has an unreconciled prior outcome (predicate 2, §3.2
check 2 below) is refused at claim — the row's existence in the registry
never substitutes for this document's own runtime check, and vice versa: a
target with no unreconciled history but a capability row that is not (or
not yet) `SIGNED_OFF` is refused by `C4` §3.5's execution-eligible view
before this document's checks are ever reached. Neither predicate is
sufficient alone; both are necessary, evaluated by different documents, at
different times, never merged into one combined score.

### 3.1 Predicate 2 in full — the runtime admission battery (three checks, none of them new machinery)

This class's `C2`-claim-time admission (predicate 2 above) is the
conjunction of three checks, run inside `C2` §6's own pre-execution
battery as an added, ordered check (§3.4 below proposes its exact row and
number), **never before that battery, never replacing any existing check
in it**:

| # | Check | Source of truth | Failure behavior |
|---|---|---|---|
| 1 | **Valid, unexpired, unrevoked `restore_approval` row exists for this exact `plan_id`**, `requested_by ≠ approved_by` | `C7` §6.2's `restore_approval` table (unchanged, not redefined here) | Refuse job claim; no device contact |
| 2 | **No unreconciled prior restore-write outcome against this `device_id`** — this ledger's own new check (§1 point 2 above) | This document's `RestoreWriteLedger` (§4 below) | Refuse job claim; no device contact; surfaced reason names the unreconciled prior `restore_run_id` |
| 3 | **`C7` §5.3's six-check compile-time precondition battery passed for this plan** (connectivity, backup validity ≥ `V2`, target identity match, version match, no concurrent job, credential resolution), **re-verified fresh at claim, not read stale from compile time** — §3.3 below fixes a numeric bound on how fresh check 1's connectivity evidence must be | `restore_plan` row's own recorded precondition results (`C7` §5.3, unchanged) | Refuse job claim if any compile-time precondition is stale/no-longer-true at claim time |

Check 2 is genuinely new; checks 1 and 3 are **references to existing `C7`
records**, not reimplementations — this document does not duplicate
`restore_approval` or the precondition battery, it names them as the other
two legs of predicate 2 so a reviewer sees the complete runtime picture in
one place. All three checks are evaluated **every claim**, unaffected by
`C4`'s predicate 1 having already resolved the row to `SIGNED_OFF` at
spec-load time — repeated here because it is the one place a reviewer
might otherwise assume "signed off once" means "admitted forever," which
seq 7 explicitly rules out.

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

### 3.3 Numeric freshness bound for the connectivity check consulted by predicate 2 check 3

`C7` §5.3 check 1 requires connectivity confirmed "within a bounded
freshness window" but — checked against `C7`, `C2`, and
`UI2_0_ARCHITECTURE_DESIGN.md` directly — **no document anywhere in this
repository states what that bound actually is**, for backup or for
restore; it is abstract everywhere it appears. This is tolerable for a
class-1 backup write (§7.3 point 6's own 24-hour ceiling is a separate,
coarser control on the *same* operation); it is not tolerable, unstated,
for a class whose failure mode (`C7` §5.7) can leave a device mid-outage.
This document proposes a **concrete number, scoped only to this class's
own consultation of that check**, not a retroactive edit to `C7`'s own
abstract text for backup:

**Proposed: 15 minutes.** A restore-write's connectivity evidence
(predicate 2 check 3's re-read of `C7` §5.3 check 1) is fresh only if the
underlying class-0 connect-check job succeeded within the last 15 minutes
of the claim attempt; older evidence is treated as `FAILED`, not `PASSED`,
forcing a fresh connect-check before a restore-write claim can proceed.
Rationale: short enough that a device which became unreachable minutes
before claim (exactly the scenario `C7` §5.7 case 2 worries about) is
caught before a write is attempted, long enough that a legitimate
operator-initiated restore is not forced to re-run a connect-check on
every retry of the approval/compile step. This is a **proposal**, not a
resolved contract clause — the exact number is the successor movement's
(or the council's) to confirm or override; it is stated as a concrete
number here specifically because "bounded" with no number is exactly the
kind of unstated numeric bound the council finding named.

### 3.4 The `C2` admission/retry row this class needs (proposed; `C2` itself unedited)

`docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (FROZEN) §6's six-check
pre-execution battery and §5.3's per-action-class retry table are not
edited by this document. The following is the proposed exact text a
successor `C2`-amendment movement would add — named here in full because
"a `C2` admission/retry row" was raised as its own council finding,
distinct from this document's own three-check battery (§3.1), which
describes *what* is checked; this subsection fixes *where* in `C2`'s own
tables that check lives and what `C2`'s retry rule for the new class is.

**Proposed new `C2` §6 check (row 7, after existing check 6):**

| # | Check | Outcome field | What it re-reads |
|---|---|---|---|
| 7 | **Controlled restore-write admission** (`controlled-restore-write` jobs only) — §3.1's three-check battery (`restore_approval` validity, this document's ledger has no unreconciled prior outcome for the target, `C7` §5.3's precondition battery re-verified fresh per §3.3's 15-minute connectivity bound) | `PASSED` / `BLOCKED(reason)` / `NOT_APPLICABLE` (every other class) | `restore_approval`, this document's `RestoreWriteLedger`, the `restore_plan` row's recorded preconditions |

Placed last (after credential resolution, check 6) because it is the one
check specific to a single class, mirroring where check 4 (the `RB.x`
ledger, class-1-only) already sits relative to the five checks that apply
more broadly — consistent ordering, not a new pattern.

**Proposed new `C2` §5.3 retry-rule row:**

| Action class | Retry rule |
|---|---|
| `CLASS_1B_CONTROLLED_RESTORE_WRITE` (`controlled-restore-write`) | **Never auto-retries, at any stage, for any reason** — identical rule to `CLASS_1_RECOVERY_WRITE`'s row, for a stronger reason: `C7` §5.7 already mandates this at the job-state level ("closes only via `RECONCILED`... no exception for restore"; a new attempt is always a **new** `restore_plan`/job, never a retried one). This row makes `C2`'s own per-class retry table state the same rule explicitly, rather than leaving the new class implicitly covered only by `C7`'s restatement of `C2` §3.5's generic mechanism. |

Both proposed rows are additive to `C2`'s existing tables; no existing row,
class-0/1/2/3/4 check, or retry rule is modified.

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
(`restore_push`, item 2 of the D1 follow-up list). §3.4 above proposes one
new row in `C2`'s own pre-execution check table and one new row in its
retry-rule table — a textual amendment to `C2`'s existing documented
battery, not a new job-execution mechanism: the new check is purely an
**admission-time consultation** of records that already exist (this
ledger, `restore_approval`, the `restore_plan` precondition results), and
the new retry row states a rule `C7` §5.7 already mandates at the job-state
level, it does not introduce a new state or transition to `C2`'s state
machine (§3.1 of that document, unchanged). The new action class's
`permitted` predicate, evaluated at `C2` claim time alongside the class's
own gate-resolution outcome (`C4` §3's algorithm, unchanged, per §3.0
above's two-predicate split), consults this ledger the same way
`CLASS_1_RECOVERY_WRITE`'s admission consults
`RECOVERY_OPERATIONAL_WRITE_LEDGER.md` — a sibling check, not a parallel
execution path.

## 8. Test obligations a successor implementation movement would need (descriptive, not delivered here)

No test file is added or edited by this document. Listed so a successor
movement's `TARGETED_TEST` plan is not written from nothing; updated this
pass for the append-only reconciliation model (§4.0) and the new
`C2`/inventory items (§3.2–§3.4):

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
- (i) **(new)** a restore-write job claim whose connectivity evidence is
  older than §3.3's proposed 15-minute bound is refused at the new `C2` §6
  check 7 (§3.4), with a fresh connect-check required before retry.

## 9. Open items / unresolved semantics for review

Several items the initial DRAFT left fully open are now resolved (§4.0,
§3.2, §3.3) per this pass's governing PO decisions and council findings;
what remains genuinely open is narrower:

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
3. **§3.3's proposed 15-minute connectivity freshness bound is a proposal,
   not a resolved number** — stated concretely so the council/PO have an
   actual value to confirm or override, per the finding that named the
   absence of any number as the gap, not a claim that 15 minutes is
   uniquely correct.
4. **Restore-write's own gate-registry rows** (the literal `restore_push`
   command/call template's ten-field gate entry — vendor, timeout, retry,
   frequency, session reuse, unsupported behavior, secret-output risk, safe
   telemetry) are not specified by this document; they are per-vendor,
   per-capability detail that `C4` §2.4's worked-example pattern already
   shows how to produce once a concrete restore capability is authored —
   out of scope here, named so it is not mistaken for already covered.
5. **§3.2's inventory is scoped to this repository** — it cannot rule out
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
