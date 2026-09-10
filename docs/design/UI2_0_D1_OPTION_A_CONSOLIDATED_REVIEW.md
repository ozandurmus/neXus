# UI 2.0 — D1 Option A: consolidated review (revision pass, relay seq 5–13 + council findings + PO freshness clarification)

**Status: DRAFT — CONSOLIDATED REVIEW BUNDLE FOR PO/COUNCIL, NOT FROZEN, NOT
APPLIED.** This document is the **primary entry point** for reviewing this
revision pass. It edits no product source, no test, no `C1`–`C7` FROZEN
contract, no `utils/action_taxonomy.py`, and no project-state file. It ties
together two sibling DRAFT documents — `docs/design/RESTORE_CONTROLLED_
WRITE_LEDGER.md` (the admission contract) and `docs/design/UI2_0_D1_
OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` (the proposed edit text for D1 §5
follow-up items 1, 2, 2b, 4, 5) — both revised across three passes so far,
and states exactly what changed in each and why, so a reviewer does not
have to diff two 400+ line documents by hand to find the substance.

**Governing inputs to this revision (binding, not re-argued here):**

- Relay `NXS-LOCAL-0060` seq 5 (`RELAY_DECISION`, PO): `ActionClass.level
  = 1.5`, non-renumbering representation, with a required consumer/wire-
  field inventory before any taxonomy edit lands.
- Relay `NXS-LOCAL-0060` seq 7 (`RELAY_DECISION`, PO): `CLASS_1B_
  CONTROLLED_RESTORE_WRITE` may be `SIGNED_OFF` only narrowly for
  restore-scoped capability rows; runtime execution eligibility is
  **independently** gated at claim time by restore approval, this
  document's ledger, and `C7`'s precondition battery — `C4` static
  sign-off and `C2` runtime admission are **separate predicates**.
- Relay `NXS-LOCAL-0060` seq 9 (`RELAY_DECISION`, PO): fold the restore
  ledger's admission facts into `C7` §5.3's **existing** checks 1/2; do
  **not** retain a separate `C2` §6 check row.
