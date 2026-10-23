# VSX chassis failover unit

**Status:** DRAFT -- engineering findings and design for the chassis step of a Check Point VSX cluster
(lane `lane/vsx-chassis-unit`, 2026-10-23). It approves no device command. The chassis readiness reads and
the chassis failover writes listed in §4 wait for the Product Owner's exact-command approval. §5 lists a
contradiction with a FROZEN contract that the Product Owner has to resolve.

## 1. The PO direction this implements

PO 2026-10-23 (repeating 2026-09-27): "VSX şasi ve VS cluster'ları ayrı ayrı değerlendirmen gerekiyor. Küme
görüntüsü korunabilir, ama her biri ayrı ayrı failover için değerlendirilmeli." The VSX chassis and each Virtual
System are evaluated as separate units. The cluster view stays, but each unit is evaluated for failover on
its own. `FAILOVER_READINESS_CHECKS_CP_PAN_DRAFT.md`, PO decisions 2026-09-27 (second answer) item 3: a VS
failover is a separate step from the chassis (physical member) failover. Per-VS readiness (CP-08) belongs to
the VS step, and CP-01..CP-07 belong to the chassis step.

## 2. Findings: why the chassis row had no unit

- `CpFailoverService.namedUnits` returned **either** one unit per common VSID (when the two members share VS
  contexts) **or** one plain unit (`unitId == clusterId`, `vsId == null`) when they share none.
  `resolveUnits`/`clusterSummaries` refuse a VSX cluster with no common VSID (`VSX_CONTEXT_INCOMPLETE`). So a
  VSX cluster never had a `vsId == null` unit. The HA & readiness list builds the parent row from
  `units.find(unitId === clusterId)`. On a VSX cluster that unit never existed, so the row showed UNKNOWN,
  "Insufficient evidence" and "Not evaluated", with no checkbox and no run button.
  This followed the FROZEN `FAILOVER_EXECUTION_CP_CONTRACT.md` §10 (2026-09-27): "The VSX chassis itself is
  not failed over by this feature". §10 left the chassis without a unit, even for readiness.
- Worker: `CpFailoverJobExecutor` knows two contexts. With `vsId == null` it issues the bare read in a login shell
  (`bash -lc '<read>'`, gate keys `cphaprob stat`, `cp_failover_*`). With a VSID it uses `bash -lc 'vsenv <VSID> &&
  <read>'` (gate keys `cp_*_vsid`). The two contexts run the same battery (contract checks 1-3, 5, 6, 8-15). For a
  VS unit every read runs in its VS context. This is the VS step: per-VS state, CP-08, and the rest of the per-VS
  battery.
