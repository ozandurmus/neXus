# PO decision record: Check Point readiness warnings (2026-11-23)

**Status:** RATIFIED. The PO answered "İkisi de sarı uyarı" on 2026-11-23: both rules below are amber, non-blocking
warnings. Each rule has its own section; lane 384 wrote section 1, lane 385 wrote section 2 (lost updates).

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

## 2. Lost-update rate on the state-synchronization check (check 9, lane 385) on the state-synchronization check (check 9)

Section owner: lane Q-385. Code: `CpFailoverChecks#readinessSync` (worker), `ReadinessCheckView` (service text),
`ReadinessChecksTable.tsx` (amber row).

### Finding

Check 9 failed on ANY increase of `lostUpdates`, `lostBulkUpdateEvents` or `unsynchronizedUpdates` since the unit's previous readiness
run. Fleet measurement over 7 days (2012 rows) found 6 increases of `lostUpdates`: +1 in 4.1 h, +35 in 6 h, +1551 in 45.6 h, +3967 in
6 h, +714102 in 44 h, +958741 in 6 h. The first two are background noise of a healthy pair; the rest are real loss.

### Rule

Constant `LOST_UPDATES_WARN_PER_HOUR = 10`. Rate = increase / hours between the baseline (the previous readiness observation) and this
read, with at least one hour assumed (so a short interval cannot inflate the rate). The comparison is exact integer arithmetic.

| Evidence | Result |
|---|---|
| Sync status OK, only `lostUpdates` increased, rate at most 10 per hour | **WARN** (non-blocking), "Sync OK; +N lost updates in H h (below 10 per hour)" |
| Sync status OK, only `lostUpdates` increased, rate above 10 per hour | FAIL, as before |
| Any `lostBulkUpdateEvents` increase | FAIL, as before |
| Any `unsynchronizedUpdates` increase | FAIL, as before |
| Sync status not OK | FAIL, as before |
| Output unparsable, counters missing | UNKNOWN, as before |
| Counter went down (reset) or no baseline | PASS with the reset / baseline flag, as before |

Applied to the measured series: +1 in 4.1 h and +35 in 6 h warn; +1551 in 45.6 h (34 per hour) and the three larger rows fail.

### Gate behaviour

WARN on check 9 is recorded like WARN on check 13: it does not stop a real failover run and does not make a readiness run NOT_READY
(`CpFailoverJobExecutor#record` exempts it). A start still always requires the initiator's explicit `warningConfirmed` (unchanged,
`CpFailoverService#request`); the warning adds no second confirmation. The row evidence carries `elapsedSeconds` and `warnPerHour` so
the text states the limit that applied.

### Presentation

The standard readiness table shows the row amber (warning icon, "warning" chip, "Warning" tile count); it is neither counted as
passed nor as blocking. The Estate dossier already renders any WARN result as a "Review" badge.

### Non-goals

No change to the other counters, to non-CP vendors, to the sync command or its frequency, and no tuning of the 10 per hour limit
without a new fleet measurement.
