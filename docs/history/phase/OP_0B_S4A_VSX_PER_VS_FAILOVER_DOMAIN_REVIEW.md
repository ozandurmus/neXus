# OP.0b S4-A — Check Point VSX failover-domain: PO architecture review

## Status

**DRAFT — DO NOT FREEZE. STOP FOR PO REVIEW (2026-09-04).**

Movement: `ARCHITECTURE` (extended reasoning). Trigger: PO correction during
the S8-B VSX real-environment campaign — the frozen model "physical VSX
parent is the sole readiness/failover unit; VS children are context only" is
**not accepted**; a Virtual System must be able to be an independent
readiness unit and, under CLASS 2, an independent operation target where
vendor semantics support it. S8-C PAN is paused. Nothing in this document
authorizes a command, edits a frozen contract, or implements anything. It
returns the exact amendments and the exact evidence still owed.

Authority hierarchy applies unchanged (`AGENTS.md`): this document ranks
below the FROZEN `OP.0b.0`, `OP.0b.1` and `OP.2.0` contracts until the PO
freezes an amendment; where it disagrees with them it **reports** the
disagreement (below) and does not reconcile it.

Evidence grades used here: `OFFICIAL` (vendor page fetched and quoted),
`SNIPPET` (official URL, sentence seen in a search-index excerpt, page not
fetchable from this environment), `TITLE-ONLY` (sk title only), `REPO`
(repository source/contract/test), `REAL-ENV` (S8 operator SAFE SUMMARY),
`UNKNOWN`. **No official page could be fetched from this sandbox** —
`sc1.checkpoint.com`, `support.checkpoint.com` and every mirror are blocked
at the egress proxy (`CONNECT 403`), so nothing below is `OFFICIAL`; every
vendor claim is `SNIPPET`/`TITLE-ONLY` at best and is marked so. Per the
vendor-semantics law nothing `SNIPPET`-grade is treated as decided.

---

## 1. Current model defects (what the repository assumes, and where)

### 1.1 The load-bearing vendor claim is written into a frozen invariant

`OP_0B_0` domain invariant 9 (`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md:202-207`):

> *"No VSLS. A Check Point Virtual System is a readiness/impact entity (its
> state is read and assessed per VS, per the real finding that VS state can
> differ from the physical member's) but not an execution target in this
> estate: **without VSLS the whole VSX gateway fails over.**"*

Everything downstream cites this row rather than a vendor page: `OP_0B_1`
§"VSX safety summary" (`:537-543`), `OP_2_0` "Identity invariants"
(`:774-779`) and P8 (`:405-408`), `FAILOVER_ENGINE_ARCHITECTURE.md` §10.2
row (`:425`), `project/roadmap.json` D-V9a/D-V9b/D-V5b, `preflight_model.py:171-174`,
`cp_preflight_projection.py:567-569`, and tests `test_op0b_s7_readiness_v2.py::test_22/23/23b/24`,
`test_op0b_s5_cp_preflight_collector.py::test_35/36/37/38`,
`test_op0a_ha_readiness.py::test_no_vsls_assumption_is_introduced_by_vsx_grouping`.

The claim is **half right by the vendor's own text** (§2): in VSX *High
Availability* mode all Virtual Systems on a member do fail over together; in
*VSLS* / "Per Virtual System State" they do not. The repository froze the HA
half as if it were the whole of VSX, then froze "no VSLS" as a scope choice —
and neither the frozen contracts nor the S8-B run has **established which
of the two this estate's approved pair actually runs** (§1.4).

### 1.2 The frozen minimum battery closed the per-VS question by scope, not evidence

- `OP_0B_0` itself specified a per-VS battery **C** (`vsenv N; cphaprob stat`,
  REQ, sk165432 caveat; `:652-654`) and evidence rows for a VS unit (`:348-361`).
- `OP_0B_1` then froze the minimum as **physical/VS0 only** (`:426`, `:533`,
  `:830`, `:960`); battery C was "not in frozen battery".
- `D-V5b` (failover statistics per-VS applicability) was closed as "not
  load-bearing — battery only requires physical/VS0-level reads" (`:1408-1414`;
  `roadmap.json` D-V5b). That is circular once the battery is widened.
- `D-V9a` (sk165432) was frozen as an interpretation rule *because* "a VS is
  never a CLASS-2 target either way" (`:1432`, `:1448-1449`). The rule itself
  (contradictory non-VS0 read → `UNKNOWN`, never `KNOWN_BAD`) stays correct;
  its "therefore not blocking" status does not survive the PO correction.

### 1.3 Product code that is structurally incapable today (REPO)

