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

## 3. The admission model — three legs, none of them new machinery

This class's `permitted` gate (the runtime check equivalent to
`RECOVERY_OPERATIONAL_WRITE_LEDGER.md`'s cadence check for class 1) is the
conjunction of three checks, run inside `C2`'s own claim-time admission
battery (`C7` §6.2's "restore-specific instance" of `C2` §6's six checks),
**never before it, never replacing it**:

| # | Check | Source of truth | Failure behavior |
|---|---|---|---|
| 1 | **Valid, unexpired, unrevoked `restore_approval` row exists for this exact `plan_id`**, `requested_by ≠ approved_by` | `C7` §6.2's `restore_approval` table (unchanged, not redefined here) | Refuse job claim; no device contact |
| 2 | **No unreconciled prior restore-write outcome against this `device_id`** — this ledger's own new check (§1 point 2 above) | This document's `RestoreWriteLedger` (§4 below) | Refuse job claim; no device contact; surfaced reason names the unreconciled prior `restore_run_id` |
| 3 | **`C7` §5.3's six-check compile-time precondition battery passed for this plan** (connectivity, backup validity ≥ `V2`, target identity match, version match, no concurrent job, credential resolution) | `restore_plan` row's own recorded precondition results (`C7` §5.3, unchanged) | Refuse job claim if any compile-time precondition is stale/no-longer-true at claim time — `C7` §5.3 itself already requires re-checking connectivity/credential/concurrency at claim; this document adds no new precondition, it only names that the class's admission gate consults the same battery, exactly like `C4` §3.5's "execution-eligible view" consults gate resolution rather than re-deriving it |

Check 2 is genuinely new; checks 1 and 3 are **references to existing `C7`
records**, not reimplementations — this document does not duplicate
`restore_approval` or the precondition battery, it names them as the other
two legs of one gate so a reviewer sees the complete admission picture in
one place, per this document's own stated purpose (the ledger/preconditions/
approval boundary, together).

## 4. `RestoreWriteLedger` — module API sketch (non-binding; a successor movement writes the real module)

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
    reconciled_at: datetime | None # None until a job_reconciliation record
                                   # closes an "outcome_unknown" entry (C7 §5.7)
    reconciliation_ref: str | None # job_reconciliation.reconciliation_id, once set


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
        """True if the newest entry for device_id has outcome in
        {"outcome_unknown"} and reconciled_at is None. Raises
        RestoreWriteLedgerUnreadableError if the store cannot be read —
        never conflates 'unreadable' with 'no unreconciled entry'."""

    def record_attempt(self, *, entry: RestoreWriteLedgerEntry) -> None:
        """Append one entry, called once per restore-write step the moment
        it is sent to the device — mirrors RECOVERY_OPERATIONAL_WRITE_
        LEDGER.md §6's send-time recording rule, not confirm-time."""

    def record_reconciliation(self, *, restore_run_id: str,
                               reconciliation_ref: str,
                               reconciled_at: datetime) -> None:
        """Sets reconciled_at/reconciliation_ref on the matching entry.
        Insert-only at the row level per §6 below: this is a targeted
        UPDATE of exactly the reconciliation columns on one existing row,
        the one deliberate exception to RB.3b's insert-only rule, because
        reconciliation is a real, audited state transition on an existing
        attempt record, not a new attempt — a successor movement's schema
        review may instead model this as a second linked row if an
        append-only shape is preferred; this document does not mandate one
        representation over the other, it only requires reconciliation
        state to be queryable by has_unreconciled_prior above."""
```

Backend selection follows `utils/evidence_backend.py`'s existing four-backend
pattern verbatim (`SECURITYEXPERT_EVIDENCE_BACKEND` filesystem/postgres,
`_ensure_schema`, `_write_json_atomic`, `_parse_dt`, `EvidenceBackendError`
reuse) — this document adds a fifth concern to that module the same way
`RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §3 added its fourth, and does not
re-derive the abstraction.

## 5. Fail-closed contract

| Ledger state for `device_id` | Meaning | Decision |
|---|---|---|
| **Absent** (no entry ever recorded for this device) | no prior restore-write | **admit** (subject to checks 1 and 3) |
| **Readable, newest entry `reconciled_at` set (or outcome is a terminal non-`outcome_unknown` state)** | prior attempt is closed | **admit** |
| **Readable, newest entry `outcome = "outcome_unknown"` and `reconciled_at IS NULL`** | prior attempt unresolved | **BLOCK** — refuse job claim; this is the substantive check, not a degrade case |
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