- **PO chat clarification (supersedes this document's own prior text):**
  do not freeze a 15-minute connectivity freshness value. Model freshness
  as **configurable policy**, separating background polling cadence from
  claim-time restore safety; specify a mandatory active-probe path and an
  optional cached-telemetry/SNMP path gated on explicit TTL/identity/
  semantic-sufficiency conditions with fail-closed fallback; never claim
  SNMP proves write-channel readiness absent a vendor-specific contract;
  include policy fields, illustrative (not frozen) bounds, precedence,
  stale/unknown behavior, and audit fields.
- **Relay `NXS-LOCAL-0060` seq 13 (`RELAY_DECISION`, PO): narrowly
  supersedes seq 9.** Add an explicit restore-ledger reconciliation-
  pending check **at `C2` claim time**, fail-closed — this one fact
  "cannot remain compile-time-only." `C4` static sign-off remains fully
  independent of runtime `C2` admission, unaffected. The successor
  contract must additionally define: the exact key/scope (including
  VSX/ClusterXL endpoint/member implications), `restore_run`/step
  cardinality and multiple-step resolution, retry behavior, and test
  obligations.
- Council findings (relayed via the Nexus PO orchestrator session), listed
  and resolved one-by-one in the table below.

**This document produces no new admission logic of its own** — every
substantive resolution lives in the two sibling documents; this document
is a map, cross-reference, and change log, not a third source of truth.

---

## 1. Council findings — resolved position or bounded proposal, one row each

| # | Finding | Resolution | Where |
|---|---|---|---|
| 1 | Inventory/compatibility impact of every `ActionClass.level` consumer, including `console/app.py` and API wire fields | **Resolved by inventory** (not a proposal — an actual read-only search of current repository state). Exactly one non-test source consumer found (`console/app.py:294`'s `action_class_level` wire field, unaffected today since no job type maps to the new class yet); two test files require a successor-movement edit (`test_the_five_classes_exist_and_are_ordered`, and a new sibling assertion for `test_no_console_job_type_is_class_2_or_above`'s blind spot at `level=1.5`); no frontend, `sorted()`, or other comparison call site found. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.2 (full inventory table); `UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` Step 1 (same inventory, cross-referenced) |
| 2 | `C4`'s "five classes" wording becomes inaccurate with a sixth class | **Resolved: proposed text for all four literal occurrences** (the taxonomy module's own docstring heading, and three mentions in `C4` §1.3/§2.2/§9) — each proposed to drop the hard-coded count entirely rather than bump it to "six," so the text does not go stale again at the next addition. | `UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` Step 1, "`C4`'s 'five classes' wording" subsection |
| 3 | Static sign-off (`C4`) versus runtime admission (`C2`) — are these one predicate or two? | **Resolved by PO decision (seq 7): two independent predicates.** `C4`'s §3.3 step 7 restatement is rewritten to remove the earlier draft's blending (`SIGNED_OFF` conditioned on ledger/approval facts) — `SIGNED_OFF` is now purely a static, step-kind-scoped predicate; the ledger/approval/precondition battery is entirely `C2`'s separate, every-claim runtime predicate. Both are necessary; neither is sufficient alone. | `UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` Step 2, "§3.3 step 7 — proposed restatement"; `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.0 |
| 4 | A `C2` admission/retry row for the new class | **Revised three times.** First resolved as a standalone `C2` §6 check (row 7); **superseded by relay seq 9**, which folded the ledger's admission facts into `C7` §5.3's existing checks 1/2 alone; **narrowly re-superseded by relay seq 13**, which requires an explicit claim-time check after all for the reconciliation-pending fact specifically (resolved via `C2` §6's existing check-4 slot, repurposed — still zero new rows). The retry-rule row itself is unaffected across all three passes, since it answers a different question. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1 (compile/claim split), §3.4 (retry rule); cross-referenced from `UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` Step 2b |
| 5 | Ledger identity scope: `device_id` vs. `endpoint_id`, including VSX/ClusterXL implications | **Resolved: `device_id`** (the coarser, more conservative scope), **now with the VSX/ClusterXL treatment this pass makes explicit** (finding 10 below) rather than left implicit in `C4` §4's vocabulary. Not claimed to be the *only* defensible choice, but the one consistent with this document's own fail-closed posture elsewhere. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §4.0 (original resolution), §3.1.1 (this pass's full VSX/ClusterXL treatment) |
| 6 | Append-only / tamper-evident reconciliation history | **Resolved: append-only, second-linked-entry model**, replacing the earlier draft's targeted-`UPDATE` option. A reconciliation is a new, separately timestamped `entry_kind="reconciliation"` entry referencing the attempt it closes; the original attempt row is never mutated. Matches `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §8's own append-only invariant, extended from "no deletion" to "no in-place mutation" either. **Restated unambiguously in §6 this pass** (finding 14 below) — the retention section had carried stale "one narrow exception" wording past its own resolution. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §4.0 (rationale), §4 (revised module API), §5 (revised fail-closed table), §6 (this pass's unambiguous restatement), §8 (test obligation (g)) |
| 7 | Numeric freshness bound (the connectivity check's "bounded freshness window" is stated nowhere as an actual number, for backup or restore) | **Revised twice.** First resolved as a concrete proposal (15 minutes); **explicitly rejected by a subsequent PO clarification** — freezing any fixed number was ruled out. Now specified as **configurable policy**: a mandatory claim-time active-probe path (always available, authoritative) and an optional cached-telemetry/SNMP path usable only under strict, explicit TTL/identity-binding/semantic-sufficiency conditions, falling back to the active probe otherwise. Background polling cadence is explicitly separated from claim-time restore safety — the two are not the same test. SNMP-style reachability is never treated as proof of write-channel readiness absent an established, **now per-vendor/platform-keyed** contract (finding 13 below). All numeric fields (probe timeout, cache TTL) are `UNKNOWN`/illustrative-range-only, not decided. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.3 (policy model), §3.3.1 (schema/bounds), §3.3.2 (BackBox research note, non-authoritative) |
| 8 | Command-gate registration path for a class that is neither 1 nor 2/3/4 | **Resolved: concrete proposed sentence** for `docs/AI_DEVELOPMENT_PROTOCOL.md`'s "Network-device command gate" section, mirroring class 1's existing two-part shape (a named contract requirement **and** a named gate-entry requirement) with the class-1.5 equivalents substituted, **updated this pass** to name both the compile-time and claim-time halves of the reconciliation-pending contract. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.5 |
| 9 | **(new, this pass)** Compile-time vs. claim-time re-evaluation — must be a stated distinction, not left implicit | **Resolved: its own subsection.** `C7` §5.3 (compile time) is early and non-authoritative; `C2` §6 (claim time) is the sole authoritative gate, re-verified fresh regardless of how long ago compile-time checks passed. Both of this document's own facts (connectivity, reconciliation-pending) are now placed explicitly against this distinction, with the reason relay seq 13 gave for why one of them (reconciliation-pending) needed a claim-time instance it previously lacked. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1.0 |
| 10 | **(new, this pass, per PO instruction)** Ledger key/scope: exact VSX/ClusterXL endpoint/member implications | **Resolved with an explicit conservative default plus a named escape hatch.** ClusterXL: each member's independent `device_id` (`C4` §4.2/§4.3) naturally isolates members from each other by construction — no new mechanism needed; whether one member's restore can leave the *other* member's state uncertain is named as an open item, cross-referenced to `C7` §9.8's own already-open question rather than answered here. VSX: one physical `device_id` hosts multiple `virtual_system_ref` contexts; the default stays `device_id`-wide (a reconciliation-pending state on one virtual system blocks restores to every virtual system on that physical device) — a narrower, `virtual_system_ref`-scoped alternative is possible for a specific vendor/platform only through the same council-reviewed, per-vendor/platform-keyed contract mechanism finding 13 establishes for connectivity evidence. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1.1, §4 (the `virtual_system_ref` field, recorded but not authoritative for blocking) |
| 11 | **(new, this pass, per PO instruction)** `restore_run`/step cardinality and multiple-step resolution | **Resolved: one ledger `"attempt"` entry per `restore_run` (`C2` job), never per individual write step**, matching `C7` §5.6's own job-level granularity and `C7` §5.7's own job-level (not step-level) `OUTCOME_UNKNOWN` handling — this ledger does not attempt a finer-grained judgment than `C7`/`C2` already make. `record_attempt`'s docstring is corrected this pass (it previously said "once per restore-write step," which conflated a run's granularity with its steps). | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1.2, §4 (corrected `record_attempt` docstring) |
| 12 | **(new, this pass, per PO instruction)** Retry behavior for the claim-time reconciliation check | **Resolved: distinguished explicitly from the class's own never-auto-retry rule.** A job `BLOCKED` at claim by this check never reached `EXECUTING` and remains ordinarily claimable on a future cycle, exactly like any other `C2` §6 precondition failure — this is not the same event as a `CLAIMED`/`EXECUTING` job's own no-retry rule (§3.4), which governs failure *after* device contact was attempted. Whether a perpetually-`BLOCKED` job should ever auto-expire is named as a separate, generic `C2` job-lifecycle open item, not decided here. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.1.3 |
| 13 | **(new, this pass, per PO instruction)** Cached-telemetry semantic-sufficiency contracts must be keyed per vendor/platform, and must themselves pass trigger-(b) council review | **Resolved: both requirements added together.** `cached_evidence_semantic_sufficiency_contract_ref` is now a mapping keyed by `(vendor, platform_role_scope)` (mirroring `C4` §3.2's own gate-registry key shape), never a single global flag one vendor's contract could be misread as covering every vendor; and every entry in that mapping is itself named as a `GOV_PO_ROLE_MIGRATION.md` §7 trigger-(b) freeze candidate, requiring council review from a `nexus-po` `PLAN`/`DECIDE` episode before being added — no entry exists today for any vendor/platform. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.3.1 |
| 14 | **(new, this pass, per PO instruction)** Make the append-only decision unambiguous in §6 specifically | **Resolved: §6 rewritten to state the invariant without qualification** — no code path ever issues an `UPDATE` or `DELETE` against any row this ledger has ever written, for any reason, including reconciliation; the prior "one narrow, audited exception a successor movement's schema review may keep or replace" hedge is explicitly retracted as stale text that had outlived §4.0's own resolution. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §6 |

Findings 5–8 were left as open items in the original DRAFT pass; this
revision closes all four with a concrete, reviewable position rather than
leaving them for a later movement to invent from scratch. Findings 1–4 are
new consumer/wording/policy/mechanism findings this pass addresses for the
first time.

## 2. What changed in each sibling document (change log, not a diff)

### `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`

- New revision-note preamble naming the two governing PO decisions.
- §3 rewritten from "three legs, one gate" to "two independent predicates"
  (§3.0), with the runtime battery demoted to "predicate 2" (§3.1) —
  substance unchanged, framing corrected to match relay seq 7.
- New §3.2: the level-consumer/wire-field inventory (finding 1).
- New §3.3: the numeric freshness-bound proposal (finding 7).
- New §3.4: the `C2` admission-check/retry-row proposal (finding 4).
- New §3.5: the command-gate registration-path proposal (finding 8).
- §4 (module API) revised: `RestoreWriteLedgerEntry` gains `entry_kind`/
  `reconciles_restore_run_id`; `record_reconciliation` now appends rather
  than mutates (finding 6); new §4.0 states both resolutions (findings 5,
  6) and their rationale.
- §5 (fail-closed table) revised to read the append-only model correctly.
- §7 revised to acknowledge the new `C2` proposal without contradicting
  the "no new job-execution mechanism" claim (a textual amendment to an
  existing check/retry table is not a new mechanism).
- §8 (test obligations) revised: (d) restated for the append-only model;
  (g), (h), (i) added for the new findings.
- §9 (open items) pruned: items resolved this pass are removed from "open"
  and folded into the sections above; what remains open is narrower
  (schema-level table shape, the 15-minute proposal's own confirmation,
  gate-registry detail, residual external-client risk).
- §10 (cross-references) updated to point at this document and at `C2`.

### `docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md`

- New revision-note preamble naming the two governing PO decisions.
- Step 1's "representation question" section rewritten from "flagged, two
  options, recommend (a)" to "DECIDED: (a), `level=1.5`" — the decision
  text and required inventory are added in full (finding 1); the
  "recommended, not decided" framing is removed since it is no longer true.
- Step 1 gains a new subsection proposing the exact "five classes" → count
  -free wording fix in four locations (finding 2).
- Step 2's §3.3 step 7 restatement is **substantively rewritten**, not
  merely edited: the earlier text conditioned `SIGNED_OFF` on runtime
  facts (ledger/approval), which the PO's seq 7 decision rules out; the
  new text separates the two predicates cleanly and adds an explicit
  "necessary, never sufficient" paragraph (finding 3).
- New Step 2b added (was not one of the original four steps): points to
  the `C2` proposal now specified in the ledger document (finding 4),
  summarized rather than duplicated.
- Step 5's proposed baseline row rewritten to record the decided
  representation and policy shape, and to reference this consolidated
  review document and `C2` in its own target-file list.
- Closing sections ("what this bundle does not include," cross-references)
  updated for the new Step 2b and this document's existence.

### Second revision pass (this pass): relay seq 9 + PO freshness clarification

**`docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`:**

- New second revision-note naming both new governing inputs (seq 9, the
  freshness clarification).
- §3.1 **rewritten**: the standalone three-check `C2` battery is replaced
  by a fold-mapping table showing which `C7` §5.3 check (1 or 2) absorbs
  each fact, with the approval-validity fact noted as already covered by
  existing `C7`/`C2` FROZEN text (no fold needed at all).
- §3.3 **rewritten in full**: the 15-minute proposal is removed and
  explicitly rejected; replaced with the configurable-policy model
  (background-cadence-vs-claim-time-safety separation, active-probe vs.
  cached-telemetry alternatives, the four cached-telemetry conditions, a
  proposed field schema at new §3.3.1 with illustrative-only bounds, and
  a new §3.3.2 non-authoritative BackBox research note).
- §3.4 **narrowed**: the standalone `C2` §6 admission-check proposal is
  withdrawn (superseded by the §3.1 fold); only the `C2` §5.3 retry-rule
  row survives, since it answers a different question the seq 9 decision
  does not touch.
- §7 revised to describe the fold as an in-place amendment to `C7`'s
  existing battery, not a new `C2` mechanism.
- §8 (test obligations) item (i) rewritten for the fold/policy model;
  items (j), (k) added for the new policy-validation and audit-field
  obligations.
- §9 (open items) item 3 rewritten to record the freshness bound as
  **resolved-then-reopened by explicit PO instruction**, not silently
  smoothed over; new item 4 names vendor-semantic-sufficiency-contract
  existence as its own open `UNKNOWN`.
- Stray forward-reference fixed in §3.0 (pointed at a stale "§3.2 check 2"
  location from an earlier draft pass; now points at the §3.1 fold table).

**`docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md`:**

- New second revision-note naming both new governing inputs.
- The "necessary, never sufficient" paragraph in Step 2 updated to
  describe the fold (`C7`/`C2`-side, not `C2`-only) and the configurable
  policy, removing the stale "proposed as `C2` §6 check 7" reference.
- **Step 2b rewritten in full**: retitled to reflect the fold and the
  withdrawn admission check; states the check-1/check-2 mapping and the
  surviving retry-rule row; removes every reference to a standalone `C2`
  check or a 15-minute bound.
- Step 5's proposed baseline row updated: target-file list changed from
  `C2` §5.3/§6 to `C7` §5.3 checks 1/2 plus `C2` §5.3; prose updated to
  describe the fold and the rejected freshness value.
- Closing "what this bundle does not include" bullet for Step 2b updated
  to point at the correct sibling-document sections (§3.1, §3.4, not §3.4
  alone).

### Third revision pass (this pass): relay seq 13 + further council findings

**`docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`:**

- New third revision-note naming seq 13 and the full list of further
  council findings this pass addresses.
- §3.0 revised: adds an explicit note that seq 13 narrowly revises §3.1's
  own placement decision without reopening §3.0's two-predicate split
  itself; fixes the same stale forward-reference pattern once more (now
  correctly pointing at §3.1's check-4-slot check, not the withdrawn
  compile-time-only fold).
- **§3.1 rewritten in full and restructured**: new title reflecting the
  two-tier (compile + claim) model; new §3.1.0 states the compile-time-
  vs-claim-time distinction explicitly as its own subsection (finding 9);
  the fact-mapping table is rewritten to show both a compile-time and a
  claim-time disposition per fact, with the reconciliation-pending fact's
  claim-time home resolved as a repurposing of `C2` §6's existing,
  already-class-scoped check-4 slot (not a new row); new §3.1.1 defines
  ledger key/scope including VSX/ClusterXL implications (finding 10); new
  §3.1.2 defines `restore_run`/step cardinality (finding 11); new §3.1.3
  defines retry behavior for the claim-time check, distinguished from the
  class's own no-auto-retry rule (finding 12).
- §3.3.1's schema revised: `cached_evidence_semantic_sufficiency_
  contract_ref` changed from a single `str | None` to a mapping keyed by
  `(vendor, platform_role_scope)`, with new prose requiring every entry to
  pass `GOV_PO_ROLE_MIGRATION.md` §7 trigger (b) council review before
  being added (finding 13); the §3.3 semantic-sufficiency condition row
  updated to match.
- §3.4 narrowed further: explicitly distinguishes the retry rule (governs
  `EXECUTING` jobs) from §3.1.3's claim-time retry-eligibility answer
  (governs jobs never claimed at all).
- §3.5 updated: the proposed command-gate registration sentence now names
  both the compile-time and claim-time halves of the reconciliation
  contract.
- §4.0/§4 revised: the ledger-key rationale is now a pointer to §3.1.1
  rather than a duplicate; `RestoreWriteLedgerEntry` gains a
  `virtual_system_ref` field (recorded, not authoritative for blocking);
  `record_attempt`'s docstring corrected from "once per restore-write
  step" to "once per `restore_run`" (finding 11); `has_unreconciled_prior`
  docstring updated to note it is the single implementation consulted by
  both the compile-time and claim-time call sites.
- §5's table header and prose updated to note both consultation points
  share the same underlying decision logic, differing only in authority.
- **§6 rewritten**: the append-only invariant is restated without
  qualification, retracting the "one narrow, audited exception" hedge
  explicitly as stale (finding 14).
- **§7 retitled and rewritten**: states the full compile+claim mapping in
  one paragraph, per the "make the `C7` check mapping explicit" finding,
  and clarifies that repurposing an existing check-4 slot is still "zero
  new rows."
- §8 (test obligations) gains new obligations (b2) staleness-between-
  compile-and-claim, (l) `restore_run`-level cardinality, (m) `BLOCKED`-
  vs-no-retry distinction, (n) VSX/ClusterXL scoping; (i)/(j) updated for
  the check-2/check-4 claim-time homes and the keyed contract mapping.
- §9 (open items) restructured: items resolved this pass are folded into
  the sections above; new items added for ClusterXL cross-member risk
  (cross-referenced to `C7` §9.8, not resolved), `BLOCKED`-job expiry
  (generic `C2` question, not decided), and VSX scoping being a
  conservative default rather than a settled answer.
- §10 (cross-references) gains `C4` §4.2–§4.4, `C7` §9.8, `C2` §3.1, and
  relay seq 13.

**`docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md`:**

- New third revision-note naming seq 13.
- Step 2's "necessary, never sufficient" paragraph updated again to state
  both the compile-time early pass and the claim-time authoritative
  check-4-slot check for the reconciliation-pending fact, replacing the
  single-fold description.
- **Step 2b retitled and rewritten again**: states the full revision
  history (three PO inputs in sequence, none silently overwritten); adds
  an explicit "Compile-time" and "Claim-time" subsection matching the
  ledger document's own §3.1.0 table; the retry-rule paragraph now
  explicitly distinguishes itself from `BLOCKED`-at-claim eligibility.
- Step 5's proposed baseline row and its own preamble updated to name all
  of relay seq 5, 7, 9, 13 plus the freshness clarification, and to
  describe the two-tier compile/claim model instead of a single fold.

## 3. Fail-closed semantics and no-device-contact, reaffirmed

Nothing in this revision weakens any fail-closed rule the initial DRAFT
established, and the revision itself makes fail-closed properties
*stronger* in three places, not weaker: the append-only reconciliation
model (finding 6) removes the one place the original draft allowed an
in-place mutation of existing ledger state, closing a tamper-evidence gap;
the configurable freshness policy's fixed fallback rule (§3.3 — any
insufficient cached-telemetry evidence falls back to an active probe,
unconditionally, never to "proceed anyway") replaces a single abstract
number with a policy whose failure mode is always the conservative one,
regardless of what a deployment configures for its evidence source; and
**this pass's own explicit claim-time reconciliation check (finding 9,
relay seq 13) closes the one gap a compile-time-only fold would have left
open** — a `restore_plan` compiled against a clean target, then claimed
after a *new* unreconciled outcome appeared on that same target in the
interim, would have been silently admitted under the prior revision's
compile-time-only model; it is now refused, fresh, at the moment that
matters. No section of either sibling document, or of this one, describes,
sketches, or requires any device contact, any credential use, any network
operation, or any change to `C7`'s restore engine's own already-frozen
preconditions (§5.2–§5.7) or approval model (§6.2) — both remain
referenced, never redefined, exactly as the original DRAFT stated.

## 4. Validation

- `git diff --check`: clean (verified against this revision's actual
  changes — see the engineer relay note for the exact command output).
- Repository privacy gate: 0 new findings vs. the pre-existing baseline
  (the same 6 pre-existing findings in `project/*.json` and `relay/*.json`
  files unrelated to this change, unaffected by this revision).
- No test file, source file, or FROZEN contract is touched — verified by
  `git status`/`git diff --stat` showing only `docs/design/*.md` changes.
- No device contact, network access, or credential use of any kind was
  performed in producing this revision.

## 5. What remains genuinely open after this pass

- The exact Flyway/table-level schema for the append-only reconciliation
  model (one table with an `entry_kind` discriminator vs. two linked
  tables) — either satisfies this pass's stated invariants; left to
  whichever movement writes the real schema.
- **Connectivity freshness has no decided numeric value, by explicit PO
  instruction** — the 15-minute proposal is rejected, not merely
  unconfirmed; `active_probe_timeout_s` and `cached_evidence_ttl_seconds`
  are `UNKNOWN`, with only an illustrative range named for discussion.
  The successor movement sets real numbers from actual per-vendor probe-
  latency evidence this pass does not have.
- **No vendor/platform pair has an established, council-reviewed
  semantic-sufficiency contract for cached telemetry today** — the
  mapping (§3.3.1) is empty; `evidence_source` should default to
  `active_probe` for every real deployment until a specific pair's
  contract is written and passes `GOV_PO_ROLE_MIGRATION.md` §7 trigger (b)
  review.
- Which existing `C7` §5.3 check (2, or alternatively 5) absorbs the
  **compile-time, non-authoritative** "no unreconciled prior restore-write
  outcome" pass is this pass's own proposed mapping, not a settled one —
  flagged for council confirmation. (The **claim-time, authoritative**
  instance of the same fact is separately resolved this pass as `C2` §6
  check 4's repurposed slot, per relay seq 13 — not open.)
- **`device_id`-wide ledger scoping for VSX is this document's own
  conservative default**, not a settled answer for every vendor's actual
  restore-procedure blast radius — a narrower `virtual_system_ref`-scoped
  alternative requires its own council-reviewed, per-vendor/platform
  contract, none of which exists today.
- **Whether a restore against one ClusterXL member can leave the other
  member's operational state uncertain is not answered by this ledger**
  (`device_id`-keyed scope naturally isolates members from each other,
  which says nothing about cross-member risk) — cross-referenced to `C7`
  §9.8's own already-open "multi-member consistency-group restore" item,
  not resolved by this pass.
- **Whether a perpetually `BLOCKED`-at-claim job should ever auto-expire
  is a generic `C2` job-lifecycle question** this pass does not have the
  scope to answer.
- Restore-write's own per-vendor gate-registry rows (the literal
  `restore_push` command/call template's ten-field gate entry) are not
  specified anywhere in this pass — out of scope, per-capability detail
  for whenever a concrete restore capability is authored.
- §3.2/Step 1's inventory is scoped to what this repository's own source
  can confirm; it cannot rule out an external client of `console/app.py`'s
  wire field parsing `action_class_level` as a strict integer.
- Both sibling documents still require `nexus-decision-council` review
  (`GOV_PO_ROLE_MIGRATION.md` §7 trigger (b)) from a `nexus-po` `PLAN` or
  `DECIDE` episode before either may move from DRAFT to FROZEN — this
  consolidated review does not substitute for that council round, it
  prepares the two documents to be reviewable in one.

## 6. Cross-references

- `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md` — the admission
  contract, revised across three passes.
- `docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` — the
  amendment proposals, revised across three passes.
- `docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md` —
  the original D1 decision document (unchanged, FROZEN-adjacent DRAFT).
- `relay/NXS-LOCAL-0060-ui2-d1-device-write-class-decision.json` seq 5, 7,
  9, 13, plus a PO chat clarification rejecting the 15-minute freshness
  value — the governing inputs to this revision.
- `docs/design/GOV_PO_ROLE_MIGRATION.md` §7 — the council-trigger process
  both sibling documents must still pass through, and the process every
  future semantic-sufficiency or VSX-scoping contract must itself pass.
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` §3.1 (state machine),
  §5.3, §6 — unchanged; the ledger document's §3.1/§3.4 propose text for a
  successor movement to add, including the check-4-slot repurposing.
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`
  §2.3, §3.2, §3.3, §3.5, §4.2–§4.4 (VSX/ClusterXL target model) — unchanged,
  referenced not restated.
- `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` §5.2–
  §5.7, §6.2, §9.8 (multi-member consistency-group restore, cross-
  referenced not resolved) — unchanged, referenced not restated.