| Layer | Location | What it cannot do |
| --- | --- | --- |
| Target resolution | `application/workflows/preflight.py:227-231, 259` | accepts only `clusterxl_member`/`vsx_host` entity types; `unit_type` is `"vsx"` or `"clusterxl"`; a VS can never be named as an evaluation scope |
| Battery / session | `checkpoint/cp_preflight_battery.py:63-74, 161-191`; `checkpoint/preflight_collector.py:246-261` | `COMMAND_TEXT` is a fixed literal map, `MemberSession.run` accepts only a `CPPreflightRead` — **no code path can issue `vsenv`** from the preflight; the docstring at `:213` claims otherwise |
| Collector | `checkpoint/preflight_collector.py:373, 550-561, 663-670` | every read is stamped `FactContext.physical()`; B1 is read in VS0 and projected as category-B identity facts (`cp_vsx_vs_count`, `cp_vsx_vs_<N>_status`); exactly **one** `PreflightSnapshot` (the physical unit) is returned |
| Readiness | `utils/failover/assessment.py:919-920` | a snapshot is matched to a unit by exact `unit_id`; a VS unit never receives one; PR #63 (`ac9ead2`) made the resulting reason honest (`vs_state_out_of_physical_scope_preflight_battery`) but changed nothing structural |
| Readiness (capable, unused) | `utils/failover/preflight_readiness.py:140-144, 529, 552`; `tests/test_op0b_s7_readiness_v2.py::test_25` | the evaluator **already** accepts a VS-unit snapshot (`operational_unit_id = <group_id>__vsid_N`, members carrying `FactContext.vsid(N)` facts) and applies the D-V9a rule — proven by test_25. The model is VS-capable; the collector and the gate are not |
| Mode parser | `configuration/checkpoint_config_collector.py:976-998` | a Cluster Mode line containing "load sharing" without `multicast`/`unicast`/`pivot` returns `"unknown"`; "Virtual System Load Sharing" would therefore be parsed as **unknown** (see §1.4) |
| Legacy per-VS probe | `configuration/checkpoint_config_collector.py:1751-1753` | `vsenv N >/dev/null 2>&1; cphaprob stat` on a **fresh exec channel** — S8-A proved exec channels never reach an Expert shell on this estate, so this mature per-VS role probe is very likely inert in production (unvalidated, backlog `cp_ha_runtime` "real-env owed"); it also passes the physical hostname as the local-row token (§26 CP-4) |
| Inventory | `checkpoint/vsx_runner.py:212-214, 225, 234` | discards standby members; issues `fw ctl set int vsid` (rejected verb, CP-5) — must never feed preflight |
| UI | `static/failover_readiness_ui.js:62-79`; `utils/failover_readiness_ui.py` | nests VS rows correctly and renders seven checks per VS, but has no notion of per-check **scope** (per-VS vs shared) or of a VS's own evidence context |
| Lock | `OP_2_0` P8 `:405-408`; `backlog.json ha_entity_operational_lock` | lock subject enumerated as ClusterXL `group_id` and "CP VSX physical cluster parent"; VSIDs "never lock subjects" |
| Test guards | `test_op0b_s7_readiness_v2.py:589-592`, `test_op0b_s5_cp_preflight_collector.py:450-453`, `test_op0a_ha_readiness.py:648-657`, `test_op0b_s8_device_session_architecture.py:478-482` | source-level `"vsls" not in source` bans — any mode-conditional model that names VSLS trips them by construction |

### 1.4 A real-env finding this review must not skip (REAL-ENV, S8-B)

Both S8-B runs returned, for the **physical** VSX parent, all seven checks
`INSUFFICIENT_EVIDENCE / ha_mode_not_established` with every read `success`.
That gate fires only when at least one member's parsed `cluster_mode` is
`unknown` (or the two disagree). With the parser at `:976-998`, the
candidates are: the mode line contains "load sharing" without a unicast/
multicast/pivot token (e.g. *Virtual System Load Sharing*); or a string not
in the parser's vocabulary at all; or no line containing `mode`. **Which one
is UNKNOWN** — and it is the same unknown as the whole review: the estate's
VSX cluster type. It is resolvable with a value-free diagnostic (§9, stage
1) and it is a **prerequisite** for any VS-level model, because the
corrected model below is mode-conditional.

---

## 2. Official vendor semantics (grade marked per row; none fetched)