This document adds **zero** new steps to `C2`'s executor, **zero** new
fields to `restore_plan`/`restore_run`/`restore_approval`
(`C7` §5.2/§5.6/§6.2, all unchanged), and **zero** new step kinds beyond the
one already proposed in the amendment bundle (`restore_push`, item 2 of the
D1 follow-up list). It is purely an **admission-time consultation**: the
new action class's `permitted` predicate, evaluated at `C2` claim time
alongside the class's own gate-resolution outcome (`C4` §3's algorithm,
unchanged), consults this ledger the same way `CLASS_1_RECOVERY_WRITE`'s
admission consults `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` — a sibling
check, not a parallel execution path.

## 8. Test obligations a successor implementation movement would need (descriptive, not delivered here)

No test file is added or edited by this document. Listed so a successor
movement's `TARGETED_TEST` plan is not written from nothing:

- (a) a restore-write job claim against a device with no unreconciled prior
  entry, valid approval, and a passing precondition battery is admitted;
- (b) a restore-write job claim against a device with an `outcome_unknown`,
  unreconciled prior entry is refused, zero device contact, before `C2`'s
  own claim-time checks even run;
- (c) an unreadable ledger blocks admission (filesystem: corrupt JSON;
  Postgres: unreachable DSN), mirroring RB.3b's obligation (b);
- (d) `record_reconciliation` against the matching prior entry clears the
  block for a **new** `restore_plan`/job compiled per `C7` §5.7's
  `superseding_prior_run_id` rule — never for the same job re-claimed;
- (e) filesystem and Postgres backends return the same admit/block decision
  for the same synthetic history (mirroring RB.3b obligation (d));
- (f) the ledger read occurs inside the same admission section `C2` claim
  processes, never before a job is legitimately claimable, and never
  bypassable by a direct call outside that path.

## 9. Open items / unresolved semantics for review

1. **Reconciliation representation** (§4's `record_reconciliation` note):
   a targeted update of two columns on an existing row vs. a second linked
   row is left open; both satisfy this document's stated invariants
   (§4/§6), and the choice belongs to whichever movement writes the real
   schema, informed by whatever the successor movement's Flyway-migration
   review prefers for auditability.
2. **Whether this ledger's `device_id` scope should be `device_id` or
   `endpoint_id`** — `C7`'s own `restore_plan` carries both
   `target_device_id` and `target_endpoint_id` (§5.2); this document
   defaults to `device_id` (the coarser, more conservative scope: a
   reconciliation-pending state on any endpoint of a device blocks any
   further restore-write to that device) but does not argue this is
   strictly required over endpoint-level scoping — flagged for the
   successor movement / council review, since it directly affects blast
   radius on a multi-endpoint device.
3. **This document's own freeze path is gated on the taxonomy/`C4`
   amendments it presupposes landing first** — it specifies the admission
   contract *for* the new class, but the class does not exist until
   `docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` step 1 is
   itself reviewed and applied; this document and that bundle are designed
   to be reviewed together, in one council round if GOV.PO.1 §7's trigger
   (b) is invoked, not sequentially.
4. **The `docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate"
   table** currently documents only classes 0/1/2/3/4 and states "No new
   class 2, 3 or 4 command at the current product maturity" with class 1's
   own gate path named explicitly (`RECOVERY_OPERATIONAL_WRITE_LEDGER.md` +
   an approved gate entry, `RB.3b`'s `add backup local` cited as the only
   precedent). It has **no stated path for a new class that is neither 1
   nor 2/3/4** — this document and the amendment bundle do not edit that
   file (out of this movement's scope), but a successor movement will need
   to either add an explicit new-class command-gate path there or confirm
   the existing class-1 path is reused verbatim for the new class's own
   gate-registry rows (`C4` §3.2's `gate_registry.action_class` column,
   `sign_off_state` machinery) — named here so it is not discovered late.
5. **Restore-write's own gate-registry rows** (the literal `restore_push`
   command/call template's ten-field gate entry — vendor, timeout, retry,
   frequency, session reuse, unsupported behavior, secret-output risk, safe
   telemetry) are not specified by this document; they are per-vendor,
   per-capability detail that `C4` §2.4's worked-example pattern already
   shows how to produce once a concrete restore capability is authored —
   out of scope here, named so it is not mistaken for already covered.

## 10. Cross-references

- `docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md` — the
  decision document this contract implements item 3 of.
- `docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` — the
  literal proposed edits (D1 follow-up items 1, 2, 4, 5) this contract's
  new class/step kind presuppose.
- `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` (FROZEN)
  §5.2–§5.7, §6.2 — unchanged, referenced not restated.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
  (FROZEN) §2.3, §3.2, §3.3, §3.5 — unchanged, referenced not restated.
- `docs/design/RECOVERY_OPERATIONAL_WRITE_LEDGER.md` — the fail-closed and
  evidence-plane-placement precedent; §1 above states exactly where this
  document diverges from it and why.
- `docs/design/GOV_PO_ROLE_MIGRATION.md` §7 — council-trigger process this
  document's own eventual freeze must pass through.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate" — §9 item
  4 above names the gap this document does not close.
