# PO decision record: VSX chassis failover (2026-11-19)

**Status:** RATIFIED -- Product Owner answers of 2026-11-19 (§6 option "A ve B birlikte"; §7 "onaylıyorum" to the two
exact literals). Prepared at the PO's request ("Evet, hazırla", 2026-11-19 evening) after the HA & readiness screen showed
no failover button on a VSX chassis unit. The FROZEN `FAILOVER_EXECUTION_CP_CONTRACT.md` §10 is amended by its §17,
which points here. Implementation: §8.

## 1. Today
- `FAILOVER_EXECUTION_CP_CONTRACT.md` §10 (FROZEN): "The VSX chassis itself is not failed over by this feature."
- PO 2026-10-23 ("Önce readiness, sonra karar"): chassis readiness only; chassis failover refused everywhere with
  `VSX_CHASSIS_FAILOVER_NOT_APPROVED` (service, API, worker); the UI shows the refusal, no button
  (`VSX_CHASSIS_FAILOVER_UNIT_2026_10_23.md` §4, §5).
- Per-VS failover exists (`*_vsid` rows incl. `cp_failover_down_vsid` / `cp_failover_up_vsid`); its last missing read,
  `cp_policy_install_cpstat_vsid`, is V254 (PO-approved 2026-11-19, ships after the 2026-11-20 presentation).

## 2. Vendor semantics (evidence)
- Official R81.20 CLI reference, `clusterXL_admin`: `down` registers the critical device `admin_down` as problem and
  "gracefully changes the state of the Cluster Member to DOWN"; `up` reverses it; not persistent across reboot unless
  `-p`. **It says nothing about VSX, VS0 or VSLS.**
- CheckMates threads (user-contributed, citing sk95133): in **VSLS** mode, `clusterXL_admin down` in VS0 does **not**
  move the other virtual systems that are active on that member; the controlled way to move VSs is `vsx_util vsls` on
  the management server, or per-VS `clusterXL_admin down/up` inside each VS context. In VSX **HA** mode (per-VS failover
  disabled) the member fails over as a whole.
- Conclusion: for a VSLS cluster the effect of the VS0 literal on the VSs is **UNKNOWN** from official documentation and
  contradicted by community evidence. It must not be approved as "chassis failover" for VSLS on this basis.

## 3. Options
- **A. Chassis = all virtual systems in sequence (recommended for VSLS).** One chassis action that runs the existing,
  already-approved per-VS failover for every VS of the chassis, one VS at a time, each with its own pre-checks,
  switch, post-checks and return, stopping at the first failure. No new device command. Needs: the contract §10
  amendment, a chassis run that orchestrates VS runs, UI button "Fail over all virtual systems".
- **B. VS0 member down/up (VSX HA mode only).** New CLASS_2 literals `bash -lc 'vsenv 0 && clusterXL_admin down'` and
  `bash -lc 'vsenv 0 && clusterXL_admin up'`, allowed only when the chassis reports VSX **HA** mode (not VSLS); refused
  with `VSX_CHASSIS_MODE_UNSUPPORTED` for VSLS. Needs official confirmation (sk95133) of the HA-mode effect first.
- **C. Management-side `vsx_util vsls`.** Moves VSs by changing VSLS priorities from the management server. Different
  target (management, not gateway), interactive tool, new transport and command class. Not recommended now.
- Not proposed: `cphastop` on a member (stops clustering entirely; too blunt).

## 4. Preconditions common to A and B
Chassis readiness READY and every VS READY at start; no open run on any step of the cluster (existing step fence);
fleet one-mutation fence; business-hours confirmation as today; incident and quarantine scope unchanged.

## 5. Questions for the PO
1. Which option: A (recommended), B, or both (A for VSLS, B for HA mode)?
2. If B: approve the two exact literals in §3-B, after sk95133 is read and quoted here.
3. Amend `FAILOVER_EXECUTION_CP_CONTRACT.md` §10 accordingly.

## 6. PO answers (2026-11-19 ~22:40)
1. Option: **"A ve B birlikte"** -- A (all virtual systems in sequence) for VSLS chassis units, B (VS0 member down/up)
   for VSX HA-mode chassis units.
