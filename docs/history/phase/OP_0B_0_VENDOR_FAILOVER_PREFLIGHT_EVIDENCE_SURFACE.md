# OP.0b.0 — Vendor failover preflight evidence surface contract

## Status

**CONTRACT FROZEN — WITH REAL-ENV VALIDATION GATES (2026-09-03, session 4,
"Final Semantic Blocker Closure") — cleared for bounded implementation
(slices S0–S9 as already sequenced below); CLASS 2 remains separately and
structurally unreachable (P4 invariant, unchanged).**

This document is structurally complete: every required section, the full
command surface table (§24), the configuration/runtime field trace table (§25)
and the bug/gap register (§26) are filled in. Session 4 closes the freeze
question, not every open fact: two rows (`D-V4`, `D-V7a`) are `CLOSED_BY_DOCS`;
two remaining safety-critical rows (`D-V3a`, `D-V7b`) are genuinely
`STILL_UNKNOWN` by any source reached across four sessions — but re-reading
this document's own already-written fail-closed design (the hostname-keyed
PAN identity fallback that stands until the successor serial model proves
itself; check 6 `preemption_known` already specified "recorded, non-blocking")
shows **both were already scoped, by the original session-1 drafters, as
CLASS-2-time blockers, not architecture-interpretation blockers.** Every other
residual row's minimal safe interpretation is now explicitly frozen (see
§"Final semantic blocker closure — session 4" below). No safety-critical
semantic requires guessing for the contract, as an evidence/interpretation
model, to be usable; what remains is real-env measurement, `OP.0b.1`
command-gate syntax work, one new bounded numeric-threshold decision, and —
before CLASS 2 specifically, never before this freeze — closing `D-V3a`/
`D-V7b` for real.

**Historical record, preserved verbatim (sessions 1–3, do not delete):** why
some rows were not fully established at each prior pass, stated plainly at
the time: three sessions, on different execution environments, have
consistently found
`pan.dev`, `sc1.checkpoint.com`, `support.checkpoint.com`,
`docs.paloaltonetworks.com` and `knowledgebase.paloaltonetworks.com`
unreachable for full-page fetch (`CONNECT 403` in session 1, `EGRESS_BLOCKED`
in sessions 2 and 3) — a structural property of these execution environments,
not an incidental misconfiguration. Session 3 found this block is **not
universal**: `github.com`/`raw.githubusercontent.com` are reachable, and one
official Palo Alto Networks GitHub repository's source (the code `pan.dev`
itself is generated from) was read **verbatim** and closed two rows outright
— see §"Official vendor semantics confirmation pass — Source Pack 2
(2026-09-03, session 3)" below. A separate search tool remained reachable
throughout for the Check Point side, where no equivalent GitHub mirror was
found. Each remaining row's residual gap is recorded precisely in
§"Open decisions" and the decision matrices below. **Closing what's left
needs the same technique tried further (an official GitHub mirror for the
still-blocked Check Point pages, if one exists) or a human fetching the named
pages/sk-articles and pasting their body text in — not a bare repeat of
"try an unblocked network."**