- Inventory issues VS0 reads on a VSX host as `bash -lc 'vsenv 0 && <read>'` (CF-2,
  `CP_INVENTORY_COMMAND_GATE_ENTRIES.md` entries 7-11: "so a fresh session's landing context is never
  assumed"). It stores the VS0 facts under context `physical`. The repository has therefore already decided
  that a bare read on a VSX host is not a VS0 read. The chassis step must use the `vsenv 0` form.

## 3. Gate status of the chassis literals (checked against `gate_registry_fixture.yaml`)

| Read / write in VS0 | Exact literal | Gate row | Status |
| --- | --- | --- | --- |
| Check 1 cluster state | `bash -lc 'vsenv 0 && cphaprob stat'` | `cp_inventory_cphaprob_stat_vsenv0` | SIGNED_OFF for **inventory** ("once per device per run"); failover-readiness purpose/frequency not approved |
| Check 3 interfaces | `bash -lc 'vsenv 0 && cphaprob -a if'` | `cp_inventory_cphaprob_a_if_vsenv0` | as above |
| Check 2 IP table | `bash -lc 'vsenv 0 && cphaprob tablestat'` | none | **needs PO approval** |
| Check 5 ARP | `bash -lc 'vsenv 0 && arp -an'` | none | **needs PO approval** |
| Check 8 traffic | `bash -lc 'vsenv 0 && cat /proc/net/dev'` | none | **needs PO approval** |
| Check 9 sync | `bash -lc 'vsenv 0 && cphaprob syncstat'` | none | **needs PO approval** |
| Check 10 policy | `bash -lc 'vsenv 0 && fw stat'` | none | **needs PO approval** |
| Check 11 pnotes | `bash -lc 'vsenv 0 && cphaprob -ia list'` | none | **needs PO approval** |
| Check 12 bonds | `bash -lc 'vsenv 0 && cphaprob show_bond'`, `bash -lc 'vsenv 0 && cphaprob show_bond <BOND>'` | none | **needs PO approval** |
| Check 13 last failover | `bash -lc 'vsenv 0 && cphaprob show_failover'` | none | **needs PO approval** |
| Check 14 routing | `bash -lc 'vsenv 0 && cpstat os -f routing'` | none | **needs PO approval** |
| Execution policy read | `bash -lc 'vsenv 0 && cpstat -f policy fw'` | none | **needs PO approval** (execution only) |
| Chassis failover write | `clusterXL_admin down` / `up` in VS0 (exact form open, see §5) | none for VS0; `cp_failover_down`/`up` are the bare non-VSX rows, and `cp_failover_down_vsid`/`up_vsid` are the per-VS rows | **needs a contract amendment and PO approval** |

The one-shell framing row (`cp_failover_shell_framing`) defines `<READ>` as the bare read, or as `vsenv <VSID> &&
<command>` in a VS run. It does not name `vsenv 0`. The chassis step therefore always uses the SSH_EXEC path. The
one-shell switch is ignored for it.

Conclusion: the chassis literals are **not** gated today. Following the lane brief, nothing is added to the
gate registry, and the chassis failover stays refused. Chassis readiness is implemented, but it is fenced on
the gate registry. Until the rows below are SIGNED_OFF, a chassis readiness run stops before any device
contact. It is recorded as `UNKNOWN` with the stop code `VSX_CHASSIS_COMMANDS_NOT_APPROVED`, and nothing is
sent. Once a migration seeds the rows, chassis readiness works with no code change.

**Proposed rows for the PO** (each a CLASS_0 read, `cp_gaia_gateway`, Expert, SSH_EXEC, 30 s, no retry, "once pre,
once post-switch and once post-return per member per failover run; once per readiness run", one SSH session per
member per run, serial): `cp_failover_tablestat_vsenv0`, `cp_failover_arp_vsenv0`, `cp_failover_traffic_vsenv0`,
`cp_failover_syncstat_vsenv0`, `cp_failover_fw_stat_vsenv0`, `cp_failover_pnotes_vsenv0`, `cp_failover_bonds_vsenv0`,
`cp_failover_bond_members_vsenv0`, `cp_failover_last_event_vsenv0`, `cp_failover_routing_vsenv0`, with the literals
in the table above. The two inventory VS0 rows also need their `max_frequency` amended to cover the failover
readiness reads, in the same way `cp_inventory_cphaprob_stat` was amended for the plain form. The expected gate
ids are fixed in `CpFailoverJobExecutor.chassisGateId`. A migration only has to seed them once they are approved.
This lane adds no migration; V177 was reserved for it and is not used.

## 4. Design

**Unit identity.** One chassis unit per VSX cluster: `unitId == clusterId == opaque(clusterRef)`, `vsId == null`,
label = cluster reference. This is the same identity a plain ClusterXL unit has. Readiness storage keys
`(vendor, cluster_ref, vs_id null)`, incident view ids (`IncidentView`: `vsId == null` maps to `opaque(clusterRef)`),
approvals and schedules all already key on it, so no migration is needed. The chassis unit is listed first, then
the VS units in VSID order, which are unchanged. `Unit` carries `vsxChassis = true`, and the API exposes
`unitKind` = `VSX_CHASSIS` | `VIRTUAL_SYSTEM` | `CLUSTER`. The masked view uses the cluster pseudonym for the
chassis unit, as it does for a plain cluster.

**Readiness of the chassis.** The worker decides "chassis of a VSX cluster" from the same evidence the service
uses. That evidence is a member's virtual-system names, or a stored inventory context other than `physical`, on
either member. When it holds, the run reads in context VS0. Every read is
`bash -lc 'vsenv 0 && <read>'`, resolved against the VS0 gate rows of §3. The VS0 context preamble
(`... (ID 0).`) is the only one stripped. A `cphaprob stat` reporting any other context is UNKNOWN. The VS table at
the end of the VS0 `cphaprob stat` is not parsed into the chassis state. The battery is the chassis one
(CP-01..CP-07 in the draft's naming): the contract's checks 1-3, 5, 6, 8-15 on the physical members. No
`vsenv <VSID>` read and no per-VS state (CP-08) is issued. Check rows are stored with `vs_id null`. Every gate,
including the bond-member read, is resolved before the first connection. A missing gate therefore stops the run
with nothing sent.

**Chassis failover.** Refused everywhere with `VSX_CHASSIS_FAILOVER_NOT_APPROVED`:
- in the service, for approval requests, second approvals and run requests;
- in the API, where `canApprove`, `canStart` and `canSchedule` are false and `failoverRefusal` carries the code;
- in the worker, before any admission or contact, if a chassis FAILOVER run ever reached it.

A resume can only follow a FAILOVER run, so none can exist for the chassis. The UI shows no Start failover button
on the chassis unit. It shows the refusal instead.

**Chassis and VS steps never overlap.** The repository adds a step fence under the existing admission lock (136):
- readiness requested on one step is refused while a FAILOVER run of the other step of the same cluster is
  open, including a PLANNED one. The code is `VS_RUN_ACTIVE` when the requested step is the chassis, and
  `CHASSIS_RUN_ACTIVE` when it is a VS. This mirrors today's rule that a unit's readiness is refused while its own
  run is open.
- an immediate failover request, or the scheduled start of a PLANNED run, is refused while any run of the other
  step is open. The scheduled start is then stopped with that code, as existing start refusals are.

VS-to-VS failovers keep the fleet-wide one-mutation fence (`FLEET_MUTATION_ACTIVE`). Incidents and quarantine keep
their cluster-and-member scope, so an incident on any VS run blocks the chassis and every VS of that cluster, and
the reverse.

**UI.** In HA & readiness, the VSX parent row is the chassis unit: its own status, reason, evaluated-at, checkbox
and run button, plus a "VSX chassis" caption. The VS rows stay nested under it with their own status and actions.
The fleet chips count units: each plain cluster, each VSX chassis and each VS counts once, and the chip tooltip
says so. The Overview KPI keeps counting clusters. The inventory Failover tab lists the chassis unit first, then the
VS units.

## 5. Open for the Product Owner

1. **Contradiction with a FROZEN contract (not reconciled here).** `FAILOVER_EXECUTION_CP_CONTRACT.md` §10 (FROZEN):
   "The VSX chassis itself is not failed over by this feature." The 2026-10-23 direction says chassis and VS are
   each to be evaluated for failover. This lane implements evaluation (readiness) only. A chassis **failover**
   needs a §10 amendment.
2. **Exact chassis write and its vendor semantics.** The literal is open: `bash -lc 'vsenv 0 && clusterXL_admin
   down'`, or the bare form, which the repository forbids on a VSX host (CF-2). The vendor effect is also open:
   what `clusterXL_admin down` in VS0 does to every VS on that member (all VSs leaving the member). Both need official
   documentation and PO approval of the exact literal before any write gate row exists.
3. **The VS0 read rows of §3** (ten new rows and two frequency amendments), each approved as written.
