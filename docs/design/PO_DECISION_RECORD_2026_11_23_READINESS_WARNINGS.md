# PO decision record: Check Point readiness warnings (2026-11-23)

**Status:** RATIFIED. The PO answered "İkisi de sarı uyarı" on 2026-11-23: both rules below are amber, non-blocking
warnings. Each rule has its own section; lane 384 wrote section 1, lane 385 adds section 2 (lost updates).

Common model (already in the product, not new): a readiness check row may carry `WARN`. A `WARN` row is recorded, shown
amber, is not blocking (`ReadinessCheckView` `blocking=false`), and does not make the readiness outcome `NOT_READY`; the
readiness stays `READY`. A failover start always needs the initiator's warning confirmation (`warningConfirmed`;
`WARNING_CONFIRMATION_REQUIRED` otherwise, in `CpFailoverService#request` and `JooqCpFailoverRepository#requestBound`).
Check 13 "Last failover" was the first CP check to use `WARN`.

## 1. VSX chassis: VS0 policy install-time skew is a warning (lane 384)

### Finding
On VSX chassis units the "Installed policy parity" check (check 10) failed because the VS0 `fw stat` install times of
the two members differed while the policy name was the same (measured: skew of 18 and 62 days). Rule:
`CpFailoverChecks.POLICY_MAX_SKEW_SECONDS = 600`, reason `INSTALL_TIMES_DIFFER`.

### Decision
VSX chassis units only (the unit without a VSID whose members carry virtual systems; reads in `vsenv 0`):

| VS0 evidence | Status | Reason | Text |
|---|---|---|---|
| same name, skew <= 600 s | PASS | `POLICY_MATCH` | Same policy on both members; installed within 10 min |
| same name, skew > 600 s | **WARN** | `VS0_INSTALL_TIMES_DIFFER` | Same VS0 policy name; install times differ by N days -- reinstall the VSX gateway policy to align |
| names differ / missing / changed since pre | FAIL | as today | as today |
| unrecognised | UNKNOWN | as today | as today |

Non-VSX units (plain ClusterXL HA and each virtual-system unit) are unchanged: skew over 600 s stays FAIL.

The chassis failover run (option B, VSX HA mode) applies the same rule to its VS0 `fw stat` and `cpstat` parity: a
`WARN` there is recorded and does not stop the run. Any FAIL still stops it; UNKNOWN still stops it.

### Where the blocking parity is measured
The blocking parity is per virtual system, and it is already in place without any new command:
- each virtual-system unit's readiness reads `fw stat` in its own context (`cp_failover_fw_stat_vsid`) under the
  unchanged rule (skew > 600 s is FAIL, blocking);
- each virtual-system failover run also reads `cpstat -f policy fw` in its context (`cp_policy_install_cpstat_vsid`, V254);
- a chassis start (`CpFailoverService#chassisPlan`) requires the chassis readiness and **every** virtual system's readiness
  to be `READY` (`VSX_CHASSIS_READINESS_NOT_READY`, `VIRTUAL_SYSTEM_NOT_READY`).

**Stopped part (finding, no change made).** The chassis readiness run and the chassis failover run do not read
`cp_policy_install_cpstat_vsid`: the chassis runs issue per-VS `cphaprob stat` only (check 16). That gate row's
`max_frequency` is "once pre, once post-switch and once post-return per member per failover run of a virtual system";
issuing it from a chassis run would be a new frequency, which this decision does not authorise. Reading per-VS policy
parity inside a chassis run therefore needs its own gate/frequency decision; until then the per-VS readiness above is
the blocking evidence.

### Implementation
- Worker: `CpFailoverChecks#chassisPolicyParity`, `CpFailoverJobExecutor#policyStatus` and the readiness `record` rule
  (WARN never sets the readiness failure).
- Service: `ReadinessCheckView` (WARN is non-blocking; the check 10 summary now reads the reason nested under
  `fwStat`/`cpstat`, where the worker stores it).
- Frontend: `ReadinessChecksTable` shows a `WARN` row amber with a "warning" chip; the card verdict reads
  "Ready · N warning(s)".
- No database migration, no gate row, no command, frequency or context change.
