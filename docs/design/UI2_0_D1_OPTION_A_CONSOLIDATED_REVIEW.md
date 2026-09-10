# UI 2.0 — D1 Option A: consolidated review (revision pass, relay seq 5–7 + council findings)

**Status: DRAFT — CONSOLIDATED REVIEW BUNDLE FOR PO/COUNCIL, NOT FROZEN, NOT
APPLIED.** This document is the **primary entry point** for reviewing this
revision pass. It edits no product source, no test, no `C1`–`C7` FROZEN
contract, no `utils/action_taxonomy.py`, and no project-state file. It ties
together two sibling DRAFT documents — `docs/design/RESTORE_CONTROLLED_
WRITE_LEDGER.md` (the admission contract) and `docs/design/UI2_0_D1_
OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` (the proposed edit text for D1 §5
follow-up items 1, 2, 2b, 4, 5) — both revised in this same pass, and
states exactly what changed in each and why, so a reviewer does not have
to diff two 300+ line documents by hand to find the substance.

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
| 4 | A `C2` admission/retry row for the new class | **Resolved: exact proposed text.** A new `C2` §6 check (row 7, after the existing six) consulting the ledger's three-check battery; a new `C2` §5.3 retry-rule row stating the class never auto-retries (mirroring `CLASS_1_RECOVERY_WRITE`'s row, justified independently by `C7` §5.7's own job-state rule). | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.4; cross-referenced from `UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` new Step 2b |
| 5 | Ledger identity scope: `device_id` vs. `endpoint_id` | **Resolved: `device_id`** (the coarser, more conservative scope) — a reconciliation-pending state on any one endpoint of a multi-endpoint device blocks restore-writes to every endpoint of that device, since the device's actual configuration state is not fully confirmed until reconciled as a whole. Not claimed to be the *only* defensible choice, but the one consistent with this document's own fail-closed posture elsewhere. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §4.0 |
| 6 | Append-only / tamper-evident reconciliation history | **Resolved: append-only, second-linked-entry model**, replacing the earlier draft's targeted-`UPDATE` option. A reconciliation is a new, separately timestamped `entry_kind="reconciliation"` entry referencing the attempt it closes; the original attempt row is never mutated. Matches `RECOVERY_OPERATIONAL_WRITE_LEDGER.md` §8's own append-only invariant, extended from "no deletion" to "no in-place mutation" either. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §4.0 (rationale), §4 (revised module API), §5 (revised fail-closed table), §8 (new test obligation (g)) |
| 7 | Numeric freshness bound (the connectivity check's "bounded freshness window" is stated nowhere as an actual number, for backup or restore) | **Resolved: concrete proposal, 15 minutes**, scoped only to this class's own consultation of `C7` §5.3 check 1 — not a retroactive edit to `C7`'s own abstract text for backup. Stated as a proposal precisely because the finding was "no number exists anywhere," not "the existing number is wrong." | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.3 |
| 8 | Command-gate registration path for a class that is neither 1 nor 2/3/4 | **Resolved: concrete proposed sentence** for `docs/AI_DEVELOPMENT_PROTOCOL.md`'s "Network-device command gate" section, mirroring class 1's existing two-part shape (a named contract requirement **and** a named gate-entry requirement) with the class-1.5 equivalents substituted. | `RESTORE_CONTROLLED_WRITE_LEDGER.md` §3.5 |

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

## 3. Fail-closed semantics and no-device-contact, reaffirmed

Nothing in this revision weakens any fail-closed rule the initial DRAFT
established, and the revision itself makes one fail-closed property
*stronger*, not weaker: the append-only reconciliation model (finding 6)
removes the one place the original draft allowed an in-place mutation of
existing ledger state, closing a tamper-evidence gap the DRAFT had left as
an option rather than a rule. No section of either sibling document, or
of this one, describes, sketches, or requires any device contact, any
credential use, any network operation, or any change to `C7`'s restore
engine's own already-frozen preconditions (§5.2–§5.7) or approval model
(§6.2) — both remain referenced, never redefined, exactly as the original
DRAFT stated.

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
- The proposed 15-minute connectivity freshness bound (finding 7) is a
  concrete number for the council/PO to confirm or override, not a
  claimed-uniquely-correct value.
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
  contract, revised this pass.
- `docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` — the
  amendment proposals, revised this pass.
- `docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md` —
  the original D1 decision document (unchanged, FROZEN-adjacent DRAFT).
- `relay/NXS-LOCAL-0060-ui2-d1-device-write-class-decision.json` seq 5, 7
  — the two governing PO decisions.
- `docs/design/GOV_PO_ROLE_MIGRATION.md` §7 — the council-trigger process
  both sibling documents must still pass through.
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md`,
  `UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md`,
  `UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` (all FROZEN) —
  unchanged, referenced not restated.
