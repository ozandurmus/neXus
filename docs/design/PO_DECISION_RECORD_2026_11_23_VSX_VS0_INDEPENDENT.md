# PO decision record: VSX -- VS0 is its own failover unit, independent of every VS (2026-11-23)

**Status:** RATIFIED -- Product Owner, chat 2026-11-23, authorization ref `PO-2026-11-23-vsx-vs0-independent`.
Supersedes in part `PO_DECISION_RECORD_2026_11_19_VSX_CHASSIS_FAILOVER.md` §6-§8 and outranks
`FAILOVER_EXECUTION_CP_CONTRACT.md` §10 / §17 wherever they conflict with this record. Contract amendment: `FAILOVER_EXECUTION_CP_CONTRACT.md` §18.

## 1. The PO's words (verbatim)
> "Abi tabiki genişlet. ... Checkpoint için, sanallaştırma olan cihazda VSID 0 ayrı failover edilecek, vsid 1 ayrı
> edilebilecek birbiriyle bağımlı asla değiller. Olmayacaklar da. Opsiyon olarak şaside tüm vs'leri aynı anda failover et
> butonu koyabilirsin. Hangi kontrat sorunluysa baştan yeniden yaz."

(English: on a virtualized Check Point device, VSID 0 is failed over on its own and VSID 1 can be failed over on its
own; they are never dependent on each other and never will be. As an option, a button on the chassis that fails over
all VSs may be added. Rewrite whichever contract is in the way.)

## 2. Decision
1. **VS0 is its own failover unit** on a VSX chassis in **VSX HA mode and in VSLS mode** alike. It is failed over with
   the already approved literals `bash -lc 'vsenv 0 && clusterXL_admin down'` / `bash -lc 'vsenv 0 && clusterXL_admin up'`
   (gate rows `cp_failover_down_vsenv0` / `cp_failover_up_vsenv0`, V255). No new command.
2. **Every VS N is its own failover unit** (the existing `*_vsid` rows, `FAILOVER_EXECUTION_CP_CONTRACT.md` §10),
   unchanged.
3. **They are never dependent.** A VS0 run verifies VS0 only: pre-checks, switch confirmation and return on the VS0
   `cphaprob stat` roles. No VS has to move, stay or return for a VS0 run to succeed; no VS readiness is a precondition
   of a VS0 run. A VS run never depends on VS0's readiness or mode.
4. **Mode.** Both members must report the same known VS0 `Cluster Mode:` (High Availability or Virtual System Load
   Sharing); an unknown or split mode stays refused (`VSX_CHASSIS_MODE_UNKNOWN`). `VSX_CHASSIS_MODE_VSLS_USE_SEQUENCE`
   is withdrawn.
5. **Preconditions of a VS0 run:** the chassis unit's own latest readiness READY (`VSX_CHASSIS_READINESS_NOT_READY`);
   the step fence, the fleet one-mutation fence, business hours, incident and quarantine scope as for every unit.
6. **Optional chassis action "Fail over all virtual systems"** stays (the existing sequence of ordinary VS runs, VSID
   order, stop at the first that does not succeed). The VS runs follow one another, never in parallel: the fleet
   one-mutation fence allows one failover write at a time. It needs every VS READY (`VIRTUAL_SYSTEM_NOT_READY`) and does
   not depend on VS0's readiness or mode. VS0 is not part of it.
7. **Gate text.** The PO approves widening the allowed frequency/context text of `cp_failover_down_vsenv0`,
   `cp_failover_up_vsenv0`, the VS0 reads of the VS0 run and `cp_inventory_cphaprob_stat_vsenv0` to "once per VS0
   failover run on the active member of a VSX chassis in HA or VSLS mode" (V265). Commands, contexts, transport, class,
   timeouts and retry are unchanged.

## 3. What it supersedes
- `PO_DECISION_RECORD_2026_11_19_VSX_CHASSIS_FAILOVER.md` §6 item 1 (A for VSLS, B for HA), §7's mitigation (every VS
  must follow the chassis) and §8: the mode no longer picks the path; check 16 "Virtual systems moved" and the stop codes
  `VSX_VIRTUAL_SYSTEM_NOT_ON_ACTIVE_MEMBER` / `VSX_VIRTUAL_SYSTEM_NOT_MOVED` / `VSX_VIRTUAL_SYSTEM_NOT_RETURNED` are no
  longer conditions of any run (kept only as labels for historical runs); §8's "every VS READY" is no longer a
  precondition of the VS0 run.
- `FAILOVER_EXECUTION_CP_CONTRACT.md` §17's HA-only VS0 rule and VSLS refusal (amended by §18).
- The §7 community risk note (in VSLS the VS0 down may not move the VSs) is no longer a risk to the run: the run never
  claims the VSs moved.

## 4. Implementation (lane q-389)
V265 (frequency text only), fixture and `GateRow` constants; service (`CpFailoverService`: VS0 run in HA and VSLS, the
approval names the operation, the sequence offered beside it); worker (`CpFailoverJobExecutor`: mode check HA|VSLS, no
per-VS reads in a VS0 run or its resumed return); standard UI (`CpFailoverPanel`, `HaReadinessList`): "Fail over VS0",
each VS row's own action, "Fail over all virtual systems" as a secondary action.
