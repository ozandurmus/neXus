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
| **FIRST-CAPABILITY** | CP inventory narrow subset (`show version`, HA state) | **ACCEPTED**. Channel-drain behaviour stays `UNKNOWN` in the spec. **If it affects result completeness it blocks `CAP-VALIDATED`**: output that is not proven complete is never presented as a successful inventory. If the unknown is shown to be without effect for the subset, that boundary is recorded in the spec and validation may proceed | B1 steps 5–6 |
| **CON.0-AMENDMENT** (C-1, earlier) | Separate shell | **ACCEPTED** (2026-09-09, morning) | `C5` |
| **STACK** (U-1, X-2, earlier) | Full Java stack; PostgreSQL; Oracle deferred | **ACCEPTED** | `C1` |
| **REPOSITORY** | Same repository, `ui2/` sub-tree, second toolchain in CI | **ACCEPTED** | B1 step 1 |
| **M14/M14L** | Local LDAP console | **PARKED**: design kept, not implemented before UI 2.0 §7 | backlog |
| **SR-D4-DISPOSITION** | Whether `UA-8`'s existing disposition ("the `DEPLOY.1A`-class decision those rows reserved; design §7") covers the council's `SR-D4` wording ("server-mode enrollment vs CON.0's OIDC-only posture") | **CONFIRMED 2026-09-09**: yes, `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` turning design §7 into a frozen specification (LDAP/AD-group RBAC in place of OIDC) **is** that reserved decision. No separate `UA-9` needed. | C3, C5 amendments bundle |
| **SR-D10-DISPOSITION** | `principal_fingerprint`: stay pseudonymous, or make it attributable to a role that can resolve it | **DECIDED 2026-09-09: attributable-on-demand, not plain pseudonymous.** Default read surfaces (logs, audit views, most of the UI) show only the fingerprint, exactly as `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` §3.2 already specifies. `security_admin` additionally gets a resolve action that reveals the underlying identity via `role_bindings`' existing encrypted-at-rest linkage (already sketched in §3.2) -- itself an audited, gated action, not a silent join. Rationale: an unkeyed 12-hex prefix reversible by DN enumeration gives away real identity to anyone who tries, while a resolve action that is gated and audited gives accountability without casual exposure. This does not reopen `C3`'s algorithm (the fingerprint value itself is unchanged); it adds one gated resolve capability, specified at C5/C3-successor detail level, not in this baseline row. | C3-successor detail (C5 amendments bundle or a small C3 addendum); B1-3 |

| **VISUAL-DESIGN-LANGUAGE** | Screen/component design system for UI 2.0 | **ACCEPTED 2026-09-09: Material Design 3.** Tonal surface containers, 16px corners, navigation rail with a pill indicator, filled/tonal/outlined/text button hierarchy, 28px dialogs, Roboto + Roboto Mono. Source: the "neXus 2026 Design Refresh" design canvas (19 artboards, 7 competing directions on one Overview screen plus a full M3 product screen set: Overview, Devices->Inventory, Configuration->Alignment, Compliance, Operations, Administration, Navigation & components); mockup content recorded in workflow-support notes for B0-8. This binds the *visual system*, not new product decisions: the RBAC visible-but-refused pattern, the class 0/1 action split shown in the mocks, and the screen-state vocabulary (Aligned / Member-specific / Local override / Difference observed / Effective drift / Not applicable / Not configured / Stale) are UI expressions of already-frozen rules (design §5, UI-OPERATIONAL-RUN-NOW, APPROVAL-MODEL) and are adopted into the baseline directory (B0-8), not re-decided here | B0-8 baseline directory; B1 step 9 (device workspace / first read screen); every later REL-* integrate step |

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

## 7. Cross-references

- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` — the plan (BASELINE rev 2).
- `docs/design/UI2_0_ARCHITECTURE_DESIGN.md` — design under amendment (§5).
- `docs/design/UI2_0_COUNCIL_REVIEW_AND_SECOND_OPINION_BRIEF.md` — review record.
- `docs/design/BACKUP_RECOVERY_CONTRACTS.md`, `docs/history/phase/OP_1_FAILOVER_PLAN_COMPILER_AND_DRY_RUN.md` — frozen semantics carried into `C7` and `REL-FAILOVER-READINESS`.
- `AGENTS.md` network-device command gate; `docs/AI_DEVELOPMENT_PROTOCOL.md` — unchanged and binding.