2. B literals: **not yet approved.** sk95133's public part only states the symptom ("Virtual Systems do not become
   Active on the 'Active' cluster member of VSX cluster running in VSLS mode"); the solution needs a Check Point
   Support Center login. The PO (or someone with a support login) reads it; then the two exact literals are put to the
   PO for approval. Until then B stays refused (`VSX_CHASSIS_COMMANDS_NOT_APPROVED`) and A can be built alone.
3. §10 amendment of `FAILOVER_EXECUTION_CP_CONTRACT.md`: to be written with the implementation, after the 2026-11-20
   presentation.

## 7. PO approval of the chassis literals (2026-11-19 ~22:50)
The PO stated the semantics from field experience: `clusterXL_admin down/up` in `vsenv 0` fails over the chassis;
the same inside `vsenv <VSID>` fails over that virtual system. Evidence source recorded as **PO operational experience**;
official documentation is silent on VSX and a community note (citing sk95133) reports that in VSLS the VS0 down does
not move the other VSs -- recorded as a risk, not a blocker. The PO approved ("onaylıyorum") the exact literals:
- `bash -lc 'vsenv 0 && clusterXL_admin down'` (CLASS_2 operational-state-change)
- `bash -lc 'vsenv 0 && clusterXL_admin up'` (CLASS_2 operational-state-change)
Mitigation in the build: after the switch, the chassis post-check verifies on every VS that the active member moved;
if any VS did not move, the run stops and offers return, never reports success.
Plan after the 2026-11-20 presentation: gate rows (next free V number), fixture, lift
`VSX_CHASSIS_FAILOVER_NOT_APPROVED` for B, option A orchestration for VSLS, FROZEN CP contract §10 amendment, tests.

## 8. Ratification and implementation (2026-11-19, lane/vsx-chassis-failover-1119)
Ratified with the answers of §6 and §7; §6 item 2 ("not yet approved") is superseded by §7. What is built:
- **Mode decides the path.** The chassis mode is the `Cluster Mode:` label of the VS0 `cphaprob stat` (check 1) on both
  members: `High Availability` = VSX HA mode -> option B; `Virtual System Load Sharing` = VSLS -> option A. Anything
  else, or the two members disagreeing, is `VSX_CHASSIS_MODE_UNKNOWN` and refused. The service reads the mode from the
  chassis unit's latest readiness run; the worker re-reads it in its own pre-checks and stops before any write when it is
  not HA (`VSX_CHASSIS_MODE_VSLS_USE_SEQUENCE` for VSLS).
- **Option B (VSX HA mode).** V255 seeds `cp_failover_down_vsenv0` / `cp_failover_up_vsenv0` (CLASS_2
  operational-state-change, SIGNED_OFF) for exactly the two literals of §7. The chassis run reads only in VS0 (the V177
  `_vsenv0` rows and the two inventory VS0 rows), plus the per-VS `cphaprob stat` (`cp_inventory_vsid_cphaprob_stat`)
  for the §7 mitigation: before the switch every VS must be active on the chassis's active member; after the switch every
  VS must be active on the former standby (check 16 "Virtual systems moved", polled every 3 s for at most 10 rounds);
  after the return every VS must still be active on the former standby with the former active back as STANDBY (the
  return restores two members with roles swapped, as for any unit). A VS that did not move stops the run (`VSX_VIRTUAL_SYSTEM_NOT_MOVED`, resp.
  `VSX_VIRTUAL_SYSTEM_NOT_RETURNED`); the run is never reported as succeeded and the existing resume of the return step
  (`CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md`) is offered, which for a chassis run also writes only in VS0.
- **Option A (VSLS).** The chassis action "Fail over all virtual systems": one approval request on the chassis unit
  (operation `FAILOVER_ALL_VIRTUAL_SYSTEMS`), then one ordinary per-VS failover run per VS in VSID order, each with its
  own pre-checks, switch, post-checks and return, its own run record and its own approval row derived from the chassis
  approval (`parent_approval_id`, same window, reason, initiator and approver). The sequence (`failover_sequence`, V256)
  starts the next VS only after the previous run ended SUCCEEDED and stops at the first run that did not, or at the first
  refused start (step fence, fleet one-mutation fence, incident, window). No new device command and no new worker mode.
- **Preconditions (§4).** A chassis failover or sequence is refused unless the chassis unit's latest readiness is READY
  (`VSX_CHASSIS_READINESS_NOT_READY`) and every VS unit's latest readiness is READY (`VIRTUAL_SYSTEM_NOT_READY`).