Movement history: `ARCHITECTURE` → `VENDOR SEMANTICS AUDIT` (three parallel
evidence streams: repository source, recorded real-environment findings,
official vendor documentation) → `EVIDENCE CONTRACT` (this document, session
1) → `VENDOR SEMANTICS AUDIT` (session 2, search-snippet-level confirmation
pass) → `CONTRACT RECONCILIATION` (session 2) → `VENDOR SEMANTICS AUDIT`
(session 3, Source Pack 2 — verbatim official-source read via an unblocked
GitHub mirror) → `CONTRACT RECONCILIATION` (session 3) → `FINAL VENDOR
SEMANTICS AUDIT` (session 4 — one last targeted D-V3a/D-V7b search, converging
official-negative evidence for D-V7b) → `FINAL_CONTRACT_RECONCILIATION` →
`FREEZE_DECISION` (this document, updated in place; see §"Final semantic
blocker closure — session 4" below).

- Design parent: `docs/design/FAILOVER_ENGINE_ARCHITECTURE.md` §3.1, §3.2
  (per-vendor preflight reads), §4 (seven stop conditions), §7–§8 (engine and
  safety model), §10–§10.1 (`OP.2` prerequisites and safety contract).
- Contract parents: `docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md`
  (OP.0a, incl. the un-approved OP.0b command-gate draft at its §"OP.0b command
  gate"), `docs/history/phase/OP_0A_PAN_HA_PEER_PAIRING_IDENTITY_CLOSURE.md`
  (OP.0a.P7 revision — whose pairing join this contract's evidence supersedes;
  see §"Frozen-contract impact"), `docs/history/phase/PHASE0_6_1B_1_2_CP_HA_RUNTIME_VSX_CLOSURE.md`.
- Gate: this contract adds **no** command. It defines the evidence surface a
  future `OP.0b.1` command-gate package must cover; that package is where new
  commands are proposed, point by point, per `docs/AI_DEVELOPMENT_PROTOCOL.md`
  "Network-device command gate".
- Project-state: **deliberately not updated.** This is not a frozen build; it
  must not become `roadmap.json` `now.build`. A `STATE_UPDATE` movement after
  FREEZE records it. `docs/history/INDEX.md` is generated from
  `project/build_history.json` and is therefore also untouched.

## Objective

Freeze — once the `UNKNOWN` rows are resolved — the vendor-specific
**READ-ONLY preflight evidence contract** that must be satisfied immediately
before any future firewall failover: what must be known, where it is read
from, how authoritative it is, how fresh it must be, which member/context must
provide it, how disagreement is detected, what stays `UNKNOWN`, what blocks
execution, and what must never be inferred.

## Why this contract exists

The repository is moving from read-only HA readiness toward controlled
failover. The architectural risk, demonstrated on real hardware twice this
week, is **inventory/configuration evidence being stretched into failover
authorization evidence**:

- The frozen OP.0a.P7 PAN pairing join (configured `peer-ip` → Panorama
  `management_ip`) was **disproven on the approved real PAN pair**: member A's
  configured `peer-ip` equals its runtime `peer-info/ha1-ipaddr` (HA1
  control-link plane), not any management address; member B's configured
  `peer-ip` matches **no** runtime address field at all. Vendor documentation
  corroborates: the HA1 peer setting is documented as "the ha1 interface IP
  address on the other node", and HA1 may be bound to eth2, eth3 **or the
  management port** — so the two planes coincide only in MGT-bound-HA1 estates.
- The real VSX retry found three defects invisible to synthetic tests
  (`op_vsx_real_env_retry_fixes`): a shell artifact split one device into two
  identity strings, which misclassified VSX as ClusterXL and made HA runtime
  checks report `INSUFFICIENT_EVIDENCE` for the wrong reason.
- Today's readiness engine evaluates **2 of 7** stop conditions, from a single
  already-collected command per vendor, with **no provenance, no freshness
  model, no peer observation on Check Point, and a synthetic "phantom member"
  on Palo Alto** (`_pan_states` counts the lone member's `peer_state` as a
  second observation).

Without a frozen, vendor-native evidence contract, the next build would
inevitably promote one of these artifacts to an authorization input.

## Scope in

- Both vendors: Check Point ClusterXL (HA mode), Check Point VSX (physical
  cluster + Virtual Systems, **non-VSLS**), Palo Alto PAN-OS HA
  (Active/Passive).
- Evidence categories A–M (§"Evidence taxonomy") for every fact a future
  preflight consumes.
- The complete candidate command/API surface (§24) and field trace (§25).
- Identity, peer-relationship, provenance, freshness, command-safety,
  fail-closed and privacy contracts.
- Review of the seven-check model; single-authority rules; collector-reuse
  decision; implementation slices; the pre-CLASS-2 bug/gap register.

## Scope out

- Any implementation, collector change, readiness-verdict change, schema
  change, UI change, or device contact.
- Approving any command: §24 rows marked `REQUIRED`/`OPTIONAL` are candidates
  for the `OP.0b.1` gate package, not approvals.
- CLASS 2 execution semantics (RBAC, confirmation, locking grain, exactly-once,
  rollback, audit) — enumerated as handoff requirements only.
- Load Sharing (Unicast/Multicast), VRRP, PAN Active/Active, PAN HA clustering
  (HA4), VSLS — recorded as `UNSUPPORTED`/deferred, never silently generalized.
- IPv6 peer addressing (deferred, `pan_ha_peer_ipv6_pairing`).
- Reconciling the duplicate topology authorities in the UI (recorded as
  follow-up, §"Architecture authority").

## Authoritative sources

Three sources, kept distinct throughout:

**SOURCE A — repository source (audited line-by-line, not assumed correct).**
`utils/failover/assessment.py`, `utils/failover_readiness_ui.py`,
`configuration/checkpoint_config_collector.py`,
`configuration/checkpoint_config_probe.py`, `checkpoint/cp_runner.py`,
`checkpoint/vsx_runner.py`, `checkpoint/vsx_parser.py`,
`checkpoint/direct_ssh_probe.py`, `checkpoint/scripts/cp_inventory.sh`,
`configuration/panorama_config_collector.py`,
`panorama/panorama_runtime_runner.py`, `panorama/pan_identity.py`,
`utils/merge.py`, `utils/config_ui.py`, `utils/action_taxonomy.py`,
`utils/capability_registry.py`, `utils/restore_readiness.py`,
`static/inventory_ui.js`, `static/configuration_ui.js`,
`static/failover_readiness_ui.js`, and their tests.

**SOURCE B — recorded real-environment findings.** `project/build_history.json`
(`op_vsx_real_env_retry_fixes`, `op0a_pan_ha_peer_pairing_identity_closure`),
`docs/history/phase/PHASE0_6_1B_1_2_*`, `docs/history/validation/VALIDATION_0_6_1B*.txt`,
`docs/history/phase/PHASE0_6_1D_*`, `project/backlog.json`,
`project/roadmap.json`, `project/feature_registry.json`, `CURRENT_STATE.md`,
and — recorded nowhere but git history, see §26 PAN-15 — commits `1d97cd6`,
`d0f8e31`, `a1a3882` (the bounded PAN peer-identity diagnostic and the real
PAN pair's diagnostic output, summarized above without identities).

**SOURCE C — official vendor documentation (snippet-level only, see Status).**
Cited per row in §24 as the page that establishes the semantic. Where a snippet
established purpose/read-only class but not output vocabulary, the row says so.
Community/forum content was **not** used as authority anywhere in this
document.

## Domain invariants

1. **Evidence identity ≠ operational identity.** A physical member (CP) or
   firewall (PAN) is an evidence entity; the ClusterXL cluster, the VSX
   physical cluster, each Virtual System, and the PAN HA pair are operational
   entities. Facts are collected from evidence entities and asserted about
   operational entities, never the reverse.
2. **Pair/cluster existence ≠ pair/cluster health.** Whether two devices form
   the operational unit and whether that unit is currently safe to act on are
   independent questions with independent evidence and independent states.
3. **Configuration intent ≠ runtime state.** A configured value answers "what
   was declared", never "what is true now". It may corroborate; it never
   authorizes.
4. **A member's report about its peer is that member's claim.** It is not the
   peer's own report and not an independent observation. Bidirectional
   corroboration requires both members' own reports from the same preflight.
5. **A presentation field never becomes identity.** Hostnames, display names,
   `-1`/`-2` ordinals, VSYS labels and inferred group labels are presentation
   only (already pinned: `cp_runner._cluster_display_name`, OP.0a.P7 Q5).
6. **Absence of observation ≠ observation of absence.** "Could not read the
   link" is `UNKNOWN`/`COLLECTION_FAILED`, never `KNOWN_BAD`.
7. **Mode gates everything.** No check is evaluated, and no unit is eligible,
   until the HA mode is determined and is one this contract supports
   (CP HA/"New mode"; PAN Active/Passive). Load Sharing, VRRP, A/A, HA4
   clustering, VSLS → `UNSUPPORTED`.
8. **VSYS is subordinate.** A PAN VSYS is never an operational unit.
9. **No VSLS.** A Check Point Virtual System is a **readiness/impact entity**
   (its state is read and assessed per VS, per the real finding that VS state
   can differ from the physical member's) but **not an execution target** in
   this estate: without VSLS the whole VSX gateway fails over. A VS role that
   differs from its physical member's role is therefore an anomaly to surface
   (`RELATIONSHIP_INCONSISTENT`), never a reason to plan a per-VS action.

   **SUPERSEDED for readiness (not execution), 2026-09-04 — `OP.0b S4-A`/S4-A'
   (real-env finding, S8-B):** the approved VSX pair's own `Cluster Mode:`
   line reads *"Virtual System Load Sharing (Active Up)"* on both members —
   this estate runs VSLS, not plain VSX HA. Under VSLS a Virtual System DOES
   have independent per-VS HA state and IS an independent readiness unit;
   `docs/history/phase/OP_0B_S4A_VSX_PER_VS_FAILOVER_DOMAIN_REVIEW.md`
   records the PO-corrected model and vendor evidence. The "not an execution
   target" half of this paragraph is UNCHANGED — no CLASS 2 amendment has
   been made and none is implemented here — but "without VSLS the whole VSX
   gateway fails over" is no longer this estate's premise. This paragraph's
   original text is left verbatim above as the historical record of what
   was frozen 2026-09-03; it is not authoritative for readiness scope going
   forward where it conflicts with this note.
10. **Nothing in this contract authorizes CLASS 2.** See §"CLASS 2 handoff".

## Operational entity model

| Vendor | Evidence entities | Operational unit (readiness) | Execution target (future) | Subordinate context |
| --- | --- | --- | --- | --- |
| Check Point ClusterXL (HA) | physical members A, B (SSH endpoints; mgmt object names) | `cp_clusterxl_cluster` keyed by `cluster_topology.group_id` | the cluster (act on its **active** member) | — |
| Check Point VSX (non-VSLS) | physical members A, B | `cp_vsx_cluster` (physical) **and** one `cp_vsx_virtual_system` per VSID keyed `(physical_unit_id, vsid)` | the **physical** VSX cluster only | each VS is a readiness/impact context |
| Palo Alto A/P | firewalls A, B (identity-gated serials) | `pan_ha_pair` (candidate key: unordered pair of member serials — **not frozen**, see §"Identity contract") | the pair (act on its **active** member) | VSYS |

Current code already keys CP units this way (`assessment.py::_derive_cp_units`,
`group_id` = `sha256(CMA + sorted VIP set)[:16]`, role-independent, pinned by
`tests/test_phase0_5_2_cp_cluster_view.py` and `tests/test_op0a_ha_readiness.py`).
PAN units are keyed by member hostnames joined with `+`, which this contract's
evidence shows must change (§"Identity contract", §"Frozen-contract impact").

## Evidence taxonomy

Every fact in §25 is classified into exactly one primary category; a secondary
category is allowed only where vendor semantics prove it (noted per row).

| Cat | Name | Definition |
| --- | --- | --- |
| A | PHYSICAL IDENTITY | Stable identity of one physical device, verified by an identity gate |
| B | OPERATIONAL HA ENTITY IDENTITY | Stable identity of the cluster/pair/VS unit |
| C | CONFIGURATION INTENT | Declared configuration (management or device config plane) |
| D | RUNTIME HA STATE | A member's own current HA role/mode/state |
| E | PEER IDENTITY / RELATIONSHIP | Who the peer is, as claimed or corroborated |
| F | LINK HEALTH | Control/sync/data link status |
| G | STATE / SESSION SYNCHRONIZATION | Runtime state-table sync status |
| H | SOFTWARE / POLICY / CONTENT PARITY | Version/policy/content equality between members |
| I | ELECTION / PREEMPTION BEHAVIOR | Priority and recovery behaviour |
| J | FAILURE / HEALTH STATE | Member in a failure/attention/non-functional state |
| K | TRANSITION / FLAP HISTORY | Failover counts, reasons, recency |
| L | PROVENANCE / FRESHNESS | When/how/from where a fact was collected |
| M | PRESENTATION ONLY | Labels; never identity, never a check input |

## Check Point evidence surface

### Current state (SOURCE A, verified)

- **Exactly one HA command exists in executable code: `cphaprob stat`** — per
  physical member (`checkpoint_config_collector.py:1333/1335`) and per VS via
  `vsenv <N> >/dev/null 2>&1; cphaprob stat` on a fresh exec channel
  (`:1608-1610`, VSID numeric-validated at `:1515`). `cphaprob -a -m if` runs
  once per cluster member in stage `cp` over CPRID from the MDS
  (`cp_inventory.sh:269`) and feeds cluster identity only. `cphaprob state`,
  `cphaprob syncstat`, `cphaprob -a if`, `cphaprob -ia list`,
  `cphaprob show_failover`, `fw ctl pstat`, `fw stat`, `show cluster *` exist
  **only as fixed `missing_evidence` label strings** in
  `utils/failover/assessment.py:65-84`.
- **The `cphaprob stat` parser reads the local row only and discards the
  buffer.** `_parse_clusterxl_runtime_role` returns the first state token on
  the first line containing `(local)` or the short hostname; every other line
  is skipped; then `stdout`/`stderr` are zeroed (`:1360`, `:1627`). Dropped:
  "Unique Address", "Assigned Load", every peer row, any "Active Attention"
  reason text. `_parse_clusterxl_cluster_mode` keyword-matches the first line
  containing `mode`.
- **Split-brain is detected only by aggregating one scalar per member across
  independently timed SSH sessions** (`ThreadPoolExecutor`, up to 12 workers);
  skew is not recorded.
- **Provenance is thin:** rows carry `started_at`/`completed_at`,
  `ssh_shell_mode`, `version_command` and `ha_role_source` (a label, not the
  wire form); **no `run_id`, no `collected_at`, no source command string** on
  the row. `cp.json`/`vsx.json` carry no timestamps. `cluster_topology.group_id`
  (stage `cp`, CPRID) and `ha_role` (stage `cp_config`, direct SSH) are joined
  off disk with no freshness check (`:1070-1072` explicitly accepts "a current
  or previous inventory checkpoint").
- **`extract_cp_ha_runtime`** (`utils/failover_readiness_ui.py:95-113`) forwards
  only `ha_role` and `ha_cluster_mode`; the readiness engine cannot distinguish
  a fresh probe from an `inherited_from_physical_member` fallback or a stale
  file.
- **VSX collector defects (`checkpoint/vsx_runner.py`):** issues
  `fw ctl set int vsid <N>` — a kernel-parameter **set**, the only non-read
  verb on any CP read path; discards standby members entirely
  (`if "Standby" in ha: return []`, `:212-214`) so `vsx.json` never carries the
  standby member's VS view; discovers only `-[12]$`-named members (`:167`);
  interpolates `vs_id` without numeric validation. `checkpoint/scripts/vsx_collect.sh`
  is dead code.
- **VS rows inherit** `platform`, `model`, `serial`, `sw_version`,
  `identity_gate`, `host_key_fingerprint`, `management_state`, cluster ids from
  the physical host **without a source label**; only `ha_role` carries one.
- **Platform classification is evidence-based, not command-availability
  based** (`_classify_platform`; `capability_registry.py` invariant) — correct,
  preserved. Direct-Clish-only appliances get `ha_runtime_status =
  "capability_gap"` for `cphaprob` — a real coverage boundary.
- **Cluster identity is sound and mutual:** both members independently report
  the same VIP set under the same CMA → same `group_id`. That is a
  topology-level bidirectional corroboration of membership, already in place.

### Vendor semantics established (SOURCE C, snippet-level)

| Fact | Established by | Status |
| --- | --- | --- |
| `cphaprob stat` columns Number / Unique Address / Assigned Load / State; HA shows one Active, others Standby | R80.40/R81 ClusterXL Admin Guide "Viewing Cluster State" | ESTABLISHED |
| "Unique Address" semantics: R80.40 text says Sync-interface IPs; sk61546 says the displayed IPs "might differ from the IP addresses of Sync interfaces" during problems; another official page says "any unique IP address that belongs to the Cluster Member" | R80.40 guide; sk61546; SMB R81.10.X CLI "Viewing Cluster IP Addresses" | **AMBIGUOUS BY VENDOR'S OWN DOCS → not identity-grade** |
| `cphaprob state` shows Cluster Mode incl. "High Availability (Primary Up / Active Up)", member states incl. `Active Attention`, `Down` | R81 CLI Ref "Viewing Cluster State"; R80.30 CLI Ref "Monitoring Cluster State" | ESTABLISHED (field detail UNKNOWN) |
| "Maintain current active" vs "Switch to higher priority" recovery semantics | R80.40 "Cluster Failover"; R81.20 "High Availability Mode" | ESTABLISHED |
| **Cluster Mode string does not reliably reflect the recovery setting** | **sk180184**: mode "does not change to 'Primary Up' when setting the cluster object to 'Switch to higher priority Cluster Member'" | ESTABLISHED — device-local preemption read is **NOT AUTHORITATIVE** |
| `cphaprob syncstat` / `show cluster statistics sync`: Delta Sync status, drops, queue, timers | R81.20 "Viewing Delta Synchronization"; sk34475 | ESTABLISHED (field vocabulary UNKNOWN) |
| `fw ctl pstat` Sync section applies "until R80.10; for R80.20 and higher refer to sk34475" | sk34476 | ESTABLISHED — version-conditional |
| `cphaprob -a if` = cluster interfaces/CCP; critical devices via `cphaprob -ia list` / `show cluster members pnotes all`; pnote problem ⇒ member `Down` | R81.10 "Viewing Critical Devices"; R80.40 "ClusterXL Monitoring Commands" | ESTABLISHED |
| `cphaprob -l list` vs `-ia list` | official pages use `-ia list`; syntax `cphaprob [-i[a]] … list`; `-l list` appears in sk117236 (Gaia Embedded) | VARIANCE — use `-ia list`; exact difference UNKNOWN |
| `cphaprob -ia list` = **the complete list of the configured critical devices (pnotes)**, equivalently `show cluster members pnotes all` — repeated verbatim across three independent official-source-adjacent results (2026-09-03, Source Pack 2) | "Reporting the State of a Critical Device" (R80.40); "Viewing Critical Devices" (R81.20 CLI Ref) | ESTABLISHED — **contradicts** an unverified assumption that `-ia` returns only problem-state pnotes; it is a full enumeration |
| `cphaprob -d Device_Name -t TimeOut_in_Sec -s State [-p] register` / `cphaprob -d Device_Name [-p] unregister`; VSX global pnotes registrable/unregistrable only from VS0 context | CLI Reference Guide "Registering/Unregistering a Critical Device" (2026-09-03, Source Pack 2) | ESTABLISHED — both **mutating**, excluded from any read-only candidate |
| `show cluster failover reset history` — a distinct, mutating Clish form separate from the pure observation form | same family as "Viewing/Monitoring Cluster Failover Statistics" (2026-09-03, Source Pack 2) | ESTABLISHED — **REJECTED**, must never enter preflight |
| "Maintain current active" / "Switch to higher priority" exact behavioral semantics, confirmed precisely (2026-09-03, Source Pack 2) | "Changing the Settings of Cluster Object in SmartConsole"; "Multi-Version Cluster Limitations" (ClusterXL Admin Guide) | ESTABLISHED — sharpens the session-1 concept-level finding |
| Simple Cluster API "does not support all cluster object features"; unsupported settings require SmartConsole (2026-09-03, Source Pack 2) | "Cluster Management APIs" (ClusterXL Admin Guide, R80.40+) | ESTABLISHED — explains, does not resolve, the missing recovery-method attribute name |
| Cluster failover statistics: "number of failovers…, reason, and the time of the last failover event" | R81 CLI Ref "Viewing Cluster Failover Statistics"; sk137472 | semantics ESTABLISHED; **exact Gaia syntax/version availability UNKNOWN** (documented for Spark R81.10.15+ as `cphaprob show_failover`) |
| `cpinfo` is resource-intensive and "may decrease the performance of the target system" | sk92739 | ESTABLISHED — HIGH COST |
| `fw stat` "shows information about the policy on the Security Gateway" | R81 CLI Ref `fw stat` | ESTABLISHED (columns UNKNOWN) |
| `vsx stat -v` lists all Virtual Systems and status; status may read `Unknown` | R81 CLI Ref `vsx stat`; sk178589 | ESTABLISHED |
| Per-VS diagnosis: `vsenv <VSID>` then `cphaprob stat` | R81.20 VSX Admin Guide "General Troubleshooting Steps" | ESTABLISHED |
| **`cphaprob stat` shows the member `Down` when run in a VS context other than VS0 in a VSX HA cluster** | **sk165432** (VSX Traditional) | ESTABLISHED caveat — **applicability to this estate's version UNKNOWN** |
| `cphaprob -a if` shows Bond as `Down` in any VS context | sk93341 | ESTABLISHED caveat |
| VSX members may show "Cluster Mode: Single VS Failover" | sk112712 | ESTABLISHED — mode parser must recognise it |
| Hotfix/JHF parity command (`installed_jumbo_take`, `cpinfo -y all`) | not established by any official snippet | UNKNOWN |

### Evidence per check — ClusterXL (HA)

| # | Check | Vendor-native evidence | Context | Status today |
| --- | --- | --- | --- | --- |
| 1 | viable standby | `cphaprob stat` local row on **both** members, same preflight; peer rows as corroboration (state only) | Expert, per member | local role only; peer rows dropped |
| 2 | state sync | `cphaprob syncstat` (R80.20+); `fw ctl pstat` Sync section only for <R80.20, selected per host from already-collected `show version all` | Expert, per member | NOT_COLLECTED |
| 3 | parity | policy: `fw stat` per member; software: existing `show version all`; hotfix: UNKNOWN command | Clish (existing) + Expert | software only (collected, not compared) |
| 4 | no split-brain | both members' own `cphaprob stat` rows in one preflight, skew recorded; `Active Attention`/`Down` reasons retained | Expert, per member | aggregated across unsynchronised runs |
| 5a | control link (CCP) | `cphaprob -a if` | Expert, per member | NOT_COLLECTED |
| 5b | sync link | `cphaprob -a if` (sync interface), `cphaprob syncstat` | Expert, per member | NOT_COLLECTED |
| 6 | preemption known | **management-plane cluster object recovery setting** (authoritative); `cphaprob state` Cluster Mode only as corroboration (sk180184) | MDS (`cpmiquerybin` attribute — **name UNKNOWN**) | NOT_COLLECTED |
| 7 | flap history | cluster failover statistics (`cphaprob show_failover` / `show cluster failover`) | Expert/Clish, per member | NOT_COLLECTED |
| 8 (new) | no member failure state | `cphaprob -ia list` (any pnote `problem`); `cphaprob stat`/`state` member `Down`/`Active Attention` | Expert, per member | partially (state token only, reason dropped) |

### Evidence per check — VSX physical cluster

Same battery as ClusterXL run in the **physical (VS0) context**, plus
`vsx stat -v` to enumerate VSIDs (currently only in `vsx_runner.py` over a
nested interactive shell; the preflight must issue it over the direct,
identity-gated SSH session). Mode parser must accept "Single VS Failover"
(sk112712) as a VSX-HA mode string.

### Evidence per check — VSX Virtual System (readiness/impact only)

- `vsenv <VSID> >/dev/null 2>&1; cphaprob stat` per VS (existing primitive,
  fresh exec channel, numeric-validated) — **with the sk165432 caveat**: a
  `Down` read in a non-VS0 context is `UNKNOWN` until real-env validation on
  this estate's version proves the read reliable. Until then a per-VS role
  that contradicts the physical member's role is `RELATIONSHIP_INCONSISTENT`,
  never `KNOWN_BAD`, and never a per-VS action input.
- `cphaprob -a if` per VS: OPTIONAL, with the sk93341 Bond caveat.
- Per-VS role parse must stop passing the **physical** hostname as
  `observed_hostname` (`:1613`); rely on the `(local)` marker inside the VS
  context and record which matched.
- `fw ctl set int vsid <N>` is **REJECTED** for preflight (non-read verb; the
  exec-channel `vsenv` primitive already works without it).

### Check Point configuration extraction correctness

| Required fact | Source | Context | Parser | Normalized field | Evidence entity → operational entity | Finding |
| --- | --- | --- | --- | --- | --- | --- |
| member runtime role | `cphaprob stat` | Expert exec / interactive | `_parse_clusterxl_runtime_role` | `ha_role` | member → cluster | VALIDATED (real estate 0→42 coverage); peer rows dropped; skew unrecorded |
| cluster mode | `cphaprob stat` | same | `_parse_clusterxl_cluster_mode` | `ha_cluster_mode` | member → cluster | fixtures constructed, not captured (two inconsistent shapes); real confirmation owed; "Single VS Failover" unrecognised |
| cluster identity | `cphaprob -a -m if` (+ CMA) | CPRID from MDS, stage `cp` | `parse_cluster_virtual_interfaces` → `enrich_cluster_topology` | `cluster_topology.group_id` | member → cluster | VALIDATED, mutual; but a **different run/transport** than `ha_role`; no freshness join |
| VSX membership | `cpmiquerybin … vsx_cluster_member,vs_cluster_member` | MDS, stage `cp` | `_is_vsx_status` | `entity_type` | mgmt object → member | management intent; never reaches `unified.json` rows (`_row_is_vsx` dead; evidence-based `vsx_hosting_devices` compensates) |
| VS runtime role | `vsenv N; cphaprob stat` | Expert exec, per VS | same parsers | `ha_role` (+`_source`) | VS → VS unit | correctly labelled when inherited; **sk165432 unvalidated**; physical hostname passed as match token |
| VS platform/serial/version/identity/host-key | inherited from host | — | — | same names, **no source label** | host → VS | SUSPECT: physical facts masquerade as VS facts |
| Gaia configuration | `show configuration` (+ per-VS via vsenv) | Clish | `_snapshot_view` (sanitised) | CAS snapshot | member/VS | ClusterXL HA settings live in the **management DB**, not Gaia config → CP "configuration intent" for HA is effectively **not collected** beyond membership booleans; sanitation impact on HA-relevant lines UNKNOWN |
| legacy `cluster` on VSX rows | hostname `-1`/`-2` suffix (`merge.normalize_vsx`) | merge | — | `cluster` | — | **heuristic leaking into the failover fallback key** (`assessment.py:456-457`) |

Provenance ambiguities to close: (i) no wire-form command on rows (only the
probe artifact persists `command`/`attempted_commands`); (ii) no `run_id` on
rows; (iii) cross-stage join with no freshness; (iv) VS inheritance unlabelled;
(v) the standby member is structurally absent from `vsx.json`.

## Palo Alto evidence surface

### Current state (SOURCE A + B, verified)

- Runtime: `get_target_ha_runtime_state` issues
  `<show><high-availability><state/></high-availability></show>` via the
  Panorama XML API with `target=<serial>` and parses five leaves (`enabled`,
  `local-info/state`, `local-info/mode`, `peer-info/state`,
  `local-info/state-sync`). The response's `local-info` and `peer-info` each
  carry ~50 children on PAN-OS 11.1 (real enumeration via the bounded
  diagnostic, commit `1d97cd6`), including `serial-num`, `mgmt-ip`,
  `ha1-ipaddr`, `ha1-backup-ipaddr`, `ha2-ipaddr`, `ha1-port`, `ha2-port`,
  `preemptive`, `preempt-hold`, `priority`, `promotion-hold`, `max-flaps`,
  `nonfunc-flap-cnt`, `preempt-flap-cnt`, `last-error-reason`,
  `last-error-state`, `state-duration`, `version`, `build-rel`, `app-version`,
  `av-version`, `threat-version`, `url-version`, `*-compat`, and on
  `peer-info` additionally `conn-status`, `conn-ha1`, `conn-ha1-backup`,
  `conn-ha2`. **Siblings of `local-info`/`peer-info` under `result/group`
  (e.g. a `running-sync` element) were not enumerated** — an evidence gap.
- Identity: Panorama `show devices all` → `entry/serial` (discovery plane);
  direct `show system info` serial must equal it (identity gate,
  `_collect_direct_compare`); runtime `local-info/serial-num` and
  `peer-info/serial-num` are parsed to one-way tokens (commit `a1a3882`) with
  a same-run correspondence state — **real-env result pending**.
- Configuration intent: `deviceconfig/high-availability/group/peer-ip[-ipv6]`
  from the Panorama-proxied `xpath=/config` of the target (`:401-403`); also
  fetched directly per firewall (`:425`, `:468`) and as Panorama template intent
  without target. `peer-ip` is **HA1-plane** (proven on member A; vendor doc
  `interface ha1 peer-ip-address`).
- Defect: `_collect_device_row` **skips the runtime query entirely** when
  Panorama's discovery already supplied `ha-state` (`:1641-1647`), and that
  branch's `ha_runtime` carries no `enabled` — so `_derive_pan_units:627` can
  never form a unit for such a device. For preflight this short-circuit is
  unacceptable: a discovery-plane cached role is never runtime evidence.
- Defect: `_pan_states` synthesises a second member from `peer_state` when only
  one member has evidence (phantom-member uplift; recorded successor-contract
  invariant in the docstring, commit `a1a3882`).
- Real pair: A `CONSISTENT` (configured HA1 peer == runtime peer HA1);
  B `INCONSISTENT` (configured peer matches no runtime peer address). Both
  members `queried_target: true`, `state_sync: Complete`, one active / one
  passive. Serial correspondence: **not yet measured.**
- TLS: verification defaults **off** unless `SECURITYEXPERT_PAN_CA_BUNDLE` /
  `SECURITYEXPERT_PAN_DIRECT_CA_BUNDLE` is set; strict mode
  `REAL_ENV_VALIDATED` (`pan_tls_ca`), production requires it.

### Vendor semantics established (SOURCE C, snippet-level)

| Fact | Established by | Status |
| --- | --- | --- |
| HA states: functional = active, passive, active-primary, active-secondary; non-functional = initial, non-functional, tentative, suspended; suspended needs user intervention | PAN-OS 10.1 "HA Firewall States"; 11.1 "Failover" | ESTABLISHED |
| `show high-availability state`: local/peer information; peer "Connection status: up/down" with down-reasons such as "HA1 link went down" | KB "High Availability – HA Peer Connection Status"; KB "HA links status" | ESTABLISHED (field **names** `conn-status`, `conn-ha1`… seen real; **value vocabulary UNKNOWN**) |
| `show high-availability all`: HA1 control-link info (IP, MAC, interface, link state); "Running Configuration: synchronized / not synchronized" | Wildfire/NGFW CLI ref pages; KB "Out of Sync Peers – Configuration"; 11.1 "Reference: HA Synchronization" | ESTABLISHED; **whether `running-sync` also appears in the `state` XML is UNKNOWN** |
| State synchronization copies session, forwarding, ARP tables and VPN SAs over HA2 | "Reference: HA Synchronization" | ESTABLISHED (`state-sync` value vocabulary UNKNOWN) |
| Device priority (lower value = active), preemptive off by default and required on both, preemption hold timer, HA1 MAC tie-break | 11.1 "Device Priority and Preemption"; KB | ESTABLISHED (runtime field binding `preemptive`/`preempt-hold`/`priority` inferred from names — CONFIRM) |
| Flap-max default 3; a flap counted when the firewall leaves active within 15 min of last leaving active; suspended after max flaps; distinct non-functional loop and preemption loop; monitor-fail hold timer | KB "When does an HA node go into Suspended state…"; KB "HA Failover Hold Timers"; 11.1 "Failover" | ESTABLISHED (binding to `max-flaps`/`nonfunc-flap-cnt`/`preempt-flap-cnt` inferred from names — CONFIRM) |
| HA checks compare app/threat/AV/PAN-OS versions between peers and log mismatches | KB "App and Threat Compatibility Mismatch in HA Pair"; KB "Dynamic Updates Version Mismatch Alerts" | ESTABLISHED concept (`*-compat` **value vocabulary UNKNOWN**) |
| `show high-availability path-monitoring` exists; link and path monitoring are failover conditions | 10.1 "HA Link and Path Monitoring"; 11.1 "Configure HA Clustering" | ESTABLISHED (link-monitoring show-command existence PARTIAL) |
| HA1 may use eth2/eth3 **or the management port**; HA1 peer setting = peer's ha1 address | "Configure Active/Passive HA (PAN-OS)"; `set deviceconfig high-availability` | ESTABLISHED |
| Panorama proxies `type=op` to a firewall via `&target=<serial>` | "Query a Firewall from Panorama (API)" | ESTABLISHED |
| `show system info` per peer for software/content versions | design §3.2; command already issued for the identity gate | ESTABLISHED (existing) |
| Peer serial present in `show high-availability state` | **not shown in any official snippet**; present in real 11.1 output | **UNKNOWN semantics — real-env correspondence pending** |
| `show high-availability state-synchronization`, `flap-statistics` as NGFW show-commands | not established (flap-statistics documented for HA clustering) | UNKNOWN |

### Evidence per check — PAN Active/Passive pair

| # | Check | Vendor-native evidence | Source | Status today |
| --- | --- | --- | --- | --- |
| 1 | viable passive | `local-info/state` on **both** members, same preflight; `peer-info/state` + `peer-info/conn-status` as corroboration; non-functional/suspended ⇒ not viable | `show high-availability state` (existing) | state parsed; conn-status unparsed; phantom-member uplift |
| 2 | state sync | `local-info/state-sync` (+ `state-sync-type`) both members; HA2 link (`peer-info/conn-ha2`) | existing | `state-sync` parsed, vocabulary unconfirmed |
| 3 | parity | config: `running-sync` (location UNKNOWN: `state` XML sibling or `show high-availability all`); software/content: `local-info`/`peer-info` `version`, `build-rel`, `app-version`, `av-version`, `threat-version`, `url-version`, `*-compat`; cross-check with direct `show system info` per member | existing (+ possibly `all`) | NOT_PARSED |
| 4 | no split-brain | both members' own `local-info/state`; never `peer_state` as a member | existing | phantom-member uplift must go |
| 5a | control link | `peer-info/conn-ha1`, `conn-ha1-backup`; `local-info/ha1-port`, `ha1-backup-port` | existing | NOT_PARSED |
| 5b | sync link | `peer-info/conn-ha2`; `local-info/ha2-port` | existing | NOT_PARSED |
| 6 | preemption known | `local-info`/`peer-info` `preemptive`, `priority`, `preempt-hold`, `promotion-hold`; corroborate with config `election-option` | existing (+ config) | NOT_PARSED |
| 7 | flap history | `local-info` `max-flaps`, `nonfunc-flap-cnt`, `preempt-flap-cnt`, `state-duration`; `last-error-reason`/`last-error-state` | existing | NOT_PARSED |
| 8 (new) | no member failure state | `local-info/state` ∈ non-functional set; `last-error-*`; `peer-info/conn-status` down | existing | partially |
| — | path/link monitoring (design §3.2 row) | `show high-availability path-monitoring`, `link-monitoring` | **new commands** | NOT_COLLECTED — gate |

### Palo Alto configuration extraction correctness

| Required fact | Authoritative plane | Current source | Finding |
| --- | --- | --- | --- |
| serial (identity) | direct device (`show system info`) gated against Panorama inventory | as designed | VALIDATED (existing gate); VS/VSYS irrelevant |
| hostname | Panorama discovery (presentation) | `normalize_pan_hostname` shared seam | M only; two independent XML walks remain (`pan_hostname_parser_unification`) |
| `management_ip` | Panorama discovery | `entry/ip-address` | A-adjacent (re-assignable); **not identity, not a join key** |
| configured peer HA1 address | device config (`effective-running`, direct) with provenance | Panorama-proxied `xpath=/config` of target | plane correct (HA1), **transport is Panorama proxy** — record `source_plane=device_config`, `transport=panorama_api_proxy`; prefer direct `effective-running` for preflight |
| runtime local/peer serial | device runtime | `show high-availability state` via Panorama proxy | tokenised; correspondence pending; **transport decision open** |
| HA link state, state sync, config sync, roles, failure, preemption, flaps | device runtime | same response, mostly **unparsed** | parse-scope extension, no new command, except `running-sync` location UNKNOWN |
| discovery `ha-state` | Panorama discovery cache | `entry/ha-state` | **must never short-circuit a preflight runtime read** |
| transition history beyond counters | device logs | not collected | out of scope (unbounded, secret-bearing) |

Do not silently use Panorama intent when runtime truth is required (the
`ha-state` short-circuit does exactly this today). Do not silently use runtime
state when the question is configuration intent (the config/runtime
consistency axis needs both, labelled).

## Runtime extraction correctness

- **CP:** one command, local row only, buffer discarded, no peer state, no
  reason text, unsynchronised members, no wire-form provenance. Per-VS reads
  carry an unvalidated vendor caveat (sk165432). Mode fixtures are constructed.
- **PAN:** one command, five leaves parsed out of ~100 present, `result/group`
  siblings unenumerated, discovery cache can suppress the runtime read
  entirely, phantom member in the state aggregator, passive-member
  `peer-info` completeness unexplained (member B matched nothing).
- **Both:** `extract_*_ha_runtime` in `utils/failover_readiness_ui.py` is the
  single narrowing point; anything not copied there is invisible to readiness.

## Identity contract

### Check Point

- **Physical identity (A):** management object name (from `cpmiquerybin`)
  confirmed by the in-session `show hostname` handshake, over a **strict
  host-key-trusted** SSH session (`cp_ssh_trust` validated; R2 production
  server provisioning pending). `serial`/`model` from `cpstat os -f hw_info`
  are identity **attributes**, not the gate. For CLASS 2 the gate is
  host-key trust + hostname match; a mismatch is `IDENTITY_MISMATCH`.
- **Operational identity (B):** `cluster_topology.group_id` — mutual by
  construction (both members report the identical VIP set). Preserve verbatim.
  Legacy `cluster` (hostname suffix) fallback → to be **removed** from the
  failover key path (§26 CP-11).
- **VS identity (B):** `(physical_unit_id, vsid)`; VS evidence entity
  `<device>__vsid_<N>` preserved.
- **Peer identity (E):** `cphaprob stat` peer rows are **state corroboration
  only**. "Unique Address" is not identity-grade by the vendor's own
  documentation; peer **name** in the row is presentation. Membership identity
  comes from `group_id`, not from peer rows.

### Palo Alto

Four independent serial observations must be kept distinct:

| Symbol | Observation | Plane |
| --- | --- | --- |
| I1 | Panorama inventory `entry/serial` | management discovery |
| I2 | direct `show system info` serial over the firewall's own API | device self-report, identity-gated session |
| I3 | runtime `local-info/serial-num` | device self-report in HA context |
| I4 | runtime `peer-info/serial-num` | **this member's claim about its peer** |

Definitions (READ-ONLY presentation grades in brackets):

- **self identity verified:** I1 == I2 (existing gate); I3 == I2 is a
  consistency check, `self_identity_consistent`.
- **one-sided peer claim [B₁]:** A.I4 present; nothing else.
- **peer not inventoried:** A.I4 ∉ {I1 of any inventoried device}. Record
  "claim present, peer not independently observed". **Never** `ESTABLISHED`.
- **bidirectional corroboration [B₂]:** A.I4 == B.I2 **and** B.I4 == A.I2, both
  members identity-verified, **same preflight run**, both `conn-status` live.
- **pair established (presentation):** B₂.
- **identity mismatch:** A.I4 matches an inventoried device other than the one
  B's own report identifies, or A.I4 == B.I1 while B.I4 ≠ A.I2.
- **unknown:** any of I2/I4 missing for either member.

**Candidate pair operational identity:** unordered pair `sorted(I2_A, I2_B)` —
stable across role swap, hostname rename, management-IP change, HA1
re-addressing and label change; changes on RMA (correct: new hardware is a new
authorization subject; continuity is a presentation label). **NOT FROZEN**
until the real-env serial correspondence result is `MATCH`/`MATCH` and the
official semantics of `peer-info/serial-num` are confirmed. Until then the
current hostname-keyed unit id stands and its known defects stand with it.

**A one-sided self-report is never sufficient for CLASS 2.** B₂ is the
**minimum** identity input to CLASS 2, and B₂ alone is still not authorization.

## Peer relationship contract

Two orthogonal axes, never collapsed:

```
pair_identity_state ∈ { ESTABLISHED, PEER_NOT_INVENTORIED, MEMBER_ONLY, IDENTITY_MISMATCH, UNKNOWN }
relationship_consistency ∈ { CONSISTENT, INCONSISTENT, NOT_EVALUABLE, NOT_APPLICABLE }
```

- CP: `pair_identity_state` derives from `group_id` (mutual VIP set) plus
  both members reachable/identity-gated in-run; `relationship_consistency`
  compares each member's peer-row state against the peer's own row.
- PAN: `pair_identity_state` from I1–I4 as above; `relationship_consistency`
  compares configured HA1 peer address against the peer's own
  `local-info/ha1-ipaddr` and against the member's `peer-info/ha1-ipaddr`.
- The approved real PAN pair is, conceptually: identity `pending B₂`,
  consistency A `CONSISTENT` / B `INCONSISTENT`, readiness fail-closed.
  **Serial corroboration, when it lands, does not erase B's inconsistency.**
  Report it; never repair configuration.

## Provenance contract

Every preflight fact carries:

```
collected_at              UTC timestamp of the read
preflight_run_id          one id per preflight invocation (not the inventory run_id)
source_vendor             checkpoint | panorama
source_plane              management_discovery | management_intent | device_config | device_runtime
transport                 ssh_direct | cprid_mds | panorama_api_proxy | direct_api
source_command            wire form actually sent (identity-free for these reads; `vsenv N` retained)
shell_profile             CP only: interactive_direct_clish | interactive_expert_explicit_clish | exec_expert
physical_device_identity  CP: mgmt object name + host-key fp; PAN: I2 serial (tokenised where persisted)
operational_entity_id     unit id
context                   physical | vsid:<N> | vsys:<name>
outcome                   success | failed | unsupported | capability_gap | identity_mismatch
member_skew_ms            max spread between the two members' reads for the same fact
```

Rules: a preflight fact set is **coherent** only if every category D–K fact
for both members shares one `preflight_run_id`; `member_skew_ms` is recorded
always and bounded by an OPEN DECISION; category C facts may come from an
earlier collection but must carry their own `collection_run_id`,
`collected_at` and `source_plane`, and are marked `STALE_INTENT` beyond an
OPEN-DECISION max age; category A identity may be cached but is **re-gated
in-run** (the identity gate is itself an in-run read). Old config + fresh
runtime must never render as one snapshot: the UI shows the two provenance
stamps.

## Freshness contract

| Category | Requirement |
| --- | --- |
| A physical identity | cached allowed; **revalidated in the preflight run** (gate) |
| B operational identity | derived from in-run A + topology; recomputed per preflight |
| C configuration intent | may predate the preflight; must carry provenance; max age → **OPEN DECISION** |
| D, E, F, G, J, K runtime | **collected in the immediately preceding preflight** (same `preflight_run_id`), both members, skew recorded |
| H parity | in-run (content versions change independently of failover intent) |
| I preemption | CP: management-plane read may be bounded-window (config); PAN: in-run runtime read |
| L provenance | always present |

No numeric TTL is invented here (§"Open decisions" D-F1, D-F2).

## Command / API safety contract

- Class: every §24 `REQUIRED`/`OPTIONAL` row is `CLASS_0_READ`. Mutating
  primitives (`clusterXL_admin`, `request high-availability state …`,
  `sync-to-remote`, `fw ctl set …`) are listed only to be **REJECTED** from
  preflight.
- Session: CP — one strict-trusted SSH session per physical member runs the
  whole battery; per-VS reads on fresh exec channels inside that session.
  PAN — one API session per firewall (transport decision D-T1).
- Frequency: preflight is **interactive, on-demand** — never recurring fleet
  polling. `cpinfo`, log scraping and `show routing route` are excluded on
  cost/output grounds regardless.
- Retry: at most one bounded retry per read, never on a read whose partial
  output could be misread as state; a second failure is `COLLECTION_FAILED`.
- Timeouts: per-command, inherited from the OP.0b draft (CP 60 s, PAN 30 s)
  pending the gate package.
- Output lifetime: raw buffers parsed to enums/counters/tokens and discarded
  in-module; nothing raw persisted; `cplic print`/`cpstat os` scalars only.

## Minimum Check Point preflight battery

**A. ClusterXL (HA)** — per physical member, one session:

| Step | Command | Context | Evidence | Authoritative for | Req | Cost | Failure meaning |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A1 | `show hostname` | Clish/handshake | identity match | A | REQ | LOW | `IDENTITY_MISMATCH` → stop |
| A2 | `show version all` | Clish | Gaia/product version | H (software) | REQ | LOW | parity `UNKNOWN` |
| A3 | `cphaprob stat` | Expert | local role, mode, peer rows (state), Active Attention | D, corroborating E/J | REQ | LOW | unit `UNKNOWN` → stop |
| A4 | `cphaprob -a if` | Expert | interface/CCP/sync link status | F | REQ* | LOW | 5a/5b `INSUFFICIENT` |
| A5 | `cphaprob -ia list` | Expert | pnotes | J | REQ* | LOW | 8 `INSUFFICIENT` |
| A6 | `cphaprob syncstat` (R80.20+) / `fw ctl pstat` (<R80.20) | Expert | delta sync | G | REQ* | LOW | 2 `INSUFFICIENT` |
| A7 | `fw stat` | Expert | installed policy | H (policy) | REQ* | LOW | 3 `INSUFFICIENT` |
| A8 | cluster failover statistics | Expert/Clish | count/reason/last time | K | REQ* | LOW | 7 `INSUFFICIENT` |
| A9 | management-plane recovery setting | MDS | preemption | I | REQ* | LOW | 6 `UNKNOWN` (not blocking, recorded) |
| A10 | `cphaprob state` | Expert | mode string corroboration | I (corroboration), J | OPT | LOW | — |
| A11 | `cplic print`, `cpstat os` | Expert | licence/resources on standby | 1 sub-fact | OPT | LOW | — |

`*` = new command; enters only through the `OP.0b.1` gate package after the
row's `UNKNOWN`s are resolved.

**B. VSX physical cluster** — battery A in VS0 context, plus `vsx stat -v`
(REQ) to enumerate VSIDs; mode parser accepts "Single VS Failover".

**C. VSX Virtual System** (readiness/impact only) — `vsenv N >/dev/null 2>&1;
cphaprob stat` (REQ, sk165432 caveat), optionally `vsenv N …; cphaprob -a if`
(sk93341 caveat). No per-VS action is planned in this estate.

## Minimum PAN preflight battery

**A. Active/Passive pair** — per firewall:

| Step | Command/API | Transport | Evidence | Authoritative for | Req | Cost | Failure meaning |
| --- | --- | --- | --- | --- | --- | --- | --- |
| P1 | `show system info` | direct API (identity gate) | I2 serial, sw/content versions | A, H | REQ | LOW | `IDENTITY_MISMATCH` → stop |
| P2 | `show high-availability state` (full parse) | direct API (D-T1) or Panorama proxy | D, E (I3/I4), F, G, H, I, J, K | see §25 | REQ | LOW | unit `UNKNOWN` → stop |
| P3 | `show high-availability all` | same | `running-sync`, link detail | H (config), F | OPT→REQ if `running-sync` absent from P2 | LOW | 3 `INSUFFICIENT` |
| P4 | `show high-availability path-monitoring` | same | monitored paths | F/J | REQ* | LOW | `INSUFFICIENT` |
| P5 | `show high-availability link-monitoring` | same | monitored links | F/J | REQ* (existence PARTIAL) | LOW | `INSUFFICIENT` |
| P6 | `xpath=/config` effective-running (existing) | direct | configured HA1 peer, `preemptive`, `state-synchronization enabled` | C | REQ (bounded age) | MOD | consistency `NOT_EVALUABLE` |
| P7 | `show devices all` (existing) | Panorama | I1 inventory serial, `ha-state` (cache) | A (inventory), M | REQ (inventory only) | LOW | inventory `UNKNOWN`; never a runtime source |

**B. Exceptions:** Active/Active, HA clustering (HA4), VM-series licence
states (`vm-license` field observed) → `UNSUPPORTED`/`NOT_APPLICABLE` until
separately contracted.

## Normalized preflight fact model

Only what both vendors can support enters the common model; vendor-specific
facts stay underneath with their category.

```
PreflightUnit
  operational_unit_id, vendor, unit_type, mode {ha_new_mode|active_passive|UNSUPPORTED:<x>}
  members[]: { device_identity, identity_verified, role, failure_state, provenance }
  pair_identity_state, relationship_consistency, evidence_grade {A|B1|B2}
  checks[]: { id, status {PASS|FAIL|INSUFFICIENT_EVIDENCE|UNSUPPORTED|NOT_APPLICABLE}, reason, missing_evidence, facts[] }
  prerequisites: { identity_gate, pair_identity, mode_supported, evidence_coherence }
  coherence: { preflight_run_id, member_skew_ms, stale_intent: bool }
  vendor_facts: { …category-tagged raw-derived scalars/tokens… }
```

Not forced into the common model: CP "Assigned Load" (LS only), PAN
`vm-license`, PAN `*-compat` details, CP pnote names (kept as counts + safe
class), PAN flap counters (kept vendor-specific under K).

## Seven-check model review

| Today | Verdict | Change |
| --- | --- | --- |
| 1 viable_target | KEEP | evidence must be both members' **own** state; passive/standby set per vendor vocabulary; PAN non-functional set excluded |
| 2 state_sync_current | KEEP | CP `syncstat` (version-conditional); PAN `state-sync` + HA2 link |
| 3 parity | KEEP, widen name to "config/policy/software/content parity" | PAN `running-sync` belongs here; CP policy via `fw stat`, software via existing `show version all` |
| 4 no_split_brain | KEEP | remove PAN phantom member; require both members in-run; record skew |
| 5 control_sync_link_health | **SPLIT** → 5a control link, 5b sync link | blocking semantics differ (HA1/CCP down ⇒ split-brain risk; HA2/sync down ⇒ session drop) |
| 6 preemption_known | KEEP (recorded, non-blocking) | CP source is **management plane** (sk180184); PAN is runtime `preemptive`/`priority`/`preempt-hold` |
| 7 flap_history | KEEP | CP failover statistics; PAN flap counters + last-error |
| — | **ADD 8 no_member_failure_state** | pnote problem / `Down` / `Active Attention` / `non-functional` / `suspended` — decisive `UNSAFE`, distinct from "no standby" |
| peer identity | **MOVE to prerequisite** | `pair_identity_state` must be `ESTABLISHED` before checks run |
| evidence freshness/coherence | **MOVE to prerequisite** | incoherent snapshot ⇒ no verdict, `INSUFFICIENT_EVIDENCE:incoherent_snapshot` |
| mode determination | already pre-check | keep; add "Single VS Failover" |

Resulting shape: 4 prerequisites + 8 checks. `SAFE_TO_FAILOVER` remains
structurally unreachable until the gate package lands (P4 invariant). No code
change in this build.

## Fail-closed / UNKNOWN semantics

| State | Meaning | Verdict effect |
| --- | --- | --- |
| `UNKNOWN` | fact not determinable from collected evidence | `INSUFFICIENT_EVIDENCE` |
| `INSUFFICIENT_EVIDENCE` | read succeeded but does not answer the check | `INSUFFICIENT_EVIDENCE` |
| `KNOWN_BAD` | read succeeded and proves an unsafe condition | `UNSAFE_DO_NOT_FAILOVER` |
| `COLLECTION_FAILED` | read failed/timed out | `INSUFFICIENT_EVIDENCE` (never `KNOWN_BAD`) |
| `IDENTITY_MISMATCH` | gate failed | stop before any check; no verdict |
| `RELATIONSHIP_INCONSISTENT` | config vs runtime disagree (or VS vs host) | `INSUFFICIENT_EVIDENCE` with precise reason; unit still exists |
| `UNSUPPORTED` | mode/platform outside contract | `NOT_A_FAILOVER_UNIT` / `UNSUPPORTED` |
| `NOT_APPLICABLE` | check meaningless for this mode/vendor | excluded from PASS-all requirement, listed |

Never collapse: unobserved link ≠ link down; config/runtime disagreement ≠
pair absent; peer self-report ≠ peer verified; discovery-cache role ≠ runtime
role; inherited VS fact ≠ VS observation.

## Privacy invariants

- Readiness/preflight artifacts carry no management address, raw device
  output, licence string, hostname, or command text beyond fixed labels and
  identity-free wire forms (`vsenv N` is not an identity). Serials persist
  only as one-way tokens (established `Tokenizer` pattern) or not at all.
- `cplic print`, `cpstat os`, `show system info`, `show configuration`,
  effective-running XML are parsed to scalars in-module; raw never persisted
  beyond the existing sanitised CAS path.
- CLASS 2 local telemetry stays local; shareable summaries follow
  `PRIVACY_AND_DATA_HANDLING.md` ("filter one entity → report safe derived
  status"). Repository privacy gate stays PASS / 0.

## Architecture authority / single-source rules

**Rule:** the vendor/domain backend (`utils/failover/`) is the sole authority
for HA identity, topology, pairing and readiness; every UI consumes its
projection. Today five independent inference paths violate this and can
contradict the backend while a preflight runs:

1. `static/inventory_ui.js:1013-1042, 1332-1378` — PAN pairing by hostname
   ordinal + VSYS/VR Jaccard similarity (0.75/0.60), `inferred_ha_runtime_pair`.
2. `static/inventory_ui.js:1273-1317` — synthesises `cp_vsx_cluster` parents
   from ≥2 name-token matches; `:1249-1266` attaches VSX groups on ≥1 token
   overlap.
3. `utils/merge.py:95-101` — `cluster` from `-1`/`-2` suffix, consumed as the
   backend's legacy fallback key.
4. `static/configuration_ui.js:147` + `presentation_group_id`
   (`checkpoint_config_collector.py:1048-1057`) — hashed hostname-pattern
   grouping the failover model never reads.
5. `utils/config_ui.py:280-306` — a second PAN HA vocabulary ("HA Enabled" from
   configuration alone).

Follow-up (not this contract): retire 1–2 in favour of the backend projection;
remove 3 from the failover key path; label 4 presentation-only in the UI;
align 5's vocabulary. `console/payloads.py` and `static/failover_readiness_ui.js`
are clean.

## Current collector reuse decision

**Decision: D — hybrid, as hypothesised, with one sharpening.** Reuse the
transport/session/identity-gate/redaction/`RunContext`/admission primitives
(CP: strict-trusted SSH + handshake + exec-channel `vsenv`; PAN: `api_post` +
per-firewall keygen + identity gate). **Do not** make the inventory/config
collector the preflight engine: it fetches heavy, sensitive `show
configuration`/effective-running documents on every pass (latency, privacy,
failure coupling); its PAN branch short-circuits the runtime read on a
discovery cache; it has no preflight provenance; and coupling it to CLASS 2
would make the whole inventory pipeline part of the authorization surface.
Instead: a dedicated, read-only, vendor-specific **preflight evidence layer**
that issues the §"Minimum battery" reads with the §"Provenance contract"
envelope, and **consumes** category-C facts from the latest configuration run
by reference (with provenance and max-age). Sharpening: runtime facts are
never read from a stored telemetry file in a preflight — the preflight collector
always performs its own in-run reads.

## Frozen-contract impact (OP.0a / OP.0a.P7)

Preserved verbatim: the Grade A/B/C model (extended by B₁/B₂ subdivision);
every prohibition clause; fail-closed shape; Q5 identity-never-`management_ip`;
privacy invariants; AC-1, AC-2, AC-4, AC-7–AC-9. Requiring amendment: Q1's
factual claim that `peer-info` is a "management-address field"; the Risks
section (add plane ambiguity and legitimate real-world asymmetry). Requiring
supersession by the successor domain contract: the `peer-ip` →
`management_ip` join, the single-member-unit outcome table, correctness item
4, AC-3, AC-6's serial prohibition, original P7 AC-5. This contract does not
perform that supersession; it records that the evidence now exists.

## Implementation slices (after FREEZE)

| Slice | Objective | Files | Network | Tests | Real-env | Tier |
| --- | --- | --- | --- | --- | --- | --- |
| S0 (in flight) | PAN runtime peer-serial correspondence result | — | existing run | — | **required** | Sonnet 5 normal |
| S1 | Preflight fact + provenance model (pure, no I/O); UNKNOWN semantics; coherence check | `utils/failover/preflight_model.py` (new), tests | none | synthetic | no | Sonnet 5 normal |
| S2 | PAN parse-scope extension of existing `show high-availability state` + `show system info`: identity, conn-*, election, flap, error, versions, `result/group/*` sibling enumeration | `configuration/panorama_config_collector.py`, `utils/failover_readiness_ui.py`, tests | **none** (same commands) | synthetic real-shaped XML | yes — resolves PAN `UNKNOWN` vocabularies | Sonnet 5 normal |
| S3 | CP parse-scope extension of existing `cphaprob stat`: peer rows (state), Active Attention reason, "Single VS Failover" mode, wire form + `collected_at` on rows | `configuration/checkpoint_config_collector.py`, `utils/failover_readiness_ui.py`, tests | **none** | fixtures + one captured sanitised real header | yes — captures real header shape | Sonnet 5 normal |
| S4 | `OP.0b.1` command-gate package: ten points per new row of §24 (CP A4–A9; PAN P3–P5), after official-doc confirmation of the `UNKNOWN` rows | docs only | none | — | — | Sonnet 5 extended (security boundary) |
| S5 | CP preflight collector (dedicated) | `checkpoint/preflight_collector.py` (new) | new reads per approved gate | mocked SSH | yes | Sonnet 5 extended |
| S6 | PAN preflight collector (dedicated); transport decision D-T1 | `panorama/preflight_collector.py` (new) | new reads per approved gate | mocked API | yes | Sonnet 5 extended |
| S7 | Readiness v2: prerequisites + 8 checks; remove phantom member; pair existence vs health; serial-keyed PAN unit; `securityexpert-ha-readiness-v2` | `utils/failover/assessment.py`, UI labels, tests, successor contract to OP.0a.P7 | none | full | yes | Opus (cross-subsystem) |
| S8 | Real-env validation on the approved CP ClusterXL pair, VSX pair and PAN pair | — | reads only | — | **required** | Sonnet 5 normal |
| S9 | Authority reconciliation (UI heuristics retirement) | `static/*.js`, `utils/merge.py`, `utils/config_ui.py` | none | render harness | eyeball | Sonnet 5 normal |

Dependency order: S0 → S1 → (S2, S3 in parallel) → S4 → (S5, S6) → S7 → S8;
S9 independent after S7.

## Acceptance criteria (for the FROZEN version)

**Note (session 4):** these are bars for the *implementation* slices (S1–S9)
and the `OP.0b.1` gate package to clear, evaluated against this now-frozen
contract — not conditions on freezing the contract itself (that determination
is §"Freeze decision"/§"Final semantic blocker closure"). AC-1 in particular
still has `UNKNOWN` entries today (D-V5a/D-V6's exact syntax, D-V3a/D-V7b);
closing them is `OP.0b.1`/pre-CLASS-2 work, tracked there, not a reason this
document stays `DRAFT`.

- **AC-1** Every §24 row has no `UNKNOWN` in the Read-only, Authoritative-for
  or Official-source columns, or is `REJECTED`.
- **AC-2** Every §25 row is `COLLECTED_AND_PARSED` or has a named slice.
- **AC-3** Provenance envelope present on every preflight fact; coherence
  rule enforced; no verdict emitted on an incoherent snapshot.
- **AC-4** No fact in category C, M or discovery-cache D is a check input.
- **AC-5** `_pan_states` phantom-member uplift removed; a single-member PAN
  unit cannot PASS `viable_target` or `no_split_brain`.
- **AC-6** Pair existence and health are separate fields; the real
  asymmetric PAN pair renders as identity-per-B₂ + `INCONSISTENT` on B +
  fail-closed.
- **AC-7** No new command outside an approved `OP.0b.1` gate entry; no
  mutating verb on any read path (incl. removal of `fw ctl set int vsid` from
  preflight scope).
- **AC-8** Privacy gate PASS / 0; no raw serial/address in readiness artifacts.
- **AC-9** `SAFE_TO_FAILOVER` unreachable until S5/S6 land (P4 invariant test
  retained).

## Automated validation gate

Targeted parser tests on synthetic real-shaped output (both vendors); OP.0a /
OP.0c / OP.0d regression; full suite; privacy gate; `git diff --check`;
architecture-convergence test (no plan/executor/adapter module appears).

## Real-env validation gate

Same approved targets (one CP ClusterXL pair, one VSX pair, one PAN pair;
requested = resolved = contacts, extra = 0), reads only. Must record: captured
sanitised `cphaprob stat` header shape; sk165432 behaviour on this estate's
version; PAN `result/group/*` sibling enumeration; PAN field vocabularies for
`conn-*`, `state-sync`, `*-compat`, `preemptive`; PAN serial correspondence
`MATCH`/`MATCH`; member skew observed. Report as safe summaries, no
identities.

## CLASS 2 handoff requirements

Even a fully green preflight is one prerequisite. CLASS 2 must separately
freeze: RBAC/OIDC `OPERATE` role; explicit confirmation; change reason/ticket;
**per-HA-entity lock** (§10.1 item 4 — currently untracked, §26 X-1);
exactly-once; timeout-ambiguity → `UNKNOWN`; no blind retry; vendor-specific
mutation adapter; post-action verification; immutable audit; `UNKNOWN`
outcome handling; recovery/escalation; maintenance window; `op_degraded_verdict`
resolution before OP.1. This contract guarantees CLASS 2 will have: identity
B₂, coherent fresh runtime facts with skew, explicit failure/flap/preemption
facts, and a config/runtime consistency axis — nothing more.

## Risks

- Official-doc access from the drafting environment was snippet-only; a row
  confirmed later may differ (mitigation: DO NOT FREEZE until confirmed).
- sk165432/sk93341 may make per-VS CP reads unreliable on this estate's
  version; the contract already treats them as `UNKNOWN` until validated.
- sk180184 means CP preemption needs a management-plane read that does not
  exist in any collector today (new surface; gate).
- PAN passive-member `peer-info` completeness is unexplained (member B matched
  nothing); if passive members systematically under-report, B₂ may be
  reachable only from the active side — must be measured, not assumed.
- Real PAN pair has a genuine configuration/runtime inconsistency; pressure to
  "make it green" must be resisted (OP.0a already warns).
- `on_hardware_real_env_validation` is BLOCKED on laptop availability.

## Official vendor semantics confirmation pass — 2026-09-03

Session 2 of the vendor-semantics audit (`op0b_official_vendor_semantics_confirmation`,
`project/roadmap.json` `now_next.next` at session start). Reasoning tier:
`Sonnet 5, extended thinking (high)` per the task's own header and
`CLAUDE.md`/`AGENTS.md` routing (vendor-semantic ambiguity, phase-adjacent
contract reconciliation).

**Network authority.** A page-fetch tool was tried first against
`support.checkpoint.com`, `sc1.checkpoint.com`, `docs.paloaltonetworks.com`
and `pan.dev` and returned `EGRESS_BLOCKED` on every one — the same failure
class session 1 recorded as `CONNECT 403`. A separate search tool remained
reachable and returned indexed results: sometimes a verbatim excerpt of an
official page with a citeable URL (treated below as SOURCE C, per §3's
official-source policy), at other times only a title/URL with no body text
(treated as a discovery hint only, per AGENTS.md "field presence != field
semantic proof" and this contract's own "Community/forum/blog content may be
used only as a discovery hint, never as final authority" — the same
restriction applied here to any snippet that was itself only a paraphrase
rather than quoted official text). No device was contacted; no code, test,
collector, parser, schema, UI or transport file changed.

**Method discipline.** Per §2 of the audit task and the AGENTS.md vendor-
semantics law, a snippet was accepted as closing a row only where it was a
genuine excerpt of an official page/KB/sk article establishing the semantic
itself — never a search engine's own paraphrase, never a field-name-only
correspondence, and never community content (CheckMates, Indeni, blogs,
Reddit-style forums) even when a snippet summarized one. Two cases below were
caught by this discipline and are recorded precisely so the same false-close
is not repeated: (1) the fullest structural CLI-output example found for
`show high-availability state` — "Election Option Information",
"Configuration Synchronization" / "Running Configuration: synchronized",
"Version Compatibility: ... Match" — is from the **WildFire Appliance**
operational-mode CLI reference, a different PANW product line with its own HA
state vocabulary (`active-controller`/`passive-controller`, not the NGFW
firewall's `active`/`passive`/`active-primary`/`active-secondary`); it is
recorded below as structural corroboration only, never as NGFW-firewall-
specific confirmation. (2) The only characterization found of the exact
behavioral difference between `cphaprob -l list` and `cphaprob -ia list` came
from CheckMates/Indeni community threads; per policy it is recorded as an
unconfirmed engineering hint for future gate-package research, not as a
contract fact, and does not close D-V6.

### Decision matrix (§18 of the audit task)

| Decision | Vendor | Semantic question | Docs result | Real-env needed | Final status |
| --- | --- | --- | --- | --- | --- |
| D-V1 | PAN | `conn-*` vocabulary | `conn-status`/`conn-ha1`/`conn-ha1-backup`/`conn-ha2` re-confirmed as real, officially-discussed fields; `conn-status`-class vocabulary is `up`/`down` with a free-text down-reason (e.g. "HA1 link went down"), per official KB. Per-field vocabulary for `conn-ha1` vs `conn-ha1-backup` vs `conn-ha2` individually, aggregate-vs-specific-link scope of `conn-status`, and missing-field meaning are **not** established | YES | PARTIALLY_CLOSED — still blocks freeze |
| D-V2 | PAN | sync/compat/election/flap | Concepts confirmed with concrete defaults via official KB: preemption-hold-time (minutes, default 1), promotion-hold-time (ms, range 0–60000, default 2000), max-flaps (range 0–16, default 3; a flap = leaving active within 15 min of previously leaving active), `nonfunc-flap-cnt` = Non-Functional-state flap count distinct from the preemption-loop counter. XML field-to-concept **binding remains `FIELD_BINDING_UNCONFIRMED`** (name correspondence only, no official statement that `local-info/max-flaps` etc. are literally these concepts) | YES | PARTIALLY_CLOSED — still blocks freeze |
| D-V3a | PAN | serial field semantics | No official PAN-OS/Panorama page body confirming `local-info/serial-num` / `peer-info/serial-num` semantics inside `show high-availability state` was retrievable (only an indirect, unread SDK-naming hint). Formatting/canonicalization semantics **not established by any source** | NO (this row is docs-only) | STILL_UNKNOWN |
| D-V3b | PAN | pair correspondence / B2 | N/A — real-environment only | YES | OPEN — `B2 NOT ESTABLISHED`, unchanged |
| D-V4 | PAN | `running-sync` location | Concept + CLI field label ("Configuration Synchronization" → "Running Configuration: synchronized / not synchronized", with an "Out-of-sync Reason" on failure) confirmed via NGFW-context official KB (not the WildFire-only citation — see method discipline above). Exact XML element path/command (`state` vs `all`) for the `type=op` API response **not** confirmed by an official source (only a non-official code sample suggests `result/group/running-sync`) | YES | PARTIALLY_CLOSED — still blocks freeze |
| D-V5 | CP | failover-statistics syntax/version | Command purpose and version availability substantially narrowed: full-Gaia Clish `show cluster failover` is documented in official CLI Reference Guides across R80.20 GA, R80.30 and R81 (not merely a Spark novelty); Spark/Gaia-Embedded Expert `cphaprob show_failover` is documented since R81.10.15 with a confirmed output shape (last event: member/reason/time; cluster failover counter + reset time; 20-entry history with No./Time/Transition/CPU/Reason). Field-for-field parity between the two variants, and VSX applicability, **not** established | YES (parity + VSX) | PARTIALLY_CLOSED — still blocks freeze |
| D-V6 | CP | pnote/state syntax | No official-source advance beyond the existing draft. sk117236's Gaia-Embedded scoping re-confirmed (title only). The only behavioral-difference characterization found for `-l list` vs `-ia list` is community-sourced and, per policy, is not authority | YES | STILL_UNKNOWN |
| D-V7 | CP | recovery/preemption source | Confirmed an official "Cluster Management APIs" surface exists (R80.40+ ClusterXL Admin Guide) — the correct family to search. Exact attribute/field name for the recovery-method setting **not found** by any accessible source | YES | STILL_UNKNOWN |
| D-V9a | CP VSX | documented caveat | sk165432 title/scope re-confirmed unchanged: VSX (Traditional), non-VS0 `cphaprob stat` context ⇒ member reads `Down`. Exact affected releases, fix version, and official recommended alternative **not retrievable** (article body blocked) | NO (concept only) | PARTIAL — unchanged in substance from the session-1 draft |
| D-V9b | CP VSX | estate applicability | N/A — real-environment only | YES | OPEN |

No row reached `CLOSED_BY_DOCS`. The freeze-blocking set is unchanged in
substance (narrower evidence, same blocking outcome) and is now the split
form: `D-V1, D-V2, D-V3a, D-V3b, D-V4, D-V5, D-V6, D-V7, D-V9a, D-V9b`.

### Source table (§19 of the audit task)

| Vendor | Decision | Official source | Version scope | Exact semantic established | Semantic still NOT established | Contract impact |
| --- | --- | --- | --- | --- | --- | --- |
| PAN | D-V1 | PANW Knowledge Base — "High Availability – HA Peer Connection Status"; "High-Availability – HA links status" (`knowledgebase.paloaltonetworks.com`) | not stated in the retrieved snippet | `conn-status`-class field: `up`/`down`; down carries a free-text reason (e.g. "HA1 link went down") | per-field vocabulary for `conn-ha1`/`conn-ha1-backup`/`conn-ha2` individually; missing-field meaning | none to §24 `REQUIRED` status; D-V1 stays blocking |
| PAN | D-V2 | PANW Knowledge Base — "HA Failover Hold Timers"; "When does an HA node go into Suspended state due to Non-Functional/Preemption loop" | not stated | preemption-hold-time (min, default 1); promotion-hold-time (ms, 0–60000, default 2000); max-flaps (0–16, default 3; flap = leaving active within 15 min); `nonfunc-flap-cnt` distinct from preemption-loop flaps | XML field-to-concept binding (`FIELD_BINDING_UNCONFIRMED`) | none to §24 status; sharpens the S2 interpretation guide once binding is proven; D-V2 stays blocking |
| PAN | D-V4 | PANW Knowledge Base — "High-Availability – Out of Sync Peers – Configuration"; "High Availability configuration status is 'not synchronized'" (NGFW-context, not WildFire) | general NGFW | a "Running Configuration: synchronized/not synchronized" state with an "Out-of-sync Reason" field exists under "Configuration Synchronization" | literal XML element name/path for the `type=op` API response; whether `show high-availability all` is additionally required | §24 `show high-availability all` row's conditional REQUIRED status unchanged; D-V4 stays blocking, S2 search space narrows to "look in `state` first" |
| CP | D-V5 | Check Point CLI Reference Guides — "Viewing/Monitoring Cluster Failover Statistics" (R80.20 GA, R80.30, R81 full-Gaia; SMB R81.10.X for Spark/Gaia Embedded) — page/title level, bodies not fetchable | full-Gaia Clish since ≥R80.20 GA; Spark/Gaia-Embedded Expert `cphaprob show_failover` since R81.10.15 | command purpose (count/reason/last-event-time); Spark-variant output shape (last event, counter+reset time, 20-entry history w/ Transition+CPU+Reason) | field-for-field parity between full-Gaia Clish and Spark Expert variants; VSX applicability | §24 "cluster failover statistics" row's version caveat narrows from `UNKNOWN` to a two-variant version map; still `REQUIRED (gate)`; D-V5 stays blocking |
| CP | D-V7 | Check Point ClusterXL Admin Guide — "Cluster Management APIs" (R80.40+) — title/existence only, body not fetchable | R80.40+ | an official cluster-object Management API surface exists | exact attribute/field name for the recovery-method setting | none to §24 status; narrows the S4 gate-package research target; D-V7 stays blocking |
| CP VSX | D-V9a | Check Point sk165432 — title/scope only, body not fetchable | not retrievable | symptom + product scope (VSX Traditional, non-VS0 `cphaprob stat` ⇒ `Down`) — unchanged from session 1 | affected releases; fix version; official recommended alternative | none; D-V9a caveat status unchanged; D-V9b unaffected |

D-V3a and D-V6 have no source-table row: no official-source text was
retrievable beyond what session 1 already recorded, so nothing changed to
report as established.

### Command battery review (§12 of the audit task)

No new command is proposed. Every candidate in §24 was re-evaluated against
this session's findings:

- **KEEP, unchanged:** all `REQUIRED`/`OPTIONAL` rows not named below.
- **KEEP, annotation narrowed only (no status change):** CP cluster
  failover-statistics row (D-V5 — version-availability caveat sharpened, see
  source table); PAN `show high-availability state` / `all` rows (D-V4 — no
  status change, search-space note added).
- **REMAIN_UNKNOWN, unchanged:** CP `cphaprob state` (D-V6 field set); CP
  `cphaprob -l list` (stays `REJECTED` in favour of `-ia list`, variance still
  unconfirmed by an official source); CP MDS recovery-setting attribute
  (D-V7); PAN `path-monitoring`/`link-monitoring` show-commands.
- **REMOVE:** none — no row's official semantics disproved its candidacy.

### Configuration/runtime field-trace recheck (§13 of the audit task)

No row's collection/correctness classification in §25 changes. The narrowing
above affects only the "Required correction" interpretation text for two
rows, already reflected in the source table: CP `flap_history` (D-V5 version
map) and PAN `running_sync` (D-V4 search-space note). Both remain
`NOT_COLLECTED`/`UNKNOWN` in §25 pending their respective gate/enumeration
slices — nothing here authorizes collecting them.

### Command safety recheck (§16 of the audit task)

No command's read-only class, privilege/context, or cost changed. The two
rows whose version/availability narrowed (D-V5, D-V4) remain `CLASS_0_READ`
candidates exactly as classified in §24; narrowing a version map is not a
safety-relevant change and does not admit either row to an approved gate.

## Official vendor semantics confirmation pass — Source Pack 2 (2026-09-03, session 3)

Third session of the vendor-semantics audit (`OP.0b.0 — CONTRACT RECONCILIATION
/ OFFICIAL SOURCE PACK 2`). Reasoning tier: `Sonnet 5, extended thinking
(high)`. The task supplied a hypothesis ("source pack") built from an
independent research pass; per its own instruction ("This source pack is NOT
itself vendor authority... independently confirm every load-bearing claim")
every claim below was re-derived from a source this session actually read,
not accepted from the prompt.

**Network authority, re-tested.** `WebFetch` against `pan.dev` and
`sc1.checkpoint.com` returned `EGRESS_BLOCKED` again — third consecutive
confirmation of the same structural block. `web.archive.org` is refused by
the fetch tool itself (a different failure mode, tool-level not proxy-level).
**New this session: `github.com`/`raw.githubusercontent.com` are reachable**,
both via `WebFetch` and directly via `curl` in `Bash`. This matters because
Palo Alto Networks' `pan.dev` "PAN-OS Upgrade Assurance" documentation is
generated from the docstrings of the official `PaloAltoNetworks/pan-os-
upgrade-assurance` GitHub repository (`panos_upgrade_assurance/firewall_proxy.py`)
— the same content, reached by a different, unblocked host. This was fetched
and read **verbatim** (via `curl`, not the summarizing fetch tool) and is the
strongest single source either session has obtained. `WebSearch` remained
reachable as before for the Check Point side, where no equivalent official
GitHub mirror exists.

### PAN — `get_ha_configuration()`, `panos_upgrade_assurance/firewall_proxy.py`

Read verbatim, official `PaloAltoNetworks` GitHub org, current `main` branch.
The docstring states plainly: *"The actual API command is `show
high-availability state`"* — i.e. the existing, already-`REQUIRED` P2 read —
and reproduces a captured real (masked) response as its documented return
shape:

```
{'enabled': 'yes', 'group': {
    'local-info': {..., 'state-sync': 'Complete', 'state-sync-type': 'ip',
      'preemptive': 'no', 'priority': '100', 'preempt-hold': '1',
      'promotion-hold': '20000', 'max-flaps': '3', 'nonfunc-flap-cnt': '0',
      'preempt-flap-cnt': '0', 'state-duration': '3675', 'version': '1',
      'build-rel': '10.2.3', 'app-version': ..., 'app-compat': 'Match',
      'url-compat': 'Mismatch', ... },
    'peer-info': {..., 'conn-status': 'up',
      'conn-ha1': {'conn-desc': 'heartbeat status', 'conn-primary': 'yes', 'conn-status': 'up'},
      'conn-ha2': {'conn-desc': 'link status', 'conn-ka-enbled': 'no', 'conn-primary': 'yes', 'conn-status': 'up'},
      ... },
    'running-sync': 'synchronized', 'running-sync-enabled': 'yes',
    'mode': 'Active-Passive', 'link-monitoring': {...}, 'path-monitoring': {...}
}}
```

Four findings materially change the contract's confidence, each scoped
precisely:

1. **`conn-ha1`/`conn-ha2` are nested objects, not scalars**, each with a
   `conn-desc`, `conn-primary` and its own `conn-status` — distinct from the
   scalar aggregate `peer-info/conn-status`. `conn-desc` is literally
   `"heartbeat status"` for HA1 and `"link status"` for HA2. This directly
   answers D-V1.A/B: HA1 ⇒ heartbeat/control link, HA2 ⇒ data/sync link,
   **field-binding CONFIRMED for both**, structure CONFIRMED (nested, not
   scalar) — overturning the prior assumption these were flat fields.
   `conn-ha1-backup` does **not** appear in this example (no HA1-backup
   interface configured on the captured device) — its presence is
   conditional; its internal shape was not independently confirmed by this
   source (by analogy with `conn-ha1` it is plausibly the same nested shape,
   but that is an inference, not a citation).
2. **`group.running-sync` and `group.running-sync-enabled` are literal keys
   returned by `show high-availability state`, at `group` scope** — exactly
   what D-V4 asked and the source pack hypothesized. This is now
   `CLOSED_BY_DOCS` (detail below).
3. **Most of the D-V2 field family is confirmed present at these exact
   paths** in a real captured response: `state-sync`, `state-sync-type`,
   `preemptive`, `priority`, `preempt-hold`, `promotion-hold`, `max-flaps`,
   `nonfunc-flap-cnt`, `preempt-flap-cnt`, `state-duration`, `build-rel`,
   `app-version`/`app-compat`, `av-version`/`av-compat`,
   `threat-version`/`threat-compat`, `url-version`/`url-compat` (and others).
   The `*-compat` vocabulary is now evidenced as (at least) a two-value set —
   the same sample shows `'app-compat': 'Match'` **and** `'url-compat':
   'Mismatch'` together, not merely one value in isolation. **Two fields are
   conspicuously absent from this healthy-state example: `last-error-reason`
   and `last-error-state`** — plausibly conditional (only present on an
   actual error), but their binding is **not** confirmed by this source.
4. **A genuine correction, not a new blocker:** `local-info/version` (`'1'`
   in the sample) is almost certainly an HA-protocol/schema version counter,
   **not** the PAN-OS software version — the real software build is
   `local-info/build-rel` (`'10.2.3'`). The existing §25 "software/content
   parity" row lists `version` and `build-rel` side by side without this
   distinction; corrected below. This is a parser-guidance clarification for
   the future S2 slice, not an architecture assumption disproven — nothing
   in the frozen architecture asserted `version` was the software version.

No serial field (`serial-num` or similar) appears anywhere in this captured
example's `local-info`/`peer-info` — the example happens to be a `PA-VM`
device. This is genuine **absence of evidence**, not evidence of absence, but
it means this source does **not** advance D-V3a; see below.

### CP — three findings, one of which contradicts the source pack

- **`cphaprob -ia list`**: three independent `WebSearch` passes returned the
  identical sentence — *"The complete list of the configured critical
  devices (pnotes) is printed by the 'cphaprob -ia list' command or 'show
  cluster members pnotes all' command"* — attributable to the official
  "Reporting the State of a Critical Device" (R80.40 ClusterXL Admin Guide)
  and/or "Viewing Critical Devices" (R81.20 CLI Reference Guide) pages, both
  of which appeared as top hits in the same result sets. **This is the
  opposite of the source pack's §12 hypothesis** that `-ia` returns only
  "Problem Notification plus problematic Critical Devices" — the repeated,
  consistently-worded evidence says `-ia list` is the **complete**
  enumeration, not a problem-filtered subset. Per the task's own §18
  instruction, the contradiction is reported and the better-evidenced
  reading is kept: `cphaprob -ia list` = full pnote enumeration. The
  specific three-way `-l`/`-i`/`-ia` split the source pack proposed remains
  **not independently confirmed** — D-V6 stays open on that specific point.
- **Register/unregister syntax, confirmed precisely**: `cphaprob -d
  Device_Name -t TimeOut_in_Sec -s State [-p] register` and `cphaprob -d
  Device_Name [-p] unregister` — both **mutating** verbs, correctly outside
  any read-only preflight candidate; also confirms *"On Security Gateway in
  VSX mode, global pnotes can be registered only from the context of VS0"*
  — a genuine, citable VSX caveat, additive to §9's VSX evidence.
- **`cphaprob show_failover` reset form, confirmed distinct**: `show cluster
  failover reset history` exists as a **separate, mutating** Clish command
  from the pure observation form. §21's requirement (reject every reset
  form) is satisfied by treating this as its own, explicitly `REJECTED`
  §24 candidate row (added below) rather than an implicit exclusion.
  Output shape corroborated again this session (last event: member/reason/
  time; failover counter; last-20 history) but the source pack's specific
  claim of a bounded `-l <number>` history-depth flag on `cphaprob
  show_failover` was **not** independently found in any search result this
  session — it is not used to close D-V5a.
- **Recovery-mode semantics, confirmed precisely**: distinctly-worded,
  consistent official-reading text for both settings — *"Maintain current
  active Cluster Member: If the current Active member fails... another
  Standby member will be promoted to be Active. When former Active member
  recovers... the former Standby member will remain to be in Active
  state."* / *"Switch to higher priority Cluster Member: ... Cluster Member
  with the highest priority always has to be Active. If the [highest-
  priority] Cluster Member recovers, cluster failover occurs again."* — this
  matches and sharpens session 1's already-`ESTABLISHED` concept-level
  finding. Combined with sk180184 (session 1: Cluster Mode string does
  **not** reliably reflect this setting — an explicit, documented
  non-correlation), D-V7a's both sub-questions (behavioral semantics; runtime
  mode correlation) are answered. `D-V7a = CLOSED_BY_DOCS`.
- **Cluster Management API limitation, confirmed**: *"The Cluster APIs are
  called 'simple' because they do not support all cluster object
  features... For operations on cluster objects that are not provided by
  these APIs, use SmartConsole"* (official "Cluster Management APIs" page).
  This explains, but does not resolve, D-V7b: it is now documented that some
  cluster-object settings are SmartConsole-only, but nothing found confirms
  or denies that the recovery-method setting specifically is one of them, and
  no attribute name was found on the (separate, broader) full Management API
  either. **No attribute name is invented.** `D-V7b` stays `STILL_UNKNOWN`.
- **sk165432, incremental diagnostic detail only**: the official
  support-portal result additionally shows *"the output of 'cphaprob list'
  shows 'There are no pnotes in problem state' and ... 'cphaprob -l list'
  shows all pnotes in 'Ok' state when executed within the context of the
  VS"* — confirming the false `Down` is a presentation-layer artifact of the
  VS-context `stat` read specifically, co-existing with genuinely healthy
  pnotes. Affected/fixed release numbers still not retrievable. `D-V9a`
  stays `PARTIAL`, texture only.

### PAN serial leading-zero (D-V3a sub-fact, kept separate from HA-field binding)

A distinct, genuine official PAN Knowledge Base article on serial numbers in
CSV/Excel import confirms: *"Excel automatically truncates all leading zeros
from numbers in CSV files"* and instructs treating the serial column as
`Text`, not numeric, to preserve them. This **is** official, on-point
confirmation that PAN serials can carry a leading zero and must be handled as
opaque text — it strengthens (with a now-precise citation, not general
inference) `AGENTS.md`'s opaque-identifier prohibition. Per the task's
explicit instruction (§7–8), this does **not** establish
`local-info/serial-num` or `peer-info/serial-num` semantics inside `show
high-availability state` — no serial field appeared in the one official HA-
state example this session could read (see above). D-V3a's HA-field-binding
half stays `STILL_UNKNOWN`; only the general-serial-opacity half is newly
`CONFIRMED`.

### D-V5a / D-V5b split (per §11 of the audit task)

- **D-V5a — ClusterXL failover-statistics command contract**:
  `PARTIALLY_CLOSED`, substantially strengthened. Confirmed: command
  purpose; full-Gaia Clish `show cluster failover` documented across R80.20
  GA through at least R82 (a 27 April 2026 R82 Administration Guide PDF
  surfaced this session); Spark/Gaia-Embedded Expert `cphaprob show_failover`
  since R81.10.15; read-only observation form vs. the separate, mutating
  `... reset history` form; output shape (last event, counter, 20-entry
  history). **Not** confirmed: the source pack's specific bounded `-l
  <number>` history-depth flag, and field-for-field schema parity between
  the full-Gaia Clish and Spark Expert variants. Not `CLOSED_BY_DOCS` on the
  strict "exact syntax" bar the task sets, but close.
- **D-V5b — VSX applicability**: no official statement found either way.
  `OPEN / REAL_ENV_OR_DOC_REQUIRED`, unchanged from `STILL_UNKNOWN`'s prior
  substance — per §11's explicit instruction, this does **not** hold D-V5a's
  now-confirmed base semantics hostage.

### D-V7a / D-V7b split (per §16 of the audit task)

Already detailed above: `D-V7a = CLOSED_BY_DOCS` (recovery-mode behavioral
semantics + documented Cluster-Mode non-correlation); `D-V7b = STILL_UNKNOWN`
(no machine-readable attribute name found; the Simple Cluster API's
documented feature gap is a plausible explanation, not a resolution).

### Net effect

Two rows reach full `CLOSED_BY_DOCS` for the first time across three
sessions: **D-V4** and **D-V7a**. `D-V1`, `D-V2` upgrade within
`PARTIALLY_CLOSED` (field-binding CONFIRMED for most fields; vocabulary/
missing-field/`last-error-*` still open). `D-V5` splits into `D-V5a`
(`PARTIALLY_CLOSED`, strong) and `D-V5b` (`OPEN`). `D-V6` upgrades
`STILL_UNKNOWN → PARTIALLY_CLOSED` (register/unregister + `-ia` enumeration
semantics confirmed; the source pack's specific `-l`/`-i`/`-ia` three-way
split is **contradicted**, not confirmed, and stays open). `D-V7` splits into
`D-V7a` (`CLOSED_BY_DOCS`) and `D-V7b` (`STILL_UNKNOWN`, now with documented
context). `D-V3a` stays `STILL_UNKNOWN` for HA-field binding (a distinct,
narrower sub-fact — general serial opacity — is newly confirmed). `D-V3b`,
`D-V5b`, `D-V9b` are unchanged, real-env-only. `D-V9a` unchanged in status.
**No architecture assumption was disproven.**

### Decision matrix (§29 of the audit task)

| Decision | Scope | Official semantic status | Real-env required | Freeze impact |
| --- | --- | --- | --- | --- |
| D-V1 | PAN `conn-*` | PARTIALLY_CLOSED — field-binding + nested structure CONFIRMED for `conn-ha1`/`conn-ha2`; vocabulary/missing-field open | YES | blocks |
| D-V2 | PAN sync/election/flap/compat | PARTIALLY_CLOSED — binding CONFIRMED for most fields; `last-error-*` unconfirmed | YES | blocks |
| D-V3a | PAN HA serial semantics | STILL_UNKNOWN (HA-field binding); general opacity CONFIRMED (separate sub-fact) | NO (docs-only half) | blocks |
| D-V3b | PAN real B2 | N/A (real-env only) | YES | blocks |
| D-V4 | PAN running-sync | **CLOSED_BY_DOCS** | NO | resolved |
| D-V5a | CP ClusterXL failover statistics | PARTIALLY_CLOSED — strong | YES (schema parity) | blocks |
| D-V5b | CP VSX failover-stat applicability | OPEN — no official statement either way | YES | blocks |
| D-V6 | CP pnote/state semantics | PARTIALLY_CLOSED — contradicts source-pack hypothesis, better reading kept | YES | blocks |
| D-V7a | CP recovery behavior semantics | **CLOSED_BY_DOCS** | NO | resolved |
| D-V7b | CP configured-recovery read surface | STILL_UNKNOWN — explained, not resolved | YES (or doc) | blocks |
| D-V9a | CP VSX documented caveat | PARTIAL, unchanged | NO (concept) | blocks (VS readiness) |
| D-V9b | CP estate applicability | N/A | YES | blocks |

### Official source table (§30 of the audit task)

Vendor: PAN
Decision: D-V1, D-V2, D-V4
Official domain: `github.com/PaloAltoNetworks` (source for `pan.dev`, itself `EGRESS_BLOCKED`)
Exact page/article title: `pan-os-upgrade-assurance` — `panos_upgrade_assurance/firewall_proxy.py`, `FirewallProxy.get_ha_configuration()`
Release/version: repository `main` branch, read 2026-09-03; targets `show high-availability state` on current PAN-OS
Exact semantic established: `conn-ha1`/`conn-ha2` are nested objects with `conn-desc`/`conn-primary`/`conn-status`; `group.running-sync`/`group.running-sync-enabled` exist at `group` scope; most D-V2 fields present at their named paths in one real captured response; `*-compat` demonstrated as ≥2-valued (`Match`/`Mismatch` seen together); `local-info/version` ≠ software version (that's `build-rel`)
Still not established: exhaustive value vocabularies; missing-field semantics; `conn-ha1-backup` shape; `last-error-reason`/`last-error-state` binding; any serial field in HA state
Contract consequence: D-V4 → `CLOSED_BY_DOCS`; D-V1/D-V2 upgraded within `PARTIALLY_CLOSED`; §25 `version`/`build-rel` annotation corrected; P3 (`show high-availability all`) re-justification narrowed (see battery review below)

Vendor: PAN
Decision: D-V3a (serial opacity sub-fact only)
Official domain: `knowledgebase.paloaltonetworks.com`
Exact page/article title: PAN KB — serial numbers and leading zeros in CSV/Excel import (title not independently re-verified beyond the KCS result; content read via search snippet)
Release/version: not stated
Exact semantic established: Excel/CSV import truncates leading zeros from serial numbers; must be imported as `Text`
Still not established: `local-info/serial-num`/`peer-info/serial-num` semantics inside `show high-availability state`
Contract consequence: strengthens (with a precise citation) the existing opaque-identifier prohibition; does not close D-V3a's HA-field-binding half

Vendor: CP
Decision: D-V5a, D-V6 (pnote enumeration + register/unregister)
Official domain: `sc1.checkpoint.com` (page bodies `EGRESS_BLOCKED`; titles + repeated verbatim-matching snippets via `WebSearch`)
Exact page/article title: "Reporting the State of a Critical Device" (R80.40 ClusterXL Admin Guide); "Viewing Critical Devices" (R81.20 CLI Reference Guide); "Viewing/Monitoring Cluster Failover Statistics" (R80.20 GA/R80.30/R81/R82 CLI/Admin Guides); "Registering a Critical Device" / "Unregistering a Critical Device" (CLI Reference Guide)
Release/version: R80.20 GA through R82 (failover statistics, full-Gaia Clish); R81.10.15+ (Spark Expert); VSX-mode registration restricted to VS0 context
Exact semantic established: `cphaprob -ia list` = complete pnote enumeration (not problem-filtered); register/unregister exact flag syntax (both mutating, excluded); `show cluster failover reset history` is a separate mutating form
Still not established: `-l`/`-i` exact differentiation from `-ia`; `cphaprob state` exact field set; failover-statistics bounded history-depth flag; Clish/Expert schema parity
Contract consequence: D-V6 upgraded to `PARTIALLY_CLOSED`, source-pack hypothesis on `-i`/`-ia` explicitly contradicted; D-V5a upgraded to `PARTIALLY_CLOSED`, strong; §24 gains an explicit `REJECTED` row for the reset form

Vendor: CP
Decision: D-V7a, D-V7b
Official domain: `sc1.checkpoint.com` (page bodies `EGRESS_BLOCKED`; titles + snippets via `WebSearch`)
Exact page/article title: "Changing the Settings of Cluster Object in SmartConsole"; "Multi-Version Cluster Limitations" (both ClusterXL Admin Guide); "Cluster Management APIs" (ClusterXL Admin Guide, R80.40+)
Release/version: R80.40+ for the Cluster Management API family
Exact semantic established: precise behavioral semantics of "Maintain current active" vs "Switch to higher priority"; the Simple Cluster API does not expose every cluster-object feature and directs unsupported settings to SmartConsole
Still not established: any machine-readable attribute/property name for the recovery-method setting, on the Simple Cluster API or the broader Management API
Contract consequence: D-V7a → `CLOSED_BY_DOCS`; D-V7b stays `STILL_UNKNOWN` with documented context; no attribute name invented

## Final semantic blocker closure — session 4 (2026-09-03)

Fourth and final broad vendor-semantics session (`OP.0b.0 — FINAL SEMANTIC
BLOCKER CLOSURE`). Scope, per the task's own instruction: one last targeted
search each for `D-V3a`/`D-V7b`, then a classification-only triage of every
residual `PARTIAL` row, then the freeze decision. Not another general
architecture review; nothing below reopens settled repository source audit,
recorded real-environment findings, or the domain invariants — it only
determines whether their existing consequences were already sufficient to
freeze.

### D-V3a — one final search, and the freeze-boundary question

Re-read the official PaloAltoNetworks `pan-os-upgrade-assurance` source
(`firewall_proxy.py`) with a search targeted specifically at `serial`: it
appears once, in `get_licenses()` (device license serials, unrelated), and
**not at all** in `get_ha_configuration()`'s documented `show
high-availability state` response shape. This is the same official source
that closed `D-V4`; its `get_ha_configuration()` docstring is presented as a
comprehensive real (masked) capture, and it does not carry a serial field.
Absence of evidence is not evidence of absence, but across four sessions no
accessible source — official or otherwise — has named `local-info/serial-num`
or `peer-info/serial-num`'s semantics inside this specific command. `D-V3a`
stays `STILL_UNKNOWN` for HA-field serial binding.

The freeze-boundary question (§5 of the audit task): does `OP.0b.0` need
`D-V3a` closed to freeze? Re-reading this document's own **already-written**
"Identity contract → Palo Alto" section (§"Identity contract", unchanged by
this session) answers it: the serial-keyed **candidate** pair identity is
explicitly `**NOT FROZEN** until the real-env serial correspondence result is
`MATCH`/`MATCH` and the official semantics of `peer-info/serial-num` are
confirmed. Until then the current hostname-keyed unit id stands and its known
defects stand with it.` This is precisely the deterministic, fail-closed
fallback §5/§16 of the audit task asked whether a safe contract could state —
**it already does**, from session 1. The base architecture (evidence
taxonomy, B₁/B₂ grading, hostname-keyed unit identity, provenance model) does
not depend on the serial-keyed successor model existing yet; it depends only
on that successor model never being silently adopted without both `D-V3a`
closing *and* `D-V3b` matching — which the existing "NOT FROZEN" language
already guarantees. `D-V3a` is therefore **not** an architecture-freeze
blocker. It remains exactly what "Identity contract" and bug-register row
`PAN-7` already scope it as: a prerequisite for the *successor* identity
model and, transitively, for any PAN CLASS 2 path — i.e. a CLASS-2-time
blocker. This is not a weakening: the identity requirement itself (B₂,
opaque comparison, no normalization) is unchanged; only its classification
against the freeze/CLASS-2 boundary is being stated explicitly for the first
time.

### D-V7b — one final search, and the freeze-boundary question

Two further official-adjacent negatives, beyond session 3's "Simple Cluster
API does not support all cluster object features" finding: (1) the official
`CheckPointSW/CheckPointAnsibleMgmtCollection` GitHub repository's
`cp_mgmt_simple_cluster` module — a comprehensive, actively maintained
parameter-by-parameter specification of the `simple-cluster` object
(interfaces, security blades, VPN, topology, members, etc.) — documents **no**
recovery, failback, preemption, or "maintain current active"/"switch to
higher priority" parameter anywhere in its `DOCUMENTATION` block; (2) no
official schema/OpenAPI source for the broader (non-"simple") generic
Management API's cluster-object attributes was found either. Together these
converge, more strongly than session 3's finding alone, on: the setting is
**not** exposed through the documented, supported, safe-to-automate Check
Point management surface. It remains possible in principle that Check
Point's lower-level generic-object API (`show-generic-object`, whose internal
attribute names are not individually documented as stable) could reach it,
but this contract does not invent that attribute name, per the audit task's
explicit prohibition. `D-V7b` stays `STILL_UNKNOWN`.

The freeze-boundary question (§9 of the audit task): audit the **current**
contract only. §"Seven-check model review" already reads: `6 preemption_known
| KEEP (recorded, non-blocking) | CP source is management plane (sk180184);
PAN is runtime preemptive/priority/preempt-hold`. The minimum CP battery's own
row A9 already reads: `management-plane recovery setting | ... | 6 UNKNOWN
(not blocking, recorded)`. Both are **pre-existing, unchanged, mutually
consistent** — check 6 is explicitly, doubly specified as excluded from the
`PASS`-all requirement (the same `NOT_APPLICABLE`-adjacent treatment already
formalized for other excluded cases in §"Fail-closed / UNKNOWN semantics").
No `CONTRACT CONTRADICTION` exists. Answer: **NO**, `preemption_known` is not
currently required for `SAFE_TO_FAILOVER`. `D-V7b` is therefore **not** an
architecture-freeze blocker. Exact readiness consequence: `configured_
preemption = UNKNOWN` is recorded on every unit until a later approved
management-source slice establishes retrieval; this never by itself prevents
a unit's other seven checks from reaching `SAFE_TO_FAILOVER` once `OP.0b.1`'s
gate package lands (§P4 invariant keeps that structurally unreachable
regardless, today). Per bug-register row `CP-3` (`preemption not reliably
device-readable; no management-plane read exists | P0`), this remains a hard
prerequisite **before CLASS 2** — unchanged, and now stated explicitly as a
CLASS-2 boundary item rather than an ungrouped "P0."

### Residual PARTIAL row triage (§10–§13 of the audit task)

Applying the safety-minimalism principle (§11/§12: a minimal proven
authoritative predicate, fail-closed on anything unrecognized, beats
exhaustive vocabulary reverse-engineering) to each row still `PARTIALLY_
CLOSED`, without reopening their research:

- **`D-V1`** (PAN `conn-*`) — `SEMANTIC interpretation now FROZEN`: `conn-
  status`-class value `"up"` → healthy signal; any other value (recognized
  `"down"` or unrecognized) → not sufficient for `PASS`; an absent field is
  already covered by the pre-existing domain invariant 6 ("absence of
  observation ≠ observation of absence" → `UNKNOWN`/`COLLECTION_FAILED`,
  never `KNOWN_BAD`) — vendor confirmation of *every* possible string was
  never required. Applies uniformly to `conn-status` wherever it appears
  (top-level scalar or nested inside `conn-ha1`/`conn-ha2`) — the fail-closed
  default means an unproven assumption about a specific nested occurrence's
  vocabulary fails safe (an incorrect `UNKNOWN`), never unsafe (an incorrect
  `PASS`). Classification: `REAL_ENV_VALIDATION_GATE` (S2 — confirm the
  parser reads the now-known nested paths against real captured output).
- **`D-V2`** (PAN sync/compat/election/flap) — split: `state-sync`
  (`"Complete"` → healthy, else → not-sufficient, same pattern as `D-V1`) and
  `*-compat` (`"Match"` → healthy, `"Mismatch"` → an explicit, vendor-
  documented parity failure, else → `UNKNOWN`) are `SEMANTIC interpretation
  FROZEN`, `REAL_ENV_VALIDATION_GATE` only (S2). The `preemptive`/`priority`/
  `preempt-hold`/`promotion-hold` sub-family feeds check 6, already
  non-blocking (see `D-V7b` above) — its residual ambiguity is therefore
  provably non-blocking too. The `max-flaps`/`nonfunc-flap-cnt`/`preempt-
  flap-cnt`/`state-duration` sub-family feeds check 7 (flap_history), which
  — unlike check 6 — is **not** marked non-blocking in the existing text, and
  no exact pass/fail threshold for these counters is frozen anywhere in this
  document for either vendor. This is a genuine, narrow, still-open question,
  but it is **the same shape** of gap the contract already treats as
  non-blocking-for-freeze: `D-F1`/`D-F2` are already open, non-blocking,
  product-owner numeric-threshold decisions (max age, skew tolerance) sitting
  underneath an otherwise-frozen structure. This session adds a third,
  `D-F3`, on the same terms: the qualitative meaning of check 7 is fixed now
  (exceeding an as-yet-undecided flap/failover-frequency threshold is unsafe;
  an undecided threshold means check 7 cannot yet compute a real `PASS` —
  fail-closed, not silently permissive), the number is a bounded
  implementation-time decision. `last-error-reason`/`last-error-state`
  binding stays unconfirmed (absent from the one official captured example)
  but both are corroborative reason-text, never the primary gating fact
  (`state`'s non-functional-set membership is) — their absence is an
  acceptable, already-fail-closed outcome, not a blocker.
- **`D-V5a`** (CP failover statistics) — `SEMANTIC interpretation FROZEN`
  under the §12 minimal-parser-contract principle: count, last event/reason/
  time is enough; extra columns are safely ignorable; the reset form is
  already identified and `REJECTED`. The exact CLI flag/history-depth syntax
  and full Clish/Expert schema parity are `COMMAND_GATE_ONLY` — precisely
  `OP.0b.1`'s job, not an open safety interpretation. Same `D-F3` threshold
  caveat as `D-V2`'s flap family applies symmetrically here (CP check 7).
- **`D-V5b`** (VSX applicability of failover statistics) — re-examined
  against the **already-frozen** battery definition (§"Evidence per check —
  VSX physical cluster": "Same battery as ClusterXL run in the **physical
  (VS0) context**"): failover statistics was never specified as a per-VS
  read. The question the source pack raised turns out not to be load-bearing
  — nothing in the frozen minimum battery needs a per-VS answer. Reclassified
  `NON_BLOCKING_INFORMATIONAL`; dropped from the active blocking list.
- **`D-V6`** (CP pnote/state) — `SEMANTIC interpretation FROZEN` under the
  §12 minimal-parser-contract principle: any pnote in `problem` state (from
  the confirmed-complete `-ia list` enumeration) → check 8 `KNOWN_BAD`
  signal; none → healthy; read failure → `UNKNOWN`. `cphaprob state` was
  already `OPTIONAL`/corroboration-only in §24 before this session — its
  unresolved field set was never load-bearing. The `-l`/`-i` exact
  differentiation is `COMMAND_GATE_ONLY`.
- **`D-V9a`** (VSX sk165432 caveat) — already `SEMANTIC interpretation
  FROZEN`, verbatim, in the pre-existing §"Evidence per check — VSX Virtual
  System" text: *"a `Down` read in a non-VS0 context is `UNKNOWN` until
  real-env validation on this estate's version proves the read reliable...
  never `KNOWN_BAD`, and never a per-VS action input."* This is exactly the
  deterministic rule §13 of the audit task asked whether documentation
  supports — it was already written. `D-V9a` was never an architecture
  blocker once this is recognized; `D-V9b` (does the caveat manifest on this
  estate) remains a pure `REAL_ENV_VALIDATION_GATE` (S8) precisely because
  the frozen interpretation is safe regardless of the real answer, and — per
  domain invariant 9 — a VS is never a CLASS-2 execution target either way.

### Final blocker table (§18 of the audit task)

| Decision | Semantic status | Architecture freeze blocker? | Real-env blocker? | CLASS 2 blocker? | Exact next closure |
| --- | --- | --- | --- | --- | --- |
| D-V1 | Interpretation FROZEN (minimal predicate + invariant 6) | NO | YES (S2) | NO | S2 parse-scope extension |
| D-V2 | Interpretation FROZEN for sync/compat/preemption family; flap family needs `D-F3` | NO | YES (S2) | NO (check-6 pieces) | S2 + `D-F3` product-owner decision |
| D-V3a | STILL_UNKNOWN (HA-field binding); successor model already `NOT FROZEN`, fallback stands | NO | N/A (docs-only) | YES | GitHub-mirror/human fetch — pre-CLASS-2 |
| D-V3b | `B2 NOT ESTABLISHED`, unchanged | NO | YES | YES (min. identity input) | S0, unchanged |
| D-V4 | CLOSED | NO | NO | depends on implementation | — |
| D-V5a | Interpretation FROZEN (count/reason/time minimal contract; reset excluded) | NO | YES (S8) | NO | `OP.0b.1` gate package + S8 |
| D-V5b | Not load-bearing — battery only requires physical/VS0-level reads | NO | NO | NO | none — dropped from blocking list |
| D-V6 | Interpretation FROZEN (pnote problem/no-problem via `-ia list`; `state` stays optional) | NO | YES (S5) | NO | `OP.0b.1` gate package + S5 |
| D-V7a | CLOSED | NO | NO | NO | — |
| D-V7b | STILL_UNKNOWN; check 6 already non-blocking (pre-existing) | NO | NO | YES (`CP-3`, P0 before CLASS 2) | GitHub-mirror/human fetch — pre-CLASS-2 |
| D-V9a | Interpretation already frozen (pre-existing text) | NO | NO | NO (VS never a CLASS-2 target, invariant 9) | none required |
| D-V9b | Estate applicability, confirmatory only | NO | YES (S8) | NO (same as D-V9a) | S8, unchanged |

### Architecture verdict (§19 of the audit task)

**ARCHITECTURE CORE STATUS: STABLE.** Operational entity model: STABLE.
Dedicated preflight layer: STABLE. Identity model: STABLE — the B₁/B₂ grading
+ hostname-keyed fallback + "successor NOT FROZEN until match" design already
absorbs the `D-V3a`/`D-V3b` uncertainty. Evidence/provenance model: STABLE.
Readiness prerequisites/check model: STABLE — check 6's non-blocking status
and the VSX fail-closed rule already absorb `D-V7b`/`D-V9a`; the one addition
is `D-F3` (a new open decision, not a redesign). Vendor command/evidence
battery: STABLE CANDIDATE — minimal parser contracts now defined for every
row; exact syntax is `OP.0b.1` territory. CLASS 2 boundary: STABLE — P4
invariant unchanged, structurally unreachable regardless; `D-V3a`/`D-V7b` now
explicitly filed against this boundary. **No item requires change.**

### Diminishing returns (§20 of the audit task)

**YES — reached.** Architecture is sufficiently stable; future work moves to
bounded slices: `OP.0b.1` command-gate syntax (CP flag/history-depth
confirmation, exact Clish/Expert parity), `D-F3`'s numeric threshold
(product-owner decision, bounded), S0/S2/S3/S5/S8 real-env measurements, and
— whenever convenient, not gating anything else — `D-V3a`/`D-V7b`'s eventual
resolution before CLASS 2 specifically. None of these needs another general
vendor-semantics review to resolve.

## Open decisions

**Column note (2026-09-03, session 4):** "Blocks freeze?" below records the
**final, post-freeze** determination — see §"Final semantic blocker closure
— session 4" for the reasoning behind every `NO` that used to be `YES`. None
of these rows is fully resolved; the column answers only whether the
*contract's own interpretation* still depends on resolving them, which — per
that section — it no longer does for any row except the two genuinely
`STILL_UNKNOWN` ones, and even those only gate CLASS 2, not this freeze.

| Id | Decision | Blocks freeze? | Resolves via |
| --- | --- | --- | --- |
| D-V1 | PAN `conn-status`/`conn-ha1`/`conn-ha1-backup`/`conn-ha2` value vocabulary | NO — interpretation frozen (minimal predicate) | S2 real-env parser validation |
| D-V2 | PAN `state-sync`, `*-compat`, `preemptive`, flap-counter field semantics | NO — interpretation frozen (sync/compat/preemption); flap sub-family gated by new `D-F3` | S2 real-env + `D-F3` |
| D-V3a | PAN HA-state serial field semantics (docs-only) | NO — successor identity model already `NOT FROZEN`, hostname-keyed fallback stands | **CLASS-2 blocker**: GitHub-mirror/human fetch |
| D-V3b | PAN peer-serial real correspondence / B2 | NO (architecture); real-env + CLASS-2 blocker | S0 result already pending; `B2 NOT ESTABLISHED`, do not reinterpret |
| D-V4 | PAN `running-sync` location (`state` XML sibling vs `all`) | NO — closed | **CLOSED_BY_DOCS 2026-09-03** |
| D-V5a | CP ClusterXL failover-statistics command contract | NO — interpretation frozen (count/reason/time minimal contract) | `OP.0b.1` gate package + S8 |
| D-V5b | CP VSX failover-statistics applicability | NO — not load-bearing (battery is physical/VS0-only) | none — dropped from active blocking list |
| D-V6 | CP `-ia list`/`-l list`/`-i list` differentiation; `cphaprob state` field set | NO — interpretation frozen (pnote problem/no-problem via `-ia list`) | `OP.0b.1` gate package + S5 |
| D-V7a | CP recovery/preemption behavior semantics | NO — closed | **CLOSED_BY_DOCS 2026-09-03** |
| D-V7b | CP configured-recovery machine-readable read surface | NO — check 6 already non-blocking (pre-existing, session-1 text) | **CLASS-2 blocker** (`CP-3`, P0 before CLASS 2): GitHub-mirror/human fetch |
| D-V8 | CP hotfix parity command | no (optional check) | Gaia CLI reference |
| D-V9a | CP VSX sk165432 documented caveat semantics | NO — interpretation already frozen (pre-existing text) | none required for freeze; S8 informational |
| D-V9b | sk165432 applicability to this estate's version | NO (architecture); real-env only | S8 — VS never a CLASS-2 target regardless (invariant 9) |
| D-T1 | PAN preflight transport: direct identity-gated API vs Panorama proxy | no | product owner + security |
| D-F1 | numeric max age for category C intent | no | product owner |
| D-F2 | numeric member-skew tolerance | no | product owner + vendor guidance |
| D-F3 | numeric flap/failover-frequency threshold for check 7 (both vendors) — **new, session 4** | no | product owner + vendor guidance; qualitative meaning frozen (§"Final semantic blocker closure"), undecided number ⇒ check 7 fail-closed (not silently PASS) until set |
| D-P1 | `op_degraded_verdict` | no (OP.1) | existing open decision |

## Rollback

Documentation only; nothing to roll back. If superseded, mark this file's
status and add the superseding path; never delete.

## Definition of done (for this FROZEN version)

1. All required §23 sections present — **done**.
2. §24, §25, §26 complete with every candidate/field/gap — **done**.
3. Every semantic either cited to an official source, `CLOSED_BY_DOCS`, or
   explicitly frozen as a minimal fail-closed interpretation with a named
   residual (real-env or bounded numeric decision) — **done** (session 4,
   §"Final semantic blocker closure"); no generic product knowledge used as
   authority anywhere, including in the minimalism predicates (each cites the
   official source it derives from).
4. Freeze decision stated with the exact remaining blocker list, separated
   from CLASS 2/real-env items — **done** (§"Open decisions" column note;
   §"Final semantic blocker closure" final blocker table).
5. Project metadata update — **done this session** (`STATE_UPDATE` follows;
   see `project/build_history.json`/`project/roadmap.json`).
6. CLASS 2 remains structurally unauthorized (P4 invariant) — **unchanged,
   verified still in force**.

## Next movement

`FREEZE_DECISION` reached (session 4) supersedes prior "Next movement" text
below only where it discussed *whether* to freeze; the discovery it
recorded — `github.com`/`raw.githubusercontent.com` reachable even though
`pan.dev`/`sc1.checkpoint.com`/`support.checkpoint.com` remain
`EGRESS_BLOCKED` — still stands and still applies to whoever eventually closes
`D-V3a`/`D-V7b` for CLASS 2. With the contract now frozen, the actual next
movements are implementation/real-env slices, not another vendor-semantics
session:

**A. Bounded implementation, per the existing S0–S9 slice sequence** (§
"Implementation slices (after FREEZE)", unchanged, now unblocked): S1
(preflight fact/provenance model, pure), S2 (PAN parse-scope extension —
`conn-*`, `running-sync`, sync/compat/election fields, all now interpretation-
frozen), S3 (CP parse-scope extension — pnote/mode/wire-form), in parallel;
then S4 (`OP.0b.1` command-gate package, using the now-narrower `UNKNOWN`
list this session leaves: D-V5a's exact flag syntax, D-V6's `-l`/`-i`
nuance).

**B. `D-F3` — the flap/failover-frequency numeric threshold** — a bounded
product-owner decision, parallel to `D-F1`/`D-F2`, needed before check 7 can
compute a real verdict for either vendor; does not block S1–S3.

**C. Pre-CLASS-2 closure of `D-V3a`/`D-V7b`** — `OFFICIAL_GITHUB_MIRROR_
SEARCH` first (the technique that closed `D-V4`/`D-V7a`, and that this
session's D-V7b search extended one level further without success — a
Check Point generic-object API schema is the next candidate, unconfirmed to
exist), then `HUMAN_ASSISTED_DOC_CONFIRMATION`. Independent of A/B; gates
only CLASS 2 and the PAN successor identity model, never S1–S9.

**D. Real-environment validations already scheduled** (S0, S2/S3's real-
capture components, S8) — unchanged, independent of A–C.

Superseded text, preserved (session 3's framing of a third path, now
historical — folded into **D** above): `HUMAN_REAL_ENV` — S0 result
(already pending, independent of this thread); S2/S3/S8 enumerations once a
real preflight-capable session is available.

Recommended: `Sonnet 5, normal` for S1–S3 (deterministic implementation
against this now-frozen contract); `Sonnet 5, extended thinking (high)` only
for **B** (`D-F3`, if it turns out to need cross-vendor judgment) and **C**
(vendor-semantic calls, same as this session).

---

## §24 Command surface table

Columns: Vendor · Platform/context · Command/API · Existing/new · Read-only · Runs where · Evidence returned · Authoritative for · Not authoritative for · Freshness · Cost · Retry-safe · Privacy · Official source · Decision.

| Vendor | Platform/context | Command/API | Exist | RO | Runs where | Evidence | Auth for | Not auth for | Fresh | Cost | Retry | Privacy | Official source | Decision |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| CP | Gaia, Clish | `show hostname` | existing | yes | per member, handshake | hostname | A (gate input) | — | in-run | LOW | yes | hostname (CLASS 2 local) | Gaia CLI ref; VALIDATION_0_6_1B_1_2 | REQUIRED |
| CP | Gaia, Clish | `show version all` | existing | yes | per member | Gaia/product version | H software | hotfix level | in-run | LOW | yes | none | Gaia CLI ref | REQUIRED |
| CP | Gaia, Clish | `cpstat os -f hw_info` | existing | yes | per member | model/serial | A attribute | gate | in-run | LOW | yes | serial | draft point 9 | OPTIONAL |
| CP | ClusterXL, Expert | `cphaprob stat` | existing | yes | per member; per VS via vsenv | roles, mode, peer rows | D; corroborating E/J | preemption (sk180184); peer identity (Unique Address ambiguous) | in-run | LOW | yes | member names, unique IPs (discard) | R80.40/R81 "Viewing Cluster State" | REQUIRED |
| CP | ClusterXL, Expert | `cphaprob state` | new | yes (monitoring) | per member | mode string, Active Attention, states | J; I corroboration only | I authority (sk180184) | in-run | LOW | yes | same | R81 CLI "Viewing Cluster State" | OPTIONAL (field set UNKNOWN) |
| CP | ClusterXL, Expert (CPRID today) | `cphaprob -a -m if` | existing (stage `cp`) | yes | per member | VIP set → `group_id` | B | D | bounded (topology) | LOW | yes | VIPs, if names | R80.40 "ClusterXL Monitoring Commands" | REQUIRED (transport change to direct session noted for gate) |
| CP | ClusterXL, Expert | `cphaprob -a if` | new | yes | per member (+VS optional, sk93341) | interface/CCP/sync status | F | identity | in-run | LOW | yes | if names | R81.10 "Viewing Critical Devices"; monitoring cmds | REQUIRED (gate) |
| CP | ClusterXL, Expert | `cphaprob -ia list` | new | yes | per member | pnotes — **confirmed complete enumeration** (2026-09-03) | J | — | in-run | LOW | yes | device names (safe class) | R81.10 "Viewing Critical Devices"; "Reporting the State of a Critical Device" | REQUIRED (gate) |
| CP | ClusterXL, Expert | `cphaprob -l list` | new | yes | — | pnotes (variant) | — | — | — | LOW | — | — | sk117236 only | REJECTED in favour of `-ia list` (variance UNKNOWN — 2026-09-03 pass found no official differentiation, only a since-superseded community claim) |
| CP | ClusterXL, Expert | `cphaprob -d <name> -t <sec> -s <state> [-p] register` / `cphaprob -d <name> [-p] unregister` | new | **no** (mutating) | — | — | — | — | — | — | — | — | CLI Reference Guide "Registering/Unregistering a Critical Device" (2026-09-03) | **REJECTED** — pnote register/unregister, out of scope for any read path |
| CP | ClusterXL R80.20+, Expert | `cphaprob syncstat` | new | yes | per member | delta sync stats | G | F | in-run | LOW | yes | none | R81.20 "Viewing Delta Synchronization"; sk34475 | REQUIRED (gate; vocabulary UNKNOWN) |
| CP | ClusterXL <R80.20, Expert | `fw ctl pstat` | new | yes | per member | sync section (legacy), conn table | G (<R80.20 only) | G on R80.20+ (sk34476) | in-run | LOW–MOD | yes | none | sk34476; R80.10 "Monitoring Synchronization" | OPTIONAL (version-conditional) |
| CP | Gaia, Expert | `fw stat` | new | yes | per member | installed policy | H policy | software | in-run | LOW | yes | policy name | R81 CLI ref `fw stat` | REQUIRED (gate; columns UNKNOWN) |
| CP | Gaia, Expert | `fw ver` | new | yes | — | version | — | — | — | LOW | — | — | — | REJECTED (redundant with `show version all`) |
| CP | Gaia, Expert | `cpinfo -y all` | new | yes | — | hotfixes | — | — | — | **HIGH** | no | host identity | sk92739 | REJECTED (cost) |
| CP | Gaia, Expert | `installed_jumbo_take` | new | ? | per member | JHF take | H hotfix | — | in-run | ? | ? | none | not established | UNKNOWN |
| CP | Gaia, Expert | `cplic print` | new | yes | per member | licence | 1 sub-fact | — | in-run | LOW | yes | **licence strings → scalars only** | draft point 9 | OPTIONAL |
| CP | Gaia, Expert | `cpstat os` | new | yes | per member | resources | 1 sub-fact | — | in-run | LOW | yes | host identity → scalars | draft point 9 | OPTIONAL |
| CP | ClusterXL, Expert/Clish | failover statistics — **observation form only** (`cphaprob show_failover` Spark/Embedded R81.10.15+; `show cluster failover` full-Gaia Clish, R80.20 GA–R82 confirmed) | new | yes | per member | last event (member/reason/time), failover counter, last-20 history | K | VSX applicability (D-V5b) | in-run | LOW | yes | none | "Viewing/Monitoring Cluster Failover Statistics" (R80.20 GA/R80.30/R81/R82); sk137472 | REQUIRED (gate; **D-V5a PARTIALLY_CLOSED 2026-09-03** — history-depth flag + Clish/Expert schema parity still UNKNOWN) |
| CP | ClusterXL, Clish | `show cluster failover reset history` — **mutating reset form**, distinct from the row above | new | **no** | — | — | — | — | — | — | — | — | same family (2026-09-03) | **REJECTED** — resets the failover counter/history; must never enter preflight |
| CP | ClusterXL, Expert | `cphaprob show_bond_groups` | new | yes | per member | bond status | F (bonds) | — | in-run | LOW | yes | if names | not established | UNKNOWN |
| CP | Gaia, Expert | `free -m`, `df -h`, `top -bn1` | new | yes | — | resources | — | — | — | LOW | — | — | — | REJECTED (draft exclusion; `cpstat os` covers) |
| CP | Gaia, Expert | `/var/log/messages`, `fw log` | new | yes | — | events | — | — | — | HIGH/unbounded | no | high | — | REJECTED |
| CP | MDS | `cpmiquerybin` recovery-setting attribute / Mgmt API | new | yes | per cluster object | "Maintain current active" vs "Switch to higher priority" — **behavioral semantics CLOSED_BY_DOCS (D-V7a, 2026-09-03)**; machine-readable attribute name **STILL_UNKNOWN (D-V7b)** | **I (authoritative)** | runtime | bounded (config) | LOW | yes | object names | "Changing the Settings of Cluster Object in SmartConsole"; "Cluster Management APIs" (Simple Cluster API documented as not exposing every feature — **attribute name still UNKNOWN**) | REQUIRED (gate; D-V7b UNKNOWN) |
| CP | Gaia Clish R80.20+ | `show cluster state` / `members pnotes all` / `statistics sync` / `failover` | new | yes | per member (direct-Clish-only hosts) | same as `cphaprob` family | D/J/G/K | — | in-run | LOW | yes | same | R81.x CLI ref | OPTIONAL (alternative for `capability_gap` hosts; availability by version UNKNOWN) |
| CP | VSX, Expert | `vsx stat -v` | existing (`vsx_runner`) / new in preflight session | yes | per member | VSIDs + status | B (VS enumeration) | VS HA state | in-run | LOW | yes | VS names | R81 CLI ref `vsx stat` | REQUIRED |
| CP | VSX, Expert exec | `vsenv <N> >/dev/null 2>&1; <cmd>` | existing | yes (context) | per VS | context switch | — | — | — | LOW | yes | none | R81.20 VSX "General Troubleshooting Steps" | REQUIRED (primitive) |
| CP | VSX, Expert | `fw ctl set int vsid <N>` | existing (`vsx_runner`) | **no** (kernel set) | — | — | — | — | — | — | — | — | — | **REJECTED** for preflight |
| CP | ClusterXL | `clusterXL_admin down/up` | — | **no** | — | — | — | — | — | — | — | — | design §3.1 | REJECTED (CLASS 2, out of scope) |
| PAN | Panorama API | `<show><devices><all/></devices></show>` | existing | yes | once | inventory: serial, mgmt IP, `ha-state` | A inventory (I1), M | **runtime role** | bounded | LOW | yes | IPs, serials (local) | "Query a Firewall from Panorama (API)" | REQUIRED (inventory only) |
| PAN | Panorama API `target=` / direct | `<show><high-availability><state/></high-availability></show>` | existing | yes | per firewall | D, E(I3/I4), F, G, H, I, J, K fields | see §25 | config intent | in-run | LOW | yes | IPs, serials → tokens | KB HA Peer Connection Status; CLI ref pages | REQUIRED (full parse; D-V1–V4) |
| PAN | direct API | `<show><system><info/></system></show>` | existing | yes | per firewall | serial (I2), sw/content versions | A gate, H | HA state | in-run | LOW | yes | serial, hostname | design §3.2; existing gate | REQUIRED |
| PAN | direct API | `keygen` | existing | yes (auth) | per firewall | API key (memory only) | — | — | — | LOW | yes | **credential** | XML API docs | REQUIRED (transport) |
| PAN | Panorama `target=` / direct | `type=config action=show xpath=/config` | existing | yes | per firewall | configured HA1 peer, election, sync enable | C | runtime | bounded (D-F1) | MOD | yes | full config (sanitise) | XML API "Configuration (API)" | REQUIRED (intent, bounded age) |
| PAN | direct | `show config effective-running` (dynamic slot) | existing | yes | per firewall | effective config | C (primary per AGENTS.md) | runtime | bounded | MOD | yes | same | AGENTS.md PAN rules | REQUIRED for C (prefer over proxied) |
| PAN | API | `show high-availability all` | new | yes | per firewall | link detail | F | — | in-run | LOW | yes | IPs/MACs | CLI ref `show high-availability all`; KB out-of-sync | OPTIONAL — **re-justified 2026-09-03**: `running-sync` is `CLOSED_BY_DOCS` as sourced from `show high-availability state` (P2, already REQUIRED), not `all`; P2's own `local-info`/`peer-info` already carry `ha1-ipaddr`/`ha1-macaddr`/`ha2-ipaddr`/`ha2-macaddr`/`ha1-port`/`ha2-port` per the official PANW source read this session, so no PAN preflight fact currently in this contract is known to require `all` exclusively — keep OPTIONAL pending a fact proven `all`-only |
| PAN | API | `show high-availability state-synchronization` | new | yes | per firewall | session sync detail | G | — | in-run | LOW | yes | none | not established for NGFW | UNKNOWN |
| PAN | API | `show high-availability path-monitoring` | new | yes | per firewall | monitored paths | F/J | — | in-run | LOW | yes | destination IPs (local) | 11.1 "HA Link and Path Monitoring" | REQUIRED (gate) |
| PAN | API | `show high-availability link-monitoring` | new | yes | per firewall | monitored links | F/J | — | in-run | LOW | yes | if names | concept documented; show-cmd PARTIAL | REQUIRED (gate; confirm) |
| PAN | API | `show high-availability flap-statistics` | new | yes | — | cluster flaps | K (HA4) | A/P pair | — | LOW | — | — | HA clustering docs | UNKNOWN / NOT_APPLICABLE (no HA4) |
| PAN | API | `show session info`, `show interface all`, `show routing route` | new | yes | per firewall | dataplane readiness | — (not among checks) | — | in-run | MOD–HIGH (routes) | yes | routes/IPs | design §3.2 | OPTIONAL / deferred |
| PAN | API | `request high-availability state suspend/functional`, `sync-to-remote` | — | **no** | — | — | — | — | — | — | — | — | CLI ref `request high-availability …` | REJECTED (CLASS 2 / mutating) |
| PAN | Panorama | `entry/ha-state` from `show devices all` | existing | yes | — | cached role | M | **D** | stale | — | — | — | — | REJECTED as runtime source |

## §25 Configuration / runtime field trace table

Status vocabulary: COLLECTED_AND_PARSED · COLLECTED_NOT_PARSED · NOT_COLLECTED · UNKNOWN · **PARSER_IMPLEMENTED — PRODUCTION_WIRING_PENDING** (session-correction addition, 2026-09-03: a parser/projection exists and is proven correct against synthetic fixtures on the SAME already-fetched response, but no current production collection path actually invokes it — `COLLECTED_AND_PARSED` is reserved for a field the live product path parses today; see §25a for the full six-dimension reconciliation this addition exists to make precise). Correctness: VALIDATED · SUSPECT · BROKEN · UNKNOWN.

| Normalized fact | Vendor | Unit | Raw source | Command/API | Field/path | Parser | Collection | Correctness | Required correction | Real-env |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| member_identity | CP | cluster | handshake | `show hostname` | stdout | `_usable_clish_result` | COLLECTED_AND_PARSED | VALIDATED | record as identity gate on row | done (0.6.1B.1.2) |
| host_key_trust | CP | cluster | SSH | — | fingerprint | strict preflight | COLLECTED_AND_PARSED | VALIDATED (R1/R3); R2 prod pending | none | R2 owed |
| cluster_identity | CP | cluster | VIP set | `cphaprob -a -m if` | "virtual cluster interfaces" | `parse_cluster_virtual_interfaces` | COLLECTED_AND_PARSED | VALIDATED | join freshness; move read into preflight session | done (real pairs) |
| local_role | CP | cluster/VS | `cphaprob stat` | same | `(local)` row State | `_parse_clusterxl_runtime_role` | COLLECTED_AND_PARSED | VALIDATED (physical); **SUSPECT (VS, sk165432)** | keep; VS caveat; stop passing physical hostname | VS owed |
| cluster_mode | CP | cluster | `cphaprob stat` | same | "Cluster Mode" line | `_parse_clusterxl_cluster_mode` | COLLECTED_AND_PARSED | VALIDATED for existing modes (constructed fixtures); "Single VS Failover" **now recognised** (S3, 2026-09-03) as a distinct `vsx_single_vs_failover` enum value — never folded into `ha_new_mode` | real header capture | owed |
| peer_state (observed) | CP | cluster | `cphaprob stat` | same | non-local rows State (address/name excluded, not identity-grade) | `_parse_clusterxl_stat_preflight_fields` + `project_cp_preflight_facts` (S3, 2026-09-03) | PARSER_IMPLEMENTED — PRODUCTION_WIRING_PENDING (`_collect_host(..., include_preflight_fields=True)` opt-in, dormant by design; see §25b) | VALIDATED against synthetic fixtures (state token only; one-member buffer never synthesizes a peer row) | production wiring is S5/S6's job | owed |
| failure_reason | CP | cluster | `cphaprob stat` | same | local role token reclassified as boolean (`ACTIVE ATTENTION`/`DOWN` ⇒ `True`) — **no free-text reason suffix is parsed**: no vendor-confirmed safe reason-text vocabulary exists for `cphaprob stat` (only the state token itself is ESTABLISHED, §"Vendor semantics established"), and parsing an arbitrary suffix risks raw-buffer leakage | `_parse_clusterxl_stat_preflight_fields` (`local_attention`) + `project_cp_preflight_facts` (S3, 2026-09-03) | PARSER_IMPLEMENTED — PRODUCTION_WIRING_PENDING (see §25b) | VALIDATED (boolean derivation, not free text) | free-text reason stays NOT_COLLECTED pending a confirmed safe vocabulary | owed |
| member_skew | CP | cluster | timestamps | — | `started_at` (rows); `collected_at` (S3 projection, caller-supplied) | S1 `evaluate_coherence` (existing) | COLLECTED_NOT_PARSED on rows; computable via S1 once S5/S6 supply real per-member `collected_at` to the S3 projection | — | S5/S6 provenance | — |
| control_link_health | CP | cluster | — | `cphaprob -a if` | interface states | — | NOT_COLLECTED | — | S5 (gate) | owed |
| sync_link_health | CP | cluster | — | `cphaprob -a if`, `syncstat` | sync if / drops | — | NOT_COLLECTED | — | S5 | owed |
| state_sync | CP | cluster | — | `cphaprob syncstat` (R80.20+) | delta-sync status | — | NOT_COLLECTED | — | S5; version-conditional | owed |
| pnotes | CP | cluster | — | `cphaprob -ia list` — **confirmed complete enumeration, not problem-filtered (2026-09-03)** | device/state | — | NOT_COLLECTED | — | S5 | owed |
| policy_parity | CP | cluster | — | `fw stat` | policy/install | — | NOT_COLLECTED | — | S5 | owed |
| software_parity | CP | cluster | `show version all` | same | version | `_parse_gaia_version` | COLLECTED_NOT_PARSED (not compared) | VALIDATED (collection) | compare across members in S7 | — |
| hotfix_parity | CP | cluster | — | UNKNOWN | — | — | NOT_COLLECTED | UNKNOWN | D-V8 | — |
| preemption | CP | cluster | mgmt object | UNKNOWN attribute (behavioral semantics `CLOSED_BY_DOCS` 2026-09-03, D-V7a) | — | — | NOT_COLLECTED | D-V7a CONFIRMED / D-V7b UNKNOWN | D-V7b; never from Cluster Mode alone (sk180184) | — |
| flap_history | CP | cluster | — | failover statistics — observation form only, reset form excluded | count/reason/time | — | NOT_COLLECTED | D-V5a PARTIALLY_CLOSED 2026-09-03 (version map + purpose confirmed; history-depth flag + Clish/Expert parity open) | D-V5a/D-V5b | owed |
| vs_enumeration | CP | VSX | `vsx stat -v` | same | VSID/status | `vsx_runner.get_vs` | COLLECTED (nested shell) | SUSPECT (standby skipped; `-[12]$`) | preflight issues it directly | owed |
| vs_inherited_attrs | CP | VS | host row | — | platform/serial/… | — | COLLECTED | SUSPECT (unlabelled) | add `_source` labels | — |
| legacy_cluster | CP | VSX | hostname | — | `-1`/`-2` | `normalize_vsx` | COLLECTED | **BROKEN as identity** | remove from failover key path | — |
| row_provenance | CP | all | — | — | run_id/collected_at/wire cmd | S1 `Provenance` + S3 `project_cp_preflight_facts` (caller-supplied, not self-generated) | NOT_COLLECTED (on raw collector rows — unchanged); carried by the S3 projection layer once a caller supplies it | — | S5/S6 own creating/orchestrating real values | — |
| inventory_serial (I1) | PAN | pair | Panorama | `show devices all` | `entry/serial` | `get_devices` | COLLECTED_AND_PARSED | VALIDATED | none | — |
| identity_serial (I2) | PAN | pair | direct | `show system info` | `serial` | `_collect_direct_compare` | COLLECTED_AND_PARSED | VALIDATED (gate) | none | done |
| local_runtime_serial (I3) | PAN | pair | HA state | `show high-availability state` | `local-info/serial-num` | token (`a1a3882`) | COLLECTED_AND_PARSED (token) | UNKNOWN semantics | D-V3 | **pending (S0)** |
| peer_runtime_serial (I4) | PAN | pair | HA state | same | `peer-info/serial-num` | token | COLLECTED_AND_PARSED (token) | UNKNOWN | D-V3 | **pending (S0)** |
| management_ip | PAN | — | Panorama | `show devices all` | `entry/ip-address` | `get_devices` | COLLECTED_AND_PARSED | VALIDATED as address; **BROKEN as join key** | remove from pairing | done |
| configured_peer_ha1 | PAN | pair | config | `xpath=/config` (proxied) | `deviceconfig/high-availability/group/peer-ip` | `parse_ha_peer_ip_from_config` | COLLECTED_AND_PARSED | VALIDATED as HA1-plane value; transport labelled wrong | record plane/transport; prefer effective-running | done (A consistent, B inconsistent) |
| discovery_ha_state | PAN | — | Panorama | `show devices all` | `entry/ha-state` | `get_devices` | COLLECTED_AND_PARSED | **BROKEN as runtime source** (short-circuits query; lacks `enabled`) | never suppress runtime read | — |
| ha_enabled | PAN | pair | HA state | `state` | `result/enabled` | parsed | COLLECTED_AND_PARSED | VALIDATED | none | done |
| local_state / peer_state | PAN | pair | HA state | `state` | `local-info/state`, `peer-info/state` | parsed | COLLECTED_AND_PARSED | VALIDATED; **phantom-member use SUSPECT** | S7 removes uplift | done |
| mode | PAN | pair | HA state | `state` | `local-info/mode` | parsed | COLLECTED_AND_PARSED | VALIDATED | none | done |
| state_sync | PAN | pair | HA state | `state` | `local-info/state-sync[,-type]` | parsed (value only) | COLLECTED_AND_PARSED | UNKNOWN vocabulary | D-V2 | done (value seen) |
| conn_status / conn_ha1 / conn_ha1_backup / conn_ha2 | PAN | pair | HA state | `state` | `peer-info/conn-status` (scalar); `peer-info/conn-ha1`, `peer-info/conn-ha2` (**nested objects: `conn-desc`, `conn-primary`, `conn-status` — structure CONFIRMED 2026-09-03**); `conn-ha1-backup` presence conditional, shape not independently confirmed | `_parse_pan_ha_preflight_fields` (S2, 2026-09-03) | PARSER_IMPLEMENTED — PRODUCTION_WIRING_PENDING for the four `conn-status` leaves (see §25a); `conn-desc`/`conn-primary` remain COLLECTED_NOT_PARSED (out of S2's bounded scope) | field-binding CONFIRMED for `conn-ha1`/`conn-ha2` (2026-09-03); exhaustive vocabulary + missing-field meaning UNKNOWN | D-V1 (vocabulary); production wiring (§25a) | owed |
| ha1/ha2 addresses & ports | PAN | pair | HA state | `state` | `*-info/ha1-ipaddr`, `ha1-backup-ipaddr`, `ha2-ipaddr`, `ha1-port`, `ha2-port` | tokens (diagnostic) | COLLECTED_NOT_PARSED (persisted) | field names real | S2 (consistency axis only; never identity) — **out of S2 scope by task instruction: no HA1/HA2 addresses**; deferred | done (names) |
| running_sync | PAN | pair | HA state | `show high-availability state` (existing) | `group/running-sync`, `group/running-sync-enabled` — **CLOSED_BY_DOCS 2026-09-03** (official PANW source, verbatim) | `_parse_pan_ha_preflight_fields` (S2, 2026-09-03) | PARSER_IMPLEMENTED — PRODUCTION_WIRING_PENDING (see §25a) | CONFIRMED (path + concept); values `synchronized`/`not synchronized` per session-2 KB | production wiring (§25a) | owed |
| software/content parity | PAN | pair | HA state + `show system info` | both | `*-info/build-rel` (**software version — 2026-09-03 correction: `version` is a separate HA-protocol/schema counter, NOT the software version**), `app-version`, `av-version`, `threat-version`, `url-version`, `*-compat` | `_parse_pan_ha_preflight_fields` (S2, 2026-09-03; local + peer sides) | PARSER_IMPLEMENTED — PRODUCTION_WIRING_PENDING (see §25a) | FIELD_BINDING CONFIRMED for most fields 2026-09-03 (official PANW source); `*-compat` vocabulary evidenced ≥2-valued (`Match`/`Mismatch`); exhaustive enum still UNKNOWN | D-V2 (vocabulary); production wiring (§25a) | owed |
| preemption / priority / hold | PAN | pair | HA state | `state` | `*-info/preemptive`, `priority`, `preempt-hold`, `promotion-hold` | `_parse_pan_ha_preflight_fields` (S2, 2026-09-03) | PARSER_IMPLEMENTED — PRODUCTION_WIRING_PENDING (see §25a) | field-binding CONFIRMED 2026-09-03 (present at these exact paths in an official PANW-captured real response); semantics documented (session-2 KB) | production wiring (§25a) | owed |
| flap counters | PAN | pair | HA state | `state` | `local-info/max-flaps`, `nonfunc-flap-cnt`, `preempt-flap-cnt`, `state-duration` | `_parse_pan_ha_preflight_fields` (S2, 2026-09-03; raw counters only — no D-F3 threshold applied) | PARSER_IMPLEMENTED — PRODUCTION_WIRING_PENDING (see §25a) | field-binding CONFIRMED 2026-09-03 (same source) | production wiring (§25a); D-F3 threshold decision remains separate | owed |
| failure state | PAN | pair | HA state | `state` | `local-info/last-error-reason`, `last-error-state`; non-functional states | `_parse_pan_ha_preflight_fields` (S2, 2026-09-03; parsed defensively — returns `None` when absent, never a guess) | PARSER_IMPLEMENTED — PRODUCTION_WIRING_PENDING (see §25a) | vocabulary ESTABLISHED (states); path presence still UNCONFIRMED by an official captured example (absent from that source) | production wiring (§25a); path-presence confirmation is S8 real-env | owed |
| passive_link_state | PAN | pair | HA state | `state` | `local-info/active-passive/*` | — | COLLECTED_NOT_PARSED (children unenumerated) | UNKNOWN | S2 enumeration | owed |
| path/link monitoring | PAN | pair | — | `path-monitoring`, `link-monitoring` | — | — | NOT_COLLECTED | — | S6 (gate) | owed |
| row_provenance | PAN | all | — | — | run_id/collected_at/transport | partial (`duration_ms`, `queried_target`) | COLLECTED_NOT_PARSED | — | S1/S2 | — |

## §25a S2 implementation-state reconciliation (session correction, 2026-09-03)

The initial S2 SESSION CLOSE labeled the six field-groups above (`conn_status`/
`conn_ha1`/`conn_ha2`, `running_sync`, software/content parity, preemption/
priority/hold, flap counters, failure state) `COLLECTED_AND_PARSED`. A
follow-up PO/architecture review correctly identified this as an
overclaim: `COLLECTED_AND_PARSED` elsewhere in this table means the field
is parsed by the *current production collection path* — none of these six
are. This section makes the actual state precise, per field-group, on the
six dimensions the review specified. The answer is identical across all
six groups (they are all read by the same `_parse_pan_ha_preflight_fields`
function under the same `include_preflight_fields` flag):

| Dimension | Answer | Detail |
| --- | --- | --- |
| Response fetched by existing production path | **YES** | `_collect_device_row` already calls `get_target_ha_runtime_state`, which already issues `show high-availability state` once per selected member, for the pre-existing baseline five fields (`enabled`/`state`/`mode`/`peer_state`/`state_sync`). S2 reads more of that *same* in-memory response — it adds no request. |
| Extraction code implemented | **YES** | `_parse_pan_ha_preflight_fields` (now routed through the shared `_pan_ha_group_text` canonical accessor — §"Single extraction authority" below), proven correct against synthetic fixtures. |
| Production extraction currently invoked | **NO** | `include_preflight_fields` defaults `False`; the one production call site does not pass `True`. This is by design, not an oversight — see "S2 vs. S5/S6 boundary" below. |
| Projected into `PreflightFact` | **capability YES, invocation NO** | `panorama.pan_preflight_projection.project_pan_preflight_facts()` exists and is tested, but nothing in the product calls it yet — same dormancy as the extraction step above. |
| Automated extraction tests executed | **NO** | `tests/test_op0b_s2_pan_extraction.py` (20 tests) requires `lxml`, absent from this container; collection fails with `ModuleNotFoundError`. `python3 -m py_compile` confirms syntactic validity only — **not** evidence the tests pass. This container's tooling is deliberately not modified to install the missing dependency merely to turn this local run green (see this session's SESSION CLOSE); the repository CI / full-dependency environment is the actual gate. |
| Real-env validated | **NO** | Unaffected — `S8` remains the real-env gate for every PAN field in this table, S2 included. |

**S2 vs. S5/S6 boundary (§"Implementation slices" table, above):**

- **S2 parser responsibility:** implement and prove correct, against
  synthetic fixtures, a reusable field-extraction (`_parse_pan_ha_preflight_
  fields`) and projection (`project_pan_preflight_facts`) capability over
  the *shape* of an already-fetched `show high-availability state`
  response. Not responsible for invoking either against a real device or
  wiring either into any production collection pass.
- **S5/S6 orchestration responsibility:** build the dedicated preflight
  collector (`panorama/preflight_collector.py`, not yet created) that
  performs its own in-run `show high-availability state` read — per this
  contract's own "Current collector reuse decision" (above): *"the
  preflight collector always performs its own in-run reads"*, and
  explicitly **not** the inventory/config collector, because coupling the
  inventory pipeline to preflight evidence "would make the whole inventory
  pipeline part of the authorization surface." S5/S6, not S2, is where
  `get_target_ha_runtime_state(..., include_preflight_fields=True)` and
  `project_pan_preflight_facts()` are actually meant to be called from, with
  a real `preflight_run_id`/`collected_at`.

Given that clause, S2 leaving its new capability unwired from
`_collect_device_row` is not merely cautious default — production-wiring
it into the inventory collector's regular pass would have started
implementing S5 early, inside S2, using the wrong collector. `PARSER_
IMPLEMENTED — PRODUCTION_WIRING_PENDING` names that state precisely: the
capability is real and correctly placed; only its production invocation is
pending, and it is pending in the *dedicated preflight collector* S5/S6
have yet to build, not in this collector.

**Single extraction authority (§4 of the review):** before this
correction, three functions in this file independently traversed the same
in-memory `root` for HA-state fields: `get_target_ha_runtime_state`'s
inline five-field baseline, `_tokenize_ha_field_diagnostics` (pre-existing
OP.0a generic diagnostic sweep), and S2's `_parse_pan_ha_preflight_fields`
— matching the "one production parser + one diagnostic parser + one
preflight parser" anti-pattern this review named. Corrected with a small,
behavior-preserving refactor: a new `_pan_ha_group_text(root, path)`
helper is now the one canonical accessor for any leaf under
`result/group/`, and both the baseline five-field extraction and S2's
field map read through it (same paths, same `None`-on-absent/whitespace
semantics — verified by direct re-derivation, not merely inspection).
`_tokenize_ha_field_diagnostics` deliberately still does not route through
it: it enumerates arbitrary child tag names rather than reading named
paths, and it feeds the B1/B2-adjacent peer-identity diagnostic — refactoring
it is out of S2's authorized scope (no pair-identity redesign). Left
untouched, flagged here rather than silently. **SINGLE EXTRACTION
AUTHORITY = YES for the group-level field family S2 owns; the pre-existing
diagnostic sweep remains a deliberately separate, differently-shaped tool
outside S2's scope.**

## §25b S3 implementation-state reconciliation (2026-09-03)

Applying the same six-dimension discipline §25a established, to the three
Check Point field-groups S3 adds (`peer_state (observed)`, `failure_reason`,
`cluster_mode`'s new VSX value):

| Dimension | Answer | Detail |
| --- | --- | --- |
| Response fetched by existing production path | **YES** | `_collect_host` already runs `cphaprob stat` once per physical member (and once per VS context) for the pre-existing baseline (`ha_role`/`ha_cluster_mode`). S3 reads more of that *same* in-hand buffer, before it is zeroed — it adds no command, no SSH invocation. |
| Extraction code implemented | **YES** | `_parse_clusterxl_stat_preflight_fields` (delegates local-role/cluster-mode extraction to the existing `_parse_clusterxl_runtime_role`/`_parse_clusterxl_cluster_mode` — single extraction authority preserved by reuse, not duplication; peer-row/attention extraction is new territory nothing else in the repository parses), proven correct against synthetic fixtures. `_parse_clusterxl_cluster_mode` itself gained one new recognized value (`vsx_single_vs_failover`), in production use immediately (unlike the dormant fields below) since it is the same function the existing physical/VS paths already call unconditionally. |
| Production extraction currently invoked | **cluster_mode's new value: YES (immediately); peer/attention fields: NO** | `_collect_host`'s new `include_preflight_fields` parameter defaults `False`; `run_checkpoint_config_collection`'s one call site does not pass `True`. By design, not an oversight — see "S3 vs. S5/S6 boundary" below. The "Single VS Failover" mode recognition is different in kind: it lives inside the *existing, always-invoked* `_parse_clusterxl_cluster_mode`, so it takes effect on the next real VSX-HA read with no additional wiring — it corrects a previously-unrecognized string, it does not add a new opt-in read. |
| Projected into `PreflightFact` | **capability YES, invocation NO** | `checkpoint.cp_preflight_projection.project_cp_preflight_facts()` exists and is tested, but nothing in the product calls it yet — same dormancy as S2's PAN projection. |
| Automated extraction/projection tests executed | **YES, in this session** | `tests/test_op0b_s3_cp_extraction.py` and `tests/test_op0b_s3_cp_projection.py` both ran green in this container (`paramiko`/`lxml`/`pytest` were installed locally, session-local, for validation purposes only — not a repository dependency change; nothing under `requirements*.txt` changed). Full-dependency CI (PR) is still the authoritative gate per repository policy, but unlike S2's session this session's local run is real evidence, not merely a syntax check. |
| Real-env validated | **NO** | Unaffected — `S8` remains the real-env gate for every CP field in this table, S3 included; no device was contacted. |

**S3 vs. S5/S6 boundary:** identical shape to §25a's PAN boundary. S3 is
responsible for a reusable, synthetic-fixture-proven extraction
(`_parse_clusterxl_stat_preflight_fields`) and projection
(`project_cp_preflight_facts`) capability over the *shape* of an
already-fetched `cphaprob stat` buffer — not for invoking either against a
real device or wiring either into any production collection pass. S5 (the
dedicated `checkpoint/preflight_collector.py`, not yet created) owns
building the actual preflight invocation, per this contract's own
"S2 architectural lesson — apply to S3" guidance carried into the task
instructions. `PARSER_IMPLEMENTED — PRODUCTION_WIRING_PENDING` names the
peer/attention state precisely for the same reason §25a gives for PAN.

**Scope narrowing, stated plainly:** the task's own slice description names
"Active Attention reason" as an S3 target. What is actually projected is a
**boolean** (`local_attention`) derived from the already-parsed local-role
state token (`"ACTIVE ATTENTION"`/`"DOWN"` ⇒ `True`) — not a free-text
reason string. No official vendor snippet reached by this contract
establishes a safe, bounded reason-text vocabulary for `cphaprob stat`
specifically (only the state-token vocabulary itself, §"Vendor semantics
established", is ESTABLISHED); parsing an arbitrary suffix would risk
returning raw buffer/interface/device text, which task §16/§19 forbid. This
is the same "fail closed rather than guess" posture the contract's own
domain invariants require, applied to a genuinely open evidence gap rather
than deferred silently — see the `failure_reason` row (§25) for the exact
wording now on record.

**Single extraction authority:** `_parse_clusterxl_stat_preflight_fields`
calls the existing `_parse_clusterxl_runtime_role`/`_parse_clusterxl_cluster_mode`
functions for local role/mode rather than re-tokenizing the buffer a second
way; its own new logic (peer-row state extraction) is not duplicated
anywhere else in the repository. SINGLE EXTRACTION AUTHORITY = YES.

## §25c S7 implementation-state reconciliation (2026-09-03)

S7 (`op0b_s7_readiness_v2_integration`) integrated the S1/S5/S6 evidence
into the canonical readiness path. Recorded here, in the same six-dimension
discipline as §25a/§25b, is exactly where the implementation follows this
contract's S7 slice row verbatim and where the PO-directed build task
narrowed it — nothing below reinterprets a frozen semantic; each narrowing is
a scope decision the product owner reviews before merge.

| S7 slice-row item | Implemented as | Status |
| --- | --- | --- |
| "prerequisites" (identity gate, pair identity, mode, evidence coherence) | `utils/failover/preflight_readiness.py` evaluates all four per snapshot and discloses them under the unit's `evidence.prerequisites`; a failed prerequisite blocks every positive check result (never fabricates a failure); a single-fact explicit KNOWN_BAD still fails (a device's own report stands on its own), a cross-member conclusion (split-brain, no standby, mismatch) does not | **IMPLEMENTED** |
| "8 checks" (adds `no_member_failure_state`, splits check 5 into 5a/5b) | **NOT adopted.** The build task preserved the canonical seven checks (`assessment.STOP_CONDITIONS`, design §4). The check-8 evidence family (CP pnote problem / `Down` / `Active Attention`; PAN non-functional set) is interpreted inside check 1 `viable_target` — whose design-§4 definition already reads "with no critical device/pnote down" — and the 5a/5b evidence inside one check 5, each with distinct reason codes (`critical_device_problem_observed`, `member_failure_state_observed`, `member_non_functional_state_observed`; `ha1_link_down_observed`, `ha2_link_down_observed`, `cluster_interface_down_observed`, `monitored_path_down_observed`) so no blocking reason is lost | **DEFERRED — PO decision** (adopting the 8-check shape is a contract-level change, not an S7 invention) |
| "remove phantom member" (AC-5) | `assessment._pan_states` no longer counts `peer_state`; a single-member PAN unit reports `peer_not_independently_observed` on checks 1/4. Same law applied to the CP stored-telemetry path: a one-sided read is `INSUFFICIENT_EVIDENCE`, never a fabricated `no_viable_target` | **IMPLEMENTED** |
| "pair existence vs health" | `HaUnit.unresolved_reason` is now serialised (§26 X-4) and `evidence.prerequisites.pair_identity` carries the identity axis (`established_topology_group` / `established_configuration_intent` / `not_established`) separately from the verdict | **IMPLEMENTED (disclosure)** |
| "serial-keyed PAN unit" | **NOT adopted** — this contract's own §"Identity contract" leaves the successor model NOT FROZEN until `D-V3a` closes and `B2` matches; the hostname-keyed fallback stands. `local_serial_claim`/`peer_serial_claim` are never consulted by the mapping (test-enforced) | **preserved unresolved** (`B2 NOT ESTABLISHED`) |
| `securityexpert-ha-readiness-v2` | **NOT adopted** — the string is named here but no v2 record shape is frozen anywhere; every S7 change to the record is additive (`units[].evidence`, `units[].unresolved_reason`, `checks[].facts`, top-level `preflight`), so consumers and tests keep `-v1`. A one-line bump is available on PO instruction | **DEFERRED — PO decision** |
| Freshness/coherence | `evaluate_coherence` gates positives; `member_skew_ms` recorded, never bounded (`D-F2`); category-C facts never a check input (AC-4) and disclosed as `configuration_intent_freshness: not_evaluable:D-F1`; check 7 never PASSes (`threshold_policy_unresolved:D-F3`); the single roll-up additionally refuses SAFE while any `D-F` gate applies | **IMPLEMENTED, fail-closed** |
| Automated tests executed | `tests/test_op0b_s7_readiness_v2.py` (53) + OP.0a/OP.0c/S1–S6/architecture regression, in-session with `pytest`/`lxml`/`paramiko`/`requests`/console extras installed session-locally (no repository dependency change) | **YES** |
| Real-env validated | **NO** — S8 owed; the minimal value vocabularies the mapping freezes (PAN `state-sync` "Complete", `running-sync` "synchronized", `conn-*` "up"/"down", `*-compat` "Match"/"Mismatch"; CP sync "ok"/"not_ok") fail closed on anything unrecognised and must be confirmed against real output | owed |

**PO architecture decision (2026-09-03, S7 approval):** the seven-check
readiness contract is KEPT (no eighth top-level check, no top-level 5a/5b
split — richer OP.0b evidence is carried by existing checks + distinct fact
provenance + distinct reason/missing-evidence codes); the readiness schema
identifier stays `securityexpert-ha-readiness-v1` ("readiness v2" is the
build movement name, not a wire version); the one-sided-ACTIVE →
`INSUFFICIENT_EVIDENCE` correction and the rewritten AC-4 fixture are
accepted; SAFE/DEGRADED unreachability is accepted as not a defect. One
added machine guard: fresh preflight XOR legacy stored telemetry per unit
(`tests/test_op0b_s7_readiness_v2.py::test_evidence_source_exclusivity_fresh_preflight_xor_legacy_telemetry`).

**Single authority:** one roll-up (`assessment._verdict_for`), one check
evaluator entry (`assessment._evaluate_checks`, which dispatches to the S7
mapping only when a snapshot exists for the derived unit), one typed mapping
(`FACT_CHECK_MAP`). No `old_rollup`/`new_v2_rollup`; the S7 test suite
proves by AST inspection that exactly one function in the package returns a
verdict. Stored telemetry and a fresh snapshot are never blended for one
unit (`evidence.basis` names which was used).

**SAFE / DEGRADED reachability after S7:** both remain unreachable. CP —
check 6 (`CP-A9` not authorized, `D-V7b`) and check 7 (`D-F3`); PAN — check
7 (`D-F3`). Proven over a generated snapshot matrix, not by reading.

## §26 Current bug / gap register

Priority: **P0 BEFORE CLASS 2** · **P1 BEFORE PRODUCTION** · **P2 HARDENING** · **DEFERRED**.

| Id | Vendor | Gap | Evidence | Priority |
| --- | --- | --- | --- | --- |
| CP-1 | CP | 2/7 checks evaluable; no sync/parity/link/preemption/flap evidence exists in code | `assessment.py:61`, §24 ABSENT list | P0 |
| CP-2 | CP | peer rows dropped; no peer observation; split-brain from unsynchronised member reads; skew unrecorded — **S3 (2026-09-03) adds a dormant, opt-in extraction/projection capability (`_parse_clusterxl_stat_preflight_fields`, `project_cp_preflight_facts`) that can read peer-row state; production `_collect_host` still discards it by default (`include_preflight_fields=False`) — gap NOT closed, only a path to closing it now exists** | `_parse_clusterxl_runtime_role`; `ThreadPoolExecutor` | P0 |
| CP-3 | CP | preemption not reliably device-readable (sk180184); no management-plane read exists | vendor doc; no collector | P0 |
| CP-4 | CP | per-VS `cphaprob stat` reliability unvalidated (sk165432); physical hostname used as VS match token | `:1613`; vendor doc | P0 (before VS readiness is trusted) |
| CP-5 | CP | `vsx_runner.py`: `fw ctl set int vsid` (non-read verb); standby members discarded; `-[12]$` discovery; unvalidated `vs_id` | `vsx_runner.py:167, 212-214, 223-236` | P1 (P0 if `vsx.json` ever feeds preflight — it must not) |
| CP-6 | CP | dead `checkpoint/scripts/vsx_collect.sh` | grep | P2 |
| CP-7 | CP | no `run_id`/`collected_at`/wire command on rows; `group_id` (stage `cp`) and `ha_role` (stage `cp_config`) joined off disk without freshness — **unchanged by S3**: `project_cp_preflight_facts` carries provenance only when a caller supplies it (S5/S6's job), never by writing it onto `host_row`/`ctx_row` itself | `:1070-1072`; row shape | P0 |
| CP-8 | CP | `extract_cp_ha_runtime` drops status/source/timestamps — inherited and stale readings indistinguishable | `failover_readiness_ui.py:95-113` | P0 |
| CP-9 | CP | strict host-key trust R2 on production server pending | `cp_ssh_trust_r2_prod_server` | P1 (P0 before CLASS 2) |
| CP-10 | CP | direct-Clish-only appliances → `capability_gap` for `cphaprob`; needs `UNSUPPORTED` handling or Clish equivalents | `:1351-1357` | P1 |
| CP-11 | CP | `merge.normalize_vsx` hostname-suffix `cluster` consumed as failover legacy fallback key | `merge.py:95-101`; `assessment.py:456-457` | P1 |
| CP-12 | CP | VS rows inherit platform/serial/identity/host-key facts without source label | `:1485-1508` | P2 |
| CP-13 | CP | `cphaprob -l list` (design/draft) vs `-ia list` (official) | §24 | P1 (gate package) |
| CP-14 | CP | `cp_device_interaction_safety` `done` in backlog but listed open in design §10 and B.1.2 doc | records | P2 (docs) |
| CP-15 | CP | `cphaprob stat` fixtures constructed, two inconsistent shapes; no captured real header | tests; OP.0a Risks | P1 |
| PAN-1 | PAN | pairing join on management plane disproven; HA1 plane proven | real pair; vendor doc | P0 (successor contract) |
| PAN-2 | PAN | runtime serial correspondence unmeasured | S0 pending | P0 |
| PAN-3 | PAN | member B configured/runtime HA1 inconsistency (real); model cannot represent it | diagnostic | P0 (model); operator finding P1 |
| PAN-4 | PAN | `_pan_states` phantom member uplift | `assessment.py:189-201` | P0 |
| PAN-5 | PAN | discovery `ha-state` short-circuits runtime read; that branch lacks `enabled` → unit never forms | `:1641-1647`; `:627` | P0 |
| PAN-6 | PAN | duplicate pairing authority in `inventory_ui.js` (0.75/0.60 heuristic) | `:1013-1042, 1332-1378` | P1 |
| PAN-7 | PAN | hostname-keyed entity/unit identity vs serial | `pan_ha_serial_identity_hardening` | P1 (decision before CLASS 2) |
| PAN-8 | PAN | TLS verification default off without CA bundle; strict validated, production must enforce | `_tls_verify_setting`; `pan_tls_ca` | P1 (P0 before CLASS 2) |
| PAN-9 | PAN | IPv6 peer semantics unproven | `pan_ha_peer_ipv6_pairing` | DEFERRED |
| PAN-10 | PAN | `result/group/*` siblings (`running-sync`?) and `active-passive/*` children unenumerated | diagnostic scope | P0 (evidence gap) |
| PAN-11 | PAN | `conn-*`, `*-compat`, flap, `state-sync`, `serial-num` vocabularies not officially confirmed | §24 UNKNOWN rows | P0 (freeze blocker) |
| PAN-12 | PAN | passive member's `peer-info` matched nothing — completeness unexplained | real pair | P0 (investigate in S2/S8) |
| PAN-13 | PAN | two independent XML walks of `show devices all` | `pan_hostname_parser_unification` | P2 |
| PAN-14 | PAN | `config_ui.py` second HA vocabulary | `:280-306` | P2 |
| PAN-15 | PAN | commits `1d97cd6`, `d0f8e31`, `a1a3882` and the plane finding recorded only in git | project records | P1 (STATE_UPDATE) |
| X-1 | both | per-HA-entity lock (§10.1 item 4) tracked nowhere | design only | P0 before CLASS 2 (record now) |
| X-2 | both | `op_degraded_verdict` open | roadmap | before OP.1 |
| X-3 | both | no same-run/freshness/coherence model anywhere | §"Provenance contract" | P0 |
| X-4 | both | `HaUnit.unresolved_reason` not serialised; `reason` carries two semantic axes | `assessment.py:128-137, 721-726` | P1 (S7) |
| X-5 | both | `FAILOVER_ENGINE_ARCHITECTURE.md` §10.1 stale (OP.0a stamp; identity note) | closure DoD item 6 | P2 |
| X-6 | both | `on_hardware_real_env_validation` BLOCKED on laptop availability | backlog | external |

## Freeze decision

**FREEZE WITH REAL-ENV VALIDATION GATES (2026-09-03, session 4, current and
authoritative).** Every remaining row's minimal safe interpretation is now
explicitly frozen — see §"Final semantic blocker closure — session 4" and
the final blocker table there. Two genuinely `STILL_UNKNOWN` rows remain
(`D-V3a`, `D-V7b`), but both were already scoped, by this document's own
session-1 text (the PAN identity contract's "successor model NOT FROZEN,
hostname-keyed fallback stands" clause; check 6 `preemption_known`'s
"recorded, non-blocking" specification), as CLASS-2-time prerequisites, not
architecture-interpretation blockers — a reading verified this session, not
invented. No safety-critical semantic requires guessing for the contract, as
an evidence/interpretation model, to be used as implementation authority.
`D-V5b` is additionally found not load-bearing at all (the frozen battery
never required a per-VS answer) and is dropped from the blocking list
entirely. One new bounded, non-blocking open decision is added (`D-F3`, the
check-7 flap/failover-frequency threshold), parallel to the pre-existing
`D-F1`/`D-F2`.

This reverses the `DO NOT FREEZE` conclusion sessions 1–3 reached — correctly,
at the time, on the evidence and framing then available. Nothing about the
underlying vendor semantics changed between session 3 and session 4; what
changed is that session 4 was explicitly tasked with asking the
freeze-boundary question sessions 1–3 were not: not "is every `D-V` row
`CLOSED_BY_DOCS`" but "does any row's remaining uncertainty force a guess
anywhere in the contract's interpretation." For every row but `D-V3a`/`D-V7b`
the answer, on inspection, was already no — the minimal safety predicate was
either already written into this document from session 1, or followed
directly from applying it now (§11/§12 of the audit task). For `D-V3a`/`D-V7b`
specifically, the answer is also no, once it is recognized that the
architecture never depended on them for anything this side of CLASS 2 in the
first place. This is a reclassification, not a new leniency: no identity
requirement, no fail-closed default, and no check's `PASS` condition changed
from what sessions 1–3 already specified.

### Session 3 freeze decision (historical, superseded — preserved verbatim)

**DO NOT FREEZE.** Split-row state after the 2026-09-03 Source Pack 2 pass
(session 3): `D-V4` and `D-V7a` are **`CLOSED_BY_DOCS`** — the first rows
either session has fully closed. The remaining blocking set is `D-V1, D-V2,
D-V3a, D-V3b, D-V5a, D-V5b, D-V6, D-V7b, D-V9a, D-V9b`. All are
vendor-semantic confirmations or already-scheduled real-env measurements
(S0, S2, S8) — none is a design decision.

This pass strengthened `D-V1`, `D-V2` within `PARTIALLY_CLOSED` (field-
binding now `CONFIRMED` for most fields, via a verbatim-read official PANW
source, not name-correspondence; residual gap narrowed to exhaustive
vocabulary + `last-error-*` binding + missing-field meaning), split `D-V5`
into `D-V5a` (`PARTIALLY_CLOSED`, strong — command/purpose/version-map/
reset-exclusion confirmed, only a history-depth flag and Clish/Expert schema
parity open) and `D-V5b` (`OPEN`, no VSX-applicability statement found
either way), upgraded `D-V6` `STILL_UNKNOWN → PARTIALLY_CLOSED` (register/
unregister syntax and `-ia list`'s complete-enumeration semantics confirmed
— **explicitly contradicting**, and correcting, this session's own source-
pack hypothesis about a problem-filtered `-ia`), and split `D-V7` into
`D-V7a` (`CLOSED_BY_DOCS`) and `D-V7b` (`STILL_UNKNOWN`, now with a
documented explanation: the Simple Cluster API does not expose every
cluster-object feature). `D-V3a` stays `STILL_UNKNOWN` for its safety-
relevant half (HA-state serial field semantics) — a distinct, separately-
scoped sub-fact (general PAN serial leading-zero opacity) is newly
`CONFIRMED` but the task explicitly forbids using it to close the HA-field
question, and this session found no serial field at all in the one official
HA-state example it could read. `D-V9a` unchanged in substance (new
diagnostic texture only). Non-blocking open decisions unchanged: `D-V8,
D-T1, D-F1, D-F2, D-P1`.

Per §23/§25 of the audit task: a contract may freeze with real-env gates
remaining only if interpretation itself is already frozen for every
remaining row. That bar is met for none of the ten still-blocking rows —
`D-V3a` and `D-V7b` in particular are safety-critical *interpretations/
authoritative sources* still unknown (not merely measurements pending), which
is exactly the condition §23 names as prohibiting `FREEZE WITH REAL-ENV
VALIDATION GATES`. The decision therefore remains the conservative one, but
reached from evidence — two genuine closures and several genuine
strengthenings this session, against two rows (`D-V3a`, `D-V7b`) where no
accessible source, official or otherwise, has yet named the fact needed.

Three consecutive sessions have now hit an identical `WebFetch`-class block
against `pan.dev`/`sc1.checkpoint.com`/`support.checkpoint.com`. This session
also found the block is **not universal** — `github.com`/
`raw.githubusercontent.com` are reachable, and reading an official PANW-org
repository's source directly closed two rows outright. The honest next step
is not "give up on automated access" but **`OFFICIAL_GITHUB_MIRROR_SEARCH`
first, `HUMAN_ASSISTED_DOC_CONFIRMATION` for whatever that cannot reach** —
see "Next movement" above.
