# UI 2.0 — baseline contract (architecture direction and Phase 0 decisions)

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-09.** Phase 0 `DECIDE` outcome
for `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` (BASELINE, revision 2).
Recorded in the Product Owner's engineering session under the standing
in-session PO precedent, after the council round and three external
second-opinion rounds (`UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md`;
Astra's final review is disposed in the workflow's §10). Astra's closing
assessment: no remaining fundamental architectural objection; bind and
proceed; leave unverified corporate items conditional.

This contract binds the **direction** and the **decisions** below. It does
not freeze the B0 contracts (`C1`–`C7`), which are separate `CONTRACT`
movements, and it authorises no device execution (acceptance sentence A-1).

---

## 1. Direction (binding)

1. **Two lines, not two products.** Line-1 (the existing Python product) is
   the *reference and maintenance line*. The Java line (UI 2.0) is the *only
   product development line*.
2. **One runtime.** UI 2.0 executes every job in Java from the first job.
   No Line-1 executable, sidecar, shim, subprocess or job-queue consumer is
   part of the UI 2.0 runtime (workflow P-1).
3. **Reference, not reuse.** Line-1 collectors, runners and parsers are read
   to produce capability specifications and sanitized fixtures (workflow
   §3.1). Code, argv/payload structures, file layouts and page shapes are
   not ported. The validated meaning, order, timeout and trust behaviour of
   each device command is preserved (workflow P-2/P-3).
4. **Semantics stay authoritative; ownership moves.** Frozen vendor and
   product contracts (gate records, `D1`–`D7`, action taxonomy, OP.0/OP.1,
   RB contracts) bind UI 2.0. Once a capability is `CAP-VALIDATED`, its UI
   2.0 capability spec is the product-wide authority for that capability;
   Line-1 files remain provenance (workflow P-4).
5. **Line-1 stops growing.** No new Line-1 product features. Line-1 work is
   limited to contracts, vendor-semantic findings, real-environment
   captures, gate records and maintenance (workflow P-5).
6. **Independent schema.** UI 2.0 owns its schema; Flyway is its only
   migration authority; the Line-1 migration path is not shared.
7. **One executor.** Backup profiles run on the same crash-safe executor as
   read collections, under stricter admission; there is no second engine.
8. **Compose-only automation first.** Profiles are composed from closed step
   kinds; free-command authoring is a separate contract gated on an
   `AGENTS.md` amendment.
9. **Authorization, step log and audit precede the first device execution.**

Capability maturity states: `CAP-SPEC` → `CAP-OFFLINE` → `CAP-VALIDATED` →
`CAP-RELEASED`. Release slices: `REL-DISCOVERY`, `REL-INVENTORY`,
`REL-BACKUP`, `REL-CHECKS`, `REL-FAILOVER-READINESS`.

---

## 2. Phase 0 decisions (each recorded separately; none implies another)