| # | Question | Finding | Grade | Source |
| --- | --- | --- | --- | --- |
| V1 | VSX HA vs VSLS | Two documented modes. HA: *"In the event of a failover, all Virtual Systems on the Standby VSX Cluster Member become Active"*. VSLS: *"the Active Virtual System fails over to its Standby peer… All other Virtual Systems continue to function as usual, and no failover occurs."* | SNIPPET | R81.10 VSX Admin Guide "VSX Cluster in HA Mode"; R81 "Configuring VSLS" |
| V2 | Per-VS state | HA: *"one Active VSX Cluster Member, on which all Virtual Systems are in the Active state, and one Standby… all Virtual Systems… Standby."* VSLS: *"A different VSX Cluster Member can host the Active state of each Virtual System"*; per-VS Active/Standby/**Backup**. | SNIPPET | R81.10 "Changing VSX Cluster Type"; R81 "Configuring VSLS" |
| V3 | "Per Virtual System State" | *"enables active Virtual Systems to be placed on different VSX Cluster Members, and for Virtual System-specific failover. This setting is mandatory for VSLS."* Whether it can be enabled on an HA-type cluster: **UNKNOWN** (ambiguous wording). | SNIPPET / UNKNOWN | R81.10 "Changing VSX Cluster Type" |
| V4 | "Single VS Failover" | The `Cluster Mode:` string VSX members print; vendor definition of the term **UNKNOWN** (body not fetchable). The repository already maps it to `vsx_single_vs_failover` and treats it as a supported failover mode (`CP_SUPPORTED_FAILOVER_MODES`). | TITLE-ONLY | sk112712 |
| V5 | `cphaprob stat` under `vsenv` | Documented per-VS procedure: *"run the 'vsenv <VSID>' command… Then you run the cphaprob stat command on each VSX Cluster Member to verify its status."* | SNIPPET | R81 VSX Admin Guide "General Troubleshooting Steps" |
| V6 | sk165432 | Title: *"'cphaprob stat' shows the member in 'Down' state when executed within the context of a VS other than VS0 in a **VSX HA** Cluster"*; applies-to "VSX (Traditional)". Symptoms, cause, resolution, affected/fixed releases, recommended alternative: **UNKNOWN**. Contradiction with V5 flagged — the more specific sk governs for HA-mode clusters. | TITLE-ONLY | sk165432 |
| V7 | Per-VS scope of `-a if`, `-ia list`, `syncstat`/`fw ctl pstat`, `fw stat`, failover statistics | **UNKNOWN** for every command. Only the generic V5 pattern exists. Related: sk164133 (`cphaprob -a if` on VSX has a "Virtual cluster interfaces" section) — scope not stated. | UNKNOWN | — |
| V8 | CCP / sync ownership | Sync interface is configured at VSX-cluster (member) level, *"there are no configurable properties"*; *"sync_lost messages are sent for all interfaces of the VS0 context only."* Whether CCP runs only in VS0: UNKNOWN. Per-VS sync configuration: UNKNOWN. | SNIPPET / UNKNOWN | R81 VSX "Working with VSX Clusters"; "Configuring VSX Cluster HA Mode"; R81.10 ClusterXL glossary |
| V9 | `vsx stat -v` | `vsx stat [-l] [-v] [<VSID>]`; per-VS table with a **status** field exists (sk178589: status may read `Unknown`); column list and the status value set: **UNKNOWN**. | SNIPPET / UNKNOWN | R81 CLI Ref `vsx stat`; sk178589 |
| V10 | Failover counters per cluster / per VS | **UNKNOWN**. | UNKNOWN | R81 CLI Ref "Viewing Cluster Failover Statistics" (no VS scope stated) |
| V11 | Priority / preemption | VSLS priority is **per VS** (*"Virtual System priority refers to a preference regarding which VSX Cluster Member hosts a Virtual System's Active, Standby, and Backup states"*), changed with `vsx_util vsls` **on the Management Server**. HA-mode recovery setting is a cluster-object setting; whether it applies per VS: UNKNOWN. | SNIPPET / UNKNOWN | R81 "Configuring VSLS"; R80.40 ClusterXL "HA and LS Modes" |
| V12 | `vsenv` | *"The vsenv command changes the shell's current context to the specified Virtual Device"*; also Clish `set virtual-system`. Persistence, the documented return form (`vsenv 0`), and any ClusterXL warning after `vsenv`: **UNKNOWN**. `fw ctl set int vsid` appears nowhere as a context method — stays REJECTED. | SNIPPET / UNKNOWN | R81 VSX CLI `vsenv`; R80.10 VSX guide |
| V13 | Failover action scope (read-only mapping, no action proposed) | VSLS: `clusterXL_admin down` does **not** fail over VSs (sk95133); a per-VS manual failover procedure exists for VSLS (sk56060, steps unknown); `cphastop` in VS0 fails over **all** Active VSs on that member. HA-mode `clusterXL_admin down` scope, `-p` with `vsenv`: UNKNOWN. | TITLE-ONLY / SNIPPET | sk95133; sk56060; R81 VSX "Advanced Clustering Configuration" |
| V14 | Per-VS haState exists as a vendor object | SNMP haState is exposed per VS; Backup reads "down" over SNMP in VSLS. | TITLE-ONLY | sk110653 |

**Net vendor position (SNIPPET-grade):** a Virtual System has an independent
HA state and independent failover **only under VSLS / Per Virtual System
State**. Under VSX HA the VS still has a *readable* per-VS state (V5) but
failover is member-scoped (V1/V2), and the per-VS read is documented as
unreliable in exactly that mode (V6). The PO's product requirement is
therefore vendor-supported **for VSLS-type clusters** and is a
readiness/impact view (not an action scope) for HA-type clusters — which
makes the operational entity model **mode-conditional**, not a single
choice. "Keep VSLS out of scope unless unavoidable" — it is unavoidable: the
only vendor-documented per-VS failover is VSLS.

---

## 3. Real-env evidence already held (REAL-ENV / REPO)

- One SSH transport and one persistent Expert shell per member; `echo $?`
  framing; 0.3 s pacing; A1–A8 + B1 all `success` on the approved VSX pair
  (S8-B, two runs, run ids differ, `Coherent: True`).
- `vsenv <N>` in an Expert shell is an estate-validated context mechanism
  (`AGENTS.md` "Check Point"; `capability_registry.VSX_VSENV`; 0.6.1B
  real-env). The preflight shell adapter re-learns the prompt after every
  command (`InteractiveSshSession.run` `:687-691`), so a context change does
  not break framing.
- B1 enumerates VSIDs and already retains a per-VS **status enum**
  (`active`/`standby`/`down`, else `UNKNOWN`) — currently category B
  identity facts, never a check input.
- Physical-parent readiness on this pair: mode **not established** (§1.4).
- VS-child evidence: **absent** (no per-VS read in the frozen battery).
- Earlier real finding (2026-09-02, recorded in invariant 9): a VS role
  differing from its physical member's was observed once. Under pure HA mode
  that is either the sk165432 artefact or evidence of Per-VS State — UNKNOWN
  which.

---

## 4. Correct operational entity model (proposed, mode-conditional)

| Entity | Identity (opaque, preserved) | Topology | Evidence scope | Readiness unit | CLASS 2 target |
| --- | --- | --- | --- | --- | --- |
| Physical VSX cluster | `cluster_topology.group_id` (unchanged) | container of 2 members + N VS | yes — VS0 battery A1–A8, B1 | **yes** in HA mode (member-scoped failover); in VSLS: readiness of the *shared substrate* (links, sync, pnotes, versions) — a prerequisite view, not the failover unit | HA mode: yes (future `OP.2.C`-style, whole-member). VSLS: **no** whole-cluster action defined by the vendor except `cphastop` (all VSs) — out of scope |
| Physical member | management object name + host-key trust (unchanged) | 2 per cluster | transport + identity gate; every fact is *collected from* a member | never | never (transport/host entity, `OP.2.0` "Identity invariants") |
| VS0 (management/control context) | = physical member context | the context the physical battery runs in | physical facts are VS0-context facts; sync_lost/CCP ownership is VS0 (V8) | not a separate unit — it **is** the physical unit's context | — |
| VSID N (N ≥ 1) | `<group_id>__vsid_<N>` (unchanged); evidence entity `<device>__vsid_<N>` (unchanged) | subordinate to exactly one physical cluster | its own `vsenv N` reads (**category D** per VS) + explicitly provenanced shared facts | **yes** in VSLS / Per-VS State (independent Active/Standby/Backup); in HA mode: a readiness **view** (per-VS facts + shared facts) whose failover unit is the parent | VSLS: **candidate** typed target — but the vendor primitive is management-plane (`vsx_util vsls`, sk56060), a different execution plane than gateway SSH; HA mode: **no** (no per-VS action exists) |
| Per-VS HA domain | (VSID N, both members) | the pair of `<device>__vsid_<N>` evidence entities | the VS unit's snapshot: two members, each with `FactContext.vsid(N)` facts | the object `evaluate_snapshot_checks` already evaluates (test_25) | as VSID N |
| Cluster type / mode | `ha_cluster_mode` fact, established in-run on both members | property of the physical cluster | A3 (VS0) | **gates the whole taxonomy**: `unknown` ⇒ no VS unit may evaluate positively and the parent stays `INSUFFICIENT_EVIDENCE` (already the behaviour) | — |

Rules carried forward verbatim: evidence identity ≠ operational identity; a
VS never inherits the parent verdict; a VS fact is based only on that VS
context's own observation (`OP_0B_0` §11); identifiers opaque; `VSYS`
stays subordinate (PAN, unchanged).

---

## 5. Per-check readiness scope matrix (VS unit)

`PER_VS` = evaluated from that VS's own context read; `SHARED` = physical
cluster fact, referenced with its physical provenance and shown as shared;
`N/A`; `UNKNOWN`; `BLOCKED_BY_VENDOR_SEMANTICS` = a rule exists but positive
use is blocked until the named decision closes.

| Check | VSX HA mode (all-VS failover) | VSLS / Per-VS State | Evidence for the PER_VS part | Blocking decision |
| --- | --- | --- | --- | --- |
| 1 viable_target | SHARED (member-level standby) + PER_VS corroboration | **PER_VS** (this VS has a Standby/Backup on the other member) | `vsenv N; cphaprob stat` local row (V5) — or B1 status column if its semantics are confirmed (V9) | **BLOCKED_BY_VENDOR_SEMANTICS** in HA mode (sk165432, D-V9b); UNKNOWN B1 status vocabulary (new D-V11) |
| 2 state_sync_current | SHARED (sync interface is cluster-level, V8) | UNKNOWN whether delta-sync stats are per VS | `cphaprob syncstat` under `vsenv` — scope UNKNOWN (V7) | new D-V13 |
| 3 parity — software | SHARED | SHARED | A2 (physical) | — |
| 3 parity — policy | **PER_VS** (each VS installs its own policy) — expected, vendor confirmation UNKNOWN | PER_VS | `fw stat` under `vsenv` (V7 UNKNOWN) or `vsx stat -v` per-VS policy field (V9 UNKNOWN) | new D-V14 |
| 4 no_split_brain | SHARED + PER_VS corroboration | **PER_VS** (two Actives of one VS) | both members' `vsenv N; cphaprob stat` in one run | as check 1 |
| 5 control_sync_link_health | **SHARED** (sync/CCP are VS0/cluster-level; sk93341 Bond shows Down in any VS context — a per-VS `-a if` is `UNKNOWN`, never `KNOWN_BAD`) | SHARED | A4 (physical) | — |
| 6 preemption_known | SHARED, management-plane (`D-V7b`, unchanged) | **PER_VS** priority, management-plane (`vsx_util vsls`, V11) — no read exists | none in the gateway battery | `D-V7b` + new D-V15 (per-VS priority read surface) |
| 7 flap_history | UNKNOWN scope (V10) — `D-V5b` must be **reopened** | UNKNOWN | A8 under `vsenv`? UNKNOWN | reopen `D-V5b`; `D-F3` unchanged |
| 8 (contract) no_member_failure_state / pnotes | SHARED (global pnotes register from VS0 only) | SHARED; per-VS pnotes UNKNOWN | A5 (physical) | — |

Consequences: in **no** mode may a VS's `SAFE_TO_FAILOVER` become reachable
by this review — `D-F3`/`D-V7b` still block check 6/7, and the P4 invariant
is untouched. What changes is that a VS can hold **positive evidence** for
checks 1/3(policy)/4 from its own context, and honest `SHARED` provenance for
2/3(software)/5/8, instead of blanket `INSUFFICIENT_EVIDENCE`.

---

## 6. Command battery gap analysis (proposal only — nothing authorized)

Current approved: A1–A8 (physical/VS0) + B1 (`vsx stat -v`, VS0). Insufficient
for per-VS readiness. Candidate matrix, already-known families only:

| Candidate | Context | Valid per-VS? | Vendor grade | Proposed disposition |
| --- | --- | --- | --- | --- |
| C0 `vsenv <N>` | Expert, same shell | context switch (not a read) | SNIPPET (V12); return form UNKNOWN | **Required primitive**; needs its own gate row (§12) |
| C0' `vsenv 0` | Expert, same shell | context restore | UNKNOWN (form) | required with C0; exact documented form to confirm |
| C1 `cphaprob stat` | after `vsenv N` | **yes** (V5) — but unreliable in HA mode (V6) | SNIPPET/TITLE-ONLY | propose, fail-closed under D-V9a; positive use gated on D-V9b/D-V10 |
| B1 status column | VS0 (already approved) | per-VS status enum already parsed | UNKNOWN vocabulary (V9) | **cheapest per-VS role evidence, zero new commands** — reclassify as category D candidate once D-V11 confirms the field |
| C2 `fw stat` | after `vsenv N` | expected per-VS policy | UNKNOWN (V7) | optional, second slice |
| `cphaprob -a if` | after `vsenv N` | sk93341 Bond artefact; sk164133 VSX section | UNKNOWN | **physical-only** — keep |
| `cphaprob -ia list` | after `vsenv N` | pnotes global from VS0 | UNKNOWN | physical-only — keep |
| `cphaprob syncstat` / `fw ctl pstat` | after `vsenv N` | UNKNOWN | UNKNOWN | physical-only until D-V13 |
| `show cluster failover` / `cphaprob show_failover` | Clish per VS? | UNKNOWN | UNKNOWN | physical-only until D-V5b reopened and answered |
| `cphaprob state` (A10) | VS0 / per VS | VSX-aware view? UNKNOWN | SNIPPET | stays optional/not authorized |
| `fw ctl set int vsid` | — | mutating | — | **REJECTED, unchanged** |
| `vsx_util vsls`, `cphastop`, `clusterXL_admin` | management server / gateway | mutating | — | CLASS 2 family; listed only to be excluded from preflight |

Budget if C0/C0'/C1 are approved and B1 caps the VS set: per member
`9 + N_vs × 3` commands (`vsenv N`, `cphaprob stat`, `vsenv 0`); the
approved pair with 2 VSIDs = 15/member, 30/pair; pacing unchanged. A hard
cap on `N_vs` per run is a PO number (proposal: 8; beyond that the run
records `vs_scope_truncated` and evaluates none of the excess).

---

## 7. Session / context execution model (preserves the S8-A law)

```
per physical member, ONE SSH transport, ONE persistent Expert shell:
  A1 … A8, B1                       (VS0 — unchanged)
  for N in B1.vsids[:cap]:
      vsenv N        framed; require exit 0  AND  prompt suffix ":N]" observed
      C1 (…C2)       framed, paced, NO_RETRY, facts stamped context=vsid(N),
                     operational_entity_id=<group_id>__vsid_N
      vsenv 0        framed; require exit 0  AND  prompt suffix ":0]" observed
                     (or the VS0 prompt learned before the first vsenv)
  close shell, close transport      (unchanged)
```

No reconnect per VSID, no new SSH authentication, no shell rediscovery, no
`fw ctl set int vsid`. `vsenv` enters `COMMAND_TEXT` only as a **typed
context-switch primitive with a numeric-validated VSID argument** — never
free text through `MemberSession.run`.

---

## 8. Context safety (deterministic, fail-closed)

| Requirement | Rule |
| --- | --- |
| Current VSID known | the session carries `current_context: "0" \| "<N>" \| UNVERIFIED`; every read's provenance is stamped from it, never from the loop variable |
| Explicit switch | `vsenv N` is one framed command; success = exit status 0 **and** the re-learned prompt ends with `:N]` (or `:N#`). Either missing ⇒ `current_context = UNVERIFIED` |
| Failed switch ⇒ no misattribution | in `UNVERIFIED` state the VS's reads are **not issued**; its facts are `COLLECTION_FAILED` with `context=vsid(N)` and reason `context_switch_unverified`. Nothing is attributed to VS0 or to another VS |
| Reset before next scope | `vsenv 0` verified the same way; failure ⇒ `UNVERIFIED` ⇒ remaining VS scopes for that member are skipped (`COLLECTION_FAILED:context_restore_unverified`), and the member's snapshot carries `context_restore_failed = true` |
| Cross-VS leakage | a VS snapshot is evaluated only if every one of its facts carries `context.identifier == N` and `operational_entity_id == <group_id>__vsid_N` (`preflight_readiness._attribution_problems` already enforces the second; the first is a new problem code `context_mismatch`) |
| Prompt is framing, not identity | the `:N]` token corroborates the switch; it never becomes evidence or identity (existing law, `checkpoint_config_collector.py:524-536`) |
| No new command to prove context | if the vendor `vsenv` page documents a read-only "current context" query it may be added by gate; this review invents none |

---

## 9. sk165432 / non-VS0 handling (load-bearing, fail-closed)

- Keep the frozen D-V9a rule verbatim: a `Down`/attention/contradictory
  read in a non-VS0 context is `UNKNOWN` (`non_vs0_context_read_not_trusted`),
  never `KNOWN_BAD`, never an action input. Already implemented
  (`preflight_readiness.py:529, 552`; test_25).
- **Make it mode-conditional once the mode is established**: the sk title
  scopes the artefact to a **VSX HA** cluster. In an established VSLS /
  Per-VS-State cluster the artefact is not documented; but its absence there
  is `UNKNOWN`, not proven, so the rule stays in force until `D-V9b` is
  measured on this estate *per mode*.
- **Evidence caveat surfaced, not hidden**: every VS check that consulted a
  non-VS0 `ha_local_role` carries `missing_evidence` naming sk165432 and the
  open decision, so the operator sees why a VS is not positive.
- **Alternative read**: the B1 status column (VS0 context, already approved,
  not subject to sk165432) — usable only after its semantics are confirmed
  (D-V11). No workaround command is invented.
- Estate measurement (D-V9b) becomes **blocking for VS readiness** (it was
  "informational" only because a VS was never a target).

---

## 10. UI model

- Physical VSX cluster row: topology container **and** its own readiness
  (physical battery), labelled with the established mode (`VSX HA` /
  `VSLS` / `unknown`).
- VSID rows: nested (unchanged), each a readiness unit with its own seven
  checks; each check row carries a **scope tag** — `per-VS` (own context),
  `shared` (physical fact, with its physical provenance shown), or
  `not evaluable` (with the decision id) — so the VS verdict is honest about
  what was actually read in its context.
- No parent-verdict inheritance (unchanged). `vs_state_out_of_physical_scope_preflight_battery`
  stays as the reason for a VS that had **no** context read in the run
  (e.g. above the cap, or context unverified).
- Console/CLI parity law unchanged: one evaluation, two renderers; the SAFE
  SUMMARY gains one block per evaluated VS unit.

---

## 11. CLASS 2 impact (`OP.2.0`)

- `OP_2_0` "Identity invariants" (`:774-779`), P8 entity examples (`:405-408`),
  scope-out line `:148`, readiness-matrix column `:1520-1532`, and the
  §10.2 override row in `FAILOVER_ENGINE_ARCHITECTURE.md:425` state the
  rejected model. **Amendment required: YES** — but as a mode-conditional
  addition, not a reversal:
  - *HA-type VSX cluster*: physical cluster parent remains the only action
    target (vendor: member-scoped failover). Unchanged.
  - *VSLS / Per-VS-State cluster*: a **specific VSID is a typed operational
    action target**; the physical cluster remains the transport/host entity
    and the shared-evidence provider. The vendor primitive is
    **management-plane** (`vsx_util vsls` priority change, sk56060) — a
    different adapter shape and execution plane than `OP.2.C`'s gateway SSH
    ClusterXL adapter; it needs its own `OP.2.1` gate rows and its own
    `settle_observation`.
- **Lock grain** (P8): the record-uniqueness lock stays; the subject set
  gains the VS unit id. Interference semantics between two VSs of one
  cluster (they share CCP/sync/members) are **UNKNOWN**; until vendor
  evidence says otherwise the safe rule is **nested exclusivity**: an action
  on the parent excludes every child; an action on a child excludes the
  parent and that child; sibling actions are **serialized** (capacity 1 per
  physical cluster), not parallel. This is a recommendation, not a decision.
- `authorize()` stays unconditional `DENY`; no member, no adapter, no
  command — CLASS 2 remains unreachable. VSID target status: **BLOCKED**
  (mode establishment, V4/V6/V13 semantics, management-plane gate, lock
  semantics, `DEPLOY.1A`, trust hardening — all of the existing CP column).

---

## 12. Command-gate amendment proposal — `OP.0b S4 VSX PER-VS COMMAND GATE AMENDMENT`

Format mirrors `OP_0B_1` per-row records. **DO NOT implement until PO approves.**

**CP-C0 — `vsenv <VSID>` (context switch)** · Vendor Check Point · Action
class `CLASS_0_READ` (shell-context change; no device state, no
configuration, no kernel parameter) · Execution plane: device-direct SSH,
the existing per-member persistent Expert shell · Shell/context: Expert,
from VS0 · Argument: VSID from B1's own enumeration of this run, numeric,
bounded by the PO cap · Expected calls per member: 1 per evaluated VS, +1
`vsenv 0` restore per VS · Concurrency/timeout: as CP-A4 · Retry: `NO_RETRY`
· Session reuse: **mandatory** — no new transport, no new shell (B1 law) ·
Read-only proof: `vsenv` "changes the shell's current context" (V12,
SNIPPET); the documented return form and any ClusterXL warning must be
fetched from the R81.x `vsenv` page before approval · Sensitive output:
none (prompt only) · Safe retained: `context_switch_verified` boolean, exit
status · Failure semantics: unverified ⇒ `COLLECTION_FAILED` for that VS,
no attribution (§8) · Real-env: S8-B' stage 2 · **Decision: PROPOSED**.

**CP-C1 — `cphaprob stat` in VS context** · category D (per VS) ·
Shell/context: Expert, after a verified `vsenv N` · Calls: 1 per evaluated
VS per member · `NO_RETRY` · same session · Read-only: already approved as
A3 in VS0; the VS-context invocation is the per-VS procedure in the VSX
Admin Guide (V5) · Sensitive output: as A3 (member names, unique IPs —
discarded) · Safe retained: local role, mode, attention, peer-row states
(same parser, same projection, `context=vsid(N)`), **local-row match by the
`(local)` marker only — never the physical hostname** (closes §26 CP-4) ·
Unsupported semantics: sk165432 (D-V9a rule in force; D-V9b per mode) ·
Real-env: S8-B' stage 2 (D-V9b measurement) · **Decision: PROPOSED**.

**CP-B1 reclassification (no new command)** · the per-VS status enum
`parse_vsx_stat_v` already retains gains a **category D** projection in
`context=vsid(N)` **only after** D-V11 confirms the field's vocabulary and
meaning from the R81.x `vsx stat` page · until then it stays category B ·
**Decision: PROPOSED, conditional on D-V11**.

**CP-C2 — `fw stat` in VS context** (optional, second slice) · category H
(policy parity per VS) · conditional on D-V14 · **Decision: DEFERRED**.

Not proposed (physical-only, unchanged): A4, A5, A6, A7, A8, A10, A11.
Rejected (unchanged): `fw ctl set int vsid`, `show cluster failover reset
history`, `cphaprob … register/unregister`, `clusterXL_admin`, `cphastop`,
`vsx_util`.

Privacy/provenance: VS names never retained (unchanged); VSID is an opaque
identifier (no numeric normalization beyond the existing numeric
validation); every VS fact carries `context=vsid(N)`, `operational_entity_id`
= the VS unit id, `source_command` = the fixed read id (the `vsenv N` wire
form is recorded as the context primitive, per the frozen provenance
contract "`vsenv N` retained").

---

## 13. OP.0b contract amendments (exact, for PO decision — not applied)

| Contract | Location | Amendment |
| --- | --- | --- |
| `OP_0B_0` | invariant 9 (`:202-207`) | replace *"without VSLS the whole VSX gateway fails over"* with the mode-conditional statement of §2/§4: HA-type ⇒ member-scoped failover, VS = readiness view; VSLS/Per-VS-State ⇒ VS = independent readiness unit and candidate typed target; mode `unknown` ⇒ fail closed |
| `OP_0B_0` | operational entity model row (`:215`) | "Execution target (future)": *physical cluster (HA-type) / VSID (VSLS-type, management-plane primitive)*; "Subordinate context": *VS = readiness unit, subordinate topology* |
| `OP_0B_0` | battery C (`:652-654`) | promote from "readiness/impact only" to the per-VS battery of §6/§12; drop "No per-VS action is planned in this estate" |
| `OP_0B_0` | §26 CP-4 | P0 **now** (not "before VS readiness is trusted" — that is this build) |
| `OP_0B_0` / roadmap | `D-V5b` | **REOPEN** (per-VS failover statistics scope) |
| `OP_0B_0` / roadmap | `D-V9b` | from informational to **blocking for VS readiness**, measured per mode |
| roadmap `open_decisions` (new) | `D-V10` VSX cluster type of the approved pair (HA vs VSLS/Per-VS-State) — real-env + official; `D-V11` `vsx stat -v` status field semantics; `D-V12` `vsenv` return/verification form; `D-V13` per-VS delta-sync scope; `D-V14` per-VS policy read; `D-V15` per-VS priority (VSLS) read surface | all `open`, `decide_by` = S4-A freeze |
| roadmap | `op_aa_vsls_scope` | **REOPEN** — VSLS is no longer deferrable if D-V10 says the estate runs it; A/A PAN stays deferred |
| `OP_0B_1` | §"VSX safety summary" (`:523-543`), §"Minimum battery", gate table | add CP-C0/C1 rows and the B1 reclassification; strike the invariant-9 sentence |
| `OP_2_0` | "Identity invariants" `:774-779`; P8 `:405-408`; scope-out `:148`; matrix `:1520-1532` | §11 wording (mode-conditional target; VS lock subject; nested exclusivity recommendation) |
| `FAILOVER_ENGINE_ARCHITECTURE.md` | §10.2 row `:425` | supersession marker: §3.1's "N logical failover units (one per VS) plus the physical unit" is restored **under the VSLS/Per-VS-State condition** |
| Tests | the four `"vsls" not in source` guards; `test_22/23/23b/24/35/36/37/38` | rewrite as mode-conditional assertions (a VS unit may carry its own snapshot; VSLS may be *named*; no VSLS *assumption* may enter the HA-mode path) |
| `CURRENT_STATE.md`, `AI_HANDOVER.md`, `feature_registry`, `backlog` | S8-B row; `cp_ha_runtime` (exec-channel per-VS probe likely inert) | after PO decision |

---

## 14. Real-environment validation plan (bounded, staged, SAFE SUMMARY only)

| Stage | Device contact | What it resolves | Output |
| --- | --- | --- | --- |
| 0 — official docs (human fetch, egress blocked here) | none | V4 (sk112712), V6 (sk165432 body), V12 (`vsenv`), V9 (`vsx stat`), V3 (Per-VS State on HA clusters), V13 (sk95133/sk56060), V7 for `-a if`/`fw stat` | upgrade rows to `OFFICIAL`; decide D-V10 (doc half), D-V11, D-V12 |
| 1 — mode establishment | **existing approved battery only**, one parse-scope extension of A3 (no gate row needed per `AGENTS.md` "parse-scope extension") | why `ha_mode_not_established`; the pair's cluster type | SAFE SUMMARY adds a value-free mode classification: which fixed tokens the `Cluster Mode` line contained (`high availability`, `load sharing`, `virtual system`, `single vs failover`, `vrrp`, none) per member — an enum, never the line |
| 2 — S8-B' per-VS run | after PO approval of CP-C0/C1: same pair, 2 VSIDs, `9 + 2×3` reads/member, same shell | D-V9b per mode; context-proof mechanics; per-VS role/mode vs B1 status (`MATCH`/`MISMATCH`/`NOT_EVALUABLE` — relationship, not values) | SAFE SUMMARY per VS unit: context verified?, reads outcome, seven checks with scope tags, parent unchanged |
| 3 — console parity | none (render) | VS rows: per-VS vs shared scope tags, no inheritance, no duplication | operator visual acceptance |
| 4 — only if D-V10 = VSLS | none | CLASS 2 VSID target design (`OP.2.1` management-plane rows) | separate contract |

---

## 15. Architecture status (answers A–H)

- **A. Wrong assumptions:** (1) "without VSLS the whole VSX gateway fails
  over" generalised to all VSX; (2) "no VSLS in this estate" taken as a fact
  rather than an unestablished mode; (3) per-VS readiness closed by battery
  scope (D-V5b) rather than evidence; (4) D-V9b "informational" because a VS
  was never a target; (5) the preflight docstring claiming `vsenv` in-session
  when no code path can issue it; (6) the config collector's per-VS probe
  assumed working on an exec channel.
- **B. Contracts needing amendment:** `OP_0B_0` (invariant 9, entity table,
  battery C, D-V5b, D-V9b, CP-4), `OP_0B_1` (new rows, safety table),
  `OP_2_0` (identity invariants, P8, scope, matrix), `FAILOVER_ENGINE` §10.2
  (marker), roadmap decisions (D-V10–D-V15, `op_aa_vsls_scope`).
- **C. Structurally incapable code:** §1.3 table — target resolution,
  battery/session (`vsenv` unsendable), collector (one physical snapshot,
  physical context only), mode parser ("load sharing" ⇒ unknown), legacy
  exec-channel per-VS probe, UI scope tags, lock subject set, four VSLS test
  guards. **Capable already:** the fact model (`FactContext.vsid`), the
  evaluator (test_25), unit derivation (`<group_id>__vsid_N`), nested UI.
- **D. Command-gate expansion needed:** CP-C0 `vsenv N`/`vsenv 0` (context
  primitive), CP-C1 `cphaprob stat` per VS, B1 status reclassification
  (conditional), optional CP-C2 `fw stat` per VS. Nothing else; no
  management-plane read is proposed here.
- **E. Vendor semantics UNKNOWN:** sk165432 body; "Single VS Failover"
  definition; Per-VS State on HA clusters; per-VS scope of `-a if`, `-ia
  list`, `syncstat`, `fw stat`, failover statistics; CCP VS0 ownership;
  `vsx stat -v` status vocabulary; `vsenv` return form; HA-mode `clusterXL_admin`
  scope; per-VS recovery/preemption in HA mode; VS-vs-VS action interference.
- **F. Can VSID be an independent readiness unit?** **YES, conditionally**:
  vendor-supported as an independent HA domain under VSLS/Per-VS-State; a
  readiness *view* with its own per-VS facts under HA mode; never while the
  mode is `unknown`. The evaluator already supports it; the collector and
  gate do not.
- **G. Can VSID be an independent CLASS 2 target?** **BLOCKED** — vendor
  primitive exists only for VSLS and lives on the management server; mode
  unestablished; no gate row; lock interference UNKNOWN; all existing CLASS 2
  blockers apply. Not `READY`, not `NO`.
- **H. What remains physical/shared:** transport and identity gate;
  software version; sync interface, CCP and link health (VS0); global
  pnotes; HA-mode preemption (cluster object); the physical cluster as
  topology container and, in HA mode, as the failover unit; failover
  statistics until D-V5b answers.

---

## 16. Recommended next movement

1. **PO decision** on this review (accept the mode-conditional model or not).
2. **Stage 0** (human, no device): fetch the seven official pages named in
   §14 — this is the cheapest way to convert most `SNIPPET`/`UNKNOWN` rows
   to `OFFICIAL` and to settle D-V10's documentary half.
3. **Stage 1** (operator, existing battery + one parse-scope extension,
   normal reasoning): establish the pair's cluster type and clear
   `ha_mode_not_established`. This unblocks the **physical** VSX readiness
   regardless of the VS decision.
4. Then `CONTRACT` movement (extended reasoning): freeze the amendments of
   §12/§13 as `OP.0b S4-A`, then `IMPLEMENTATION` of the collector/battery/
   UI changes at normal reasoning, then S8-B' (stage 2/3).
5. S8-C PAN stays paused until the PO says otherwise; nothing here touches PAN.

Recommended model/reasoning: this review — done at extended reasoning;
Stage 1 diagnostic and the eventual implementation — `Sonnet 5, normal`;
the contract freeze — `Sonnet 5, extended thinking (high)`.