| id | Decision | Ruling | Consequence |
|---|---|---|---|
| **RUNTIME-DIRECTION** (D-1) | Line-1 collectors are reference only; no Python in the UI 2.0 runtime (P-1…P-5) | **ACCEPTED** | §1 above |
| **JOB-UNCERTAIN-OUTCOME** (D-2a) | A write was sent and its result could not be recorded | **ACCEPTED**: the job lands in `OUTCOME_UNKNOWN`; **no automatic second write**; reopening a conflicting operation requires recorded reconciliation evidence or a separate authorised intervention decision | `C2` job-execution contract |
| **UI-OPERATIONAL-RUN-NOW** (D-2b) | Browser-initiated one-off run of an operational-write profile | **ACCEPTED**: the UI creates an *authorised job request*; the Java worker executes it. Target, profile version, reason and audit record are fixed at request time; pre-execution checks (gate chain `E1`–`E7`, `D7`, connectivity, profile/target approval state) run before any device contact. This is a **taxonomy amendment**: class-1 actions remain non-submittable from a console; a Run Now is a job request, not a console submission | `C5` amendments bundle; `utils/action_taxonomy.py` amendment text |
| **APPROVAL-MODEL** (D-2c) | What requires four-eyes | **ACCEPTED**: four-eyes on **profile creation/change** (each version) and on **schedule authorisation**. Routine backup runs inside an approved scope (approved profile version × approved target × approved schedule, including Run Now of such a profile) do **not** need a second human each time; they need the `D7` role and a mandatory reason. **Restore** and, later, **controlled failover** require **per-operation independent approval**. Night schedules and the UI use one model; no second path. This differs from design §6 ("four-eyes approval" per run) and is an **explicit amendment** to it | `C5`, `C7`, design §6.5–6.7 |
| **RAW-RETENTION** (D-2d) | Retaining raw device output beyond the sanitized evidence fragment | **DEFERRED — default NO** until a named need appears; metadata logs, audit and encrypted backup artefacts are distinct data classes and ship regardless | `C1`, workflow §3.5 |
| **FREEZE-SLICING** (D-3) | One freeze or two | **ACCEPTED: two** — platform contract (`C1`–`C5`) and backup/artefact/restore engine contract (`C7`); `C6` extraction template frozen with `C4` | B0 |
| **HISTORY-IMPORT** (D-4) | Import Line-1 evidence history | **DEFERRED**: start the UI 2.0 history at go-live; revisit after `REL-INVENTORY` | Phase S |
| **IN-FLIGHT-LINE-1** (D-5) | Movements 0048 / 0049 / 0050 | **ACCEPTED as Astra proposed**: no automatic resume. Outputs read first; usable contracts, fixtures and findings harvested. 0048 (OP.1.S1 compiler, pure function, 33 tests) merged as the **reference implementation** of the frozen OP.1 contract (PR #162). 0050 (RB.5) **stopped**: readiness projection shape (`utils/recovery_ui.py::build_recovery_ui_payload`, `readiness_by_entity`) harvested as `REL-BACKUP` extraction input; the HTML module is not completed; branch pushed as a reference checkpoint. 0049 (`cphaprob tablestat`) **held** until the PO runs it on hardware | §4 |
| **DIRECTORY-POSTURE** (D-6) | Persisted encrypted group references + read-only directory service account | **CONDITIONAL**: acceptable subject to corporate-policy verification that this conversation does not provide. `C3` must state the required access scope, secret storage and rotation, and outage behaviour. The related function (group-reference persistence, service-account bind) is **not enabled** until the verification is recorded | `C3`; blocks that part of B1 step 3 |
| **RESTORE-IN-RELEASE-1** (D-7) | Restore in Release 1 | **ACCEPTED**: Release 1 product acceptance includes a restore flow on the platforms the product declares supported; the supported device/version set may be limited. Restore does not gate the first Java pilot. Any release without restore is named **"platform pilot / backup collection release"**, never Release 1. High risk strengthens the `C7` contract and acceptance tests; it is not a reason to drop the promise | `C7`; `REL-BACKUP` |
| **DEVICE-WRITE-CLASS** (D-8) | How restore's device write is admitted, per `C7` §9.1–§9.3's reported (not fixed) gap | **ACCEPTED: Option A** (`docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md`) — a new, narrowly-scoped `utils/action_taxonomy.py` class (`CLASS_1B_CONTROLLED_RESTORE_WRITE`, `level=1.5`, non-renumbering — relay `NXS-LOCAL-0060` seq 5) between `CLASS_1_RECOVERY_WRITE` and `CLASS_2_OPERATIONAL_STATE_CHANGE`, plus a new `C4` §2.3 device-directed step kind (`restore_push`), both scoped to a provenance-bound replay of a previously-verified backup artefact back to its own **physical device/endpoint** only (`device_id`/`endpoint_id` — VSX virtual-system-context restore and ClusterXL member restore are both explicitly unsupported and out of current scope; either future capability requires its own vendor/platform-and-target-scope contract, and ClusterXL additionally requires a cross-member safety contract) — never operator-authored content, never a different target, never console-submittable. The class is narrowly `SIGNED_OFF`-eligible at `C4`'s static/spec level for restore-scoped rows only, independent of a wholly separate runtime admission predicate (relay seq 7), gated by a dedicated ledger/approval/precondition contract (`docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`) that uses the separately amended `C7` §5.3 check 1 for early connectivity and a new, independently recorded `C7_RESTORE_NO_UNRECONCILED_PRIOR` check 7 for the early no-unreconciled-prior pass (relay seq 17; appended after check 6, checks 2–6 unchanged), plus authoritative claim-time checks at `C2` §6 check 2 and the existing check-4 slot (relay seq 13). `RESTORE_TARGET_TOPOLOGY_ELIGIBILITY` must resolve to `STANDALONE_PHYSICAL_DEVICE` both before approval and at claim; known ClusterXL members and missing/stale/conflicting topology evidence are refused fail-closed through the existing target-eligibility/allowlist boundary. `C7` §5.3's own check-4 note itself requires a companion amendment (class-scoped: class 1 keeps the `RB.x` cadence interpretation, class 1.5 reads the restore reconciliation ledger, every other class stays `NOT_APPLICABLE`). Connectivity-freshness is configurable policy, not a fixed number (no value frozen; 15 minutes explicitly rejected), with any cached-telemetry semantic-sufficiency contract keyed per vendor/platform and itself requiring council review before being referenced. `CLASS_3_CONFIGURATION_WRITE`/`CLASS_4_POLICY_DEPLOYMENT` remain prohibited, unaffected. Restore stays disabled in Java until the amendments named in `D1` §5's follow-up list (this row is item 5 of that list) are all applied and, for the admission contract specifically, taken through `GOV_PO_ROLE_MIGRATION.md` §7 council review before its own freeze | `utils/action_taxonomy.py`; `C4` §2.3, §3.3 step 7; `C7` §5.3 check 1/new check 7/check-4-note companion amendment, §9.1–§9.3; `C2` §5.3, §6 check 2/check 4/check 5; `docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`; `docs/design/UI2_0_D1_OPTION_A_CONSOLIDATED_REVIEW.md`; `REL-BACKUP` |
| **FIRST-CAPABILITY** | CP inventory narrow subset (`show version`, HA state) | **ACCEPTED**. Channel-drain behaviour stays `UNKNOWN` in the spec. **If it affects result completeness it blocks `CAP-VALIDATED`**: output that is not proven complete is never presented as a successful inventory. If the unknown is shown to be without effect for the subset, that boundary is recorded in the spec and validation may proceed | B1 steps 5–6 |
| **CON.0-AMENDMENT** (C-1, earlier) | Separate shell | **ACCEPTED** (2026-09-09, morning) | `C5` |
| **STACK** (U-1, X-2, earlier) | Full Java stack; PostgreSQL; Oracle deferred | **ACCEPTED** | `C1` |
| **REPOSITORY** | Same repository, `ui2/` sub-tree, second toolchain in CI | **ACCEPTED** | B1 step 1 |
| **M14/M14L** | Local LDAP console | **PARKED**: design kept, not implemented before UI 2.0 §7 | backlog |
| **SR-D4-DISPOSITION** | Whether `UA-8`'s existing disposition ("the `DEPLOY.1A`-class decision those rows reserved; design §7") covers the council's `SR-D4` wording ("server-mode enrollment vs CON.0's OIDC-only posture") | **CONFIRMED 2026-09-09**: yes, `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` turning design §7 into a frozen specification (LDAP/AD-group RBAC in place of OIDC) **is** that reserved decision. No separate `UA-9` needed. | C3, C5 amendments bundle |
| **SR-D10-DISPOSITION** | `principal_fingerprint`: stay pseudonymous, or make it attributable to a role that can resolve it | **DECIDED 2026-09-09: attributable-on-demand, not plain pseudonymous.** Default read surfaces (logs, audit views, most of the UI) show only the fingerprint, exactly as `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §3.2 already specifies. `security_admin` additionally gets a resolve action that reveals the underlying identity via `role_bindings`' existing encrypted-at-rest linkage (already sketched in §3.2) -- itself an audited, gated action, not a silent join. Rationale: an unkeyed 12-hex prefix reversible by DN enumeration gives away real identity to anyone who tries, while a resolve action that is gated and audited gives accountability without casual exposure. This does not reopen `C3`'s algorithm (the fingerprint value itself is unchanged); it adds one gated resolve capability, specified at C5/C3-successor detail level, not in this baseline row. | C3-successor detail (C5 amendments bundle or a small C3 addendum); B1-3 |

| **VISUAL-DESIGN-LANGUAGE** | Screen/component design system for UI 2.0 | **ACCEPTED 2026-09-09: Material Design 3.** Tonal surface containers, 16px corners, navigation rail with a pill indicator, filled/tonal/outlined/text button hierarchy, 28px dialogs, Roboto + Roboto Mono. Source: the "neXus 2026 Design Refresh" design canvas (19 artboards, 7 competing directions on one Overview screen plus a full M3 product screen set: Overview, Devices->Inventory, Configuration->Alignment, Compliance, Operations, Administration, Navigation & components); mockup content recorded in workflow-support notes for B0-8. This binds the *visual system*, not new product decisions: the RBAC visible-but-refused pattern, the class 0/1 action split shown in the mocks, and the screen-state vocabulary (Aligned / Member-specific / Local override / Difference observed / Effective drift / Not applicable / Not configured / Stale) are UI expressions of already-frozen rules (design §5, UI-OPERATIONAL-RUN-NOW, APPROVAL-MODEL) and are adopted into the baseline directory (B0-8), not re-decided here | B0-8 baseline directory; B1 step 9 (device workspace / first read screen); every later REL-* integrate step |
| **LOGO** | Product mark for UI 2.0 | **ACCEPTED 2026-09-09, CONDITIONAL: option D / Wordmark** from `docs/design/ui2_mockups/logo-identity-explorations.png` -- the `neXus` wordmark with the accented capital X, no separate symbol; the tagline shown on the study is not adopted. Condition (PO's own wording: "if it is not used elsewhere"): before the mark ships on any external surface, the PO verifies it does not collide with an existing product/trademark use; until that check is recorded here, the wordmark is used internally only. Vector source does not exist yet -- a B1-9-adjacent asset task produces it from the study; the PNG is a concept study, not final artwork. |

---

## 3. Acceptance sentences (binding on every later movement)

- **A-1.** Acceptance of this baseline does not permit execution of any
  capability on a device. Every capability passes its own gate record and
  its own validation (`CAP-VALIDATED`) before it may contact a device from
  the product.
- **A-2.** In `OUTCOME_UNKNOWN` no automatic second write is performed.
  Reopening a conflicting operation depends on recorded reconciliation
  evidence or on a separate, authorised intervention decision.
- **A-3.** Release 1 completion is measured by acceptance of product flows
  on an explicit device/version matrix, not by movement count.

---

## 4. Release 1 definition

Java platform + limited, validated device scope + backup, restore and
checks + failover *readiness*. Controlled failover execution (OP.2),
session recording, free-command authoring and full BackBox equivalence are
later releases with their own gates. The device/version matrix is a
deliverable of `C7`/`REL-BACKUP` and is the object of A-3.

---

## 5. Amendments this contract requires (to be written in `C5`)

1. `CON.0` — separate shell (approved).
2. `utils/action_taxonomy.py` — `UI-OPERATIONAL-RUN-NOW` (job request ≠
   console submission).
3. `UI2_0_ARCHITECTURE_DESIGN.md` §6 — `APPROVAL-MODEL` replaces per-run
   four-eyes with version/schedule four-eyes plus per-operation approval
   for restore and controlled failover.
4. `UI2_0_ARCHITECTURE_DESIGN.md` §3.2/§8.5 — ladder replaced by `CAP-*`
   states; `AG-U2` restated as one Java execution path.
5. `AGENTS.md` — deferred: browser profile editor (free-command) and
   mediated terminal, only when Phase S opens.

---

## 6. What happens next

`B0` opens as the primary development backlog (`project/backlog.json`,
category "UI 2.0 (Java product line)"): `C1`–`C7`, the baseline directory
and the extraction tooling. Tier `Sonnet 5, extended thinking (high)` for
every `C*` contract; `Sonnet 5, normal` for the tooling. `B1` items are
listed behind them and start when `C1`–`C4` and the authorization/audit rows
are frozen.

**2026-09-09 update — B0 complete, both freezes made.** Slice 1 (platform:
`C1`–`C6` + `UI2_0_BASELINE_DIRECTORY.md`) and slice 2 (`C7` engine) are
FROZEN — PRODUCT OWNER APPROVED. `B1` may start. Credit-conservation
direction (PO): remaining Claude budget goes to contracts, not code — every
`B1`/`B2` step gets a contract/spec movement first (tier Fable 5.1 low;
medium for security-boundary decisions), so implementation can proceed in
any tool against frozen text. Order: taxonomy DECIDE
(`ui2_taxonomy_device_write_class_and_step_kind`) → B1-1 skeleton contract →
B1-5 CP inventory extraction → B1-7/8/9 screen contracts → B1-10/11
acceptance scenarios → B1-12 deployment → B1-13 PAN export extraction →
B2 contracts as DRAFT pending B1-6 feedback.

## 7. Cross-references

- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` — the plan (BASELINE rev 2).
- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` — design under amendment (§5).
- `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` — review record.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md`, `docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md` — frozen semantics carried into `C7` and `REL-FAILOVER-READINESS`.
- `AGENTS.md` network-device command gate; `docs/AI_DEVELOPMENT_PROTOCOL.md` — unchanged and binding.

---

## Amendment A-1 (2026-09-12) — D1 Option A: record of the already-applied `DEVICE-WRITE-CLASS` (D-8) row

§2's Phase 0 decision table already carries step 5 of
`docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md` — the
`DEVICE-WRITE-CLASS` (D-8) row. The row was added without an amendment record.
This section supplies it; **no decision row is changed by it.**

**Authorizing documents.** `docs/design/UI2_0_D1_OPTION_A_AMENDMENT_PROPOSAL_BUNDLE.md`
(FROZEN — Product Owner approved amendment contract), resting on the Option A
selection recorded at relay
`relay/NXS-LOCAL-0060-ui2-d1-device-write-class-decision.json` (seq 3, fixed at
seq 5, 7, 9, 13, 17, council-satisfied at seq 21) and on
`docs/design/UI2_0_BASELINE_CONTRACT.md` §2 row `DEVICE-WRITE-CLASS` (D-8).
The admission contract the amended clauses cross-reference,
`docs/design/RESTORE_CONTROLLED_WRITE_LEDGER.md`, is itself FROZEN, so no
amended clause here rests on a DRAFT.

**Authority-hierarchy note.** Until 2026-09-12 this FROZEN contract's D-8 row
cited `docs/design/UI2_0_D1_DEVICE_WRITE_CLASS_AND_STEP_KIND_DECISION.md`
while that document's own status was "DRAFT — OPTION A SELECTED IN RELAY;
FROZEN BASELINE AMENDMENT PENDING" — a FROZEN contract resting on an unfrozen
decision, which `AGENTS.md` "Authority hierarchy" item 2 forbids. The pending
amendment has now been applied across `C2`, `C4` and `C7`, and that decision
document is frozen accordingly, so the citation is no longer a contradiction.
