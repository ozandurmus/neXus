# PO decision record — 2026-10-06: failover execution across the estate

**Status: RATIFIED — PO APPROVED (chat, 2026-10-06).** Recorded by the engineering session from the Product Owner's
explicit chat decisions of 2026-10-06. This record amends, and does not replace,
[FAILOVER_EXECUTION_CP_CONTRACT.md](FAILOVER_EXECUTION_CP_CONTRACT.md),
[FAILOVER_EXECUTION_PAN_CONTRACT.md](FAILOVER_EXECUTION_PAN_CONTRACT.md) and
[PO_DECISION_RECORD_2026_09_27_CP_FAILOVER_EXECUTION.md](PO_DECISION_RECORD_2026_09_27_CP_FAILOVER_EXECUTION.md).
Where it conflicts with them, this record wins for the items below. Everything it does not mention stays as frozen.

## Decisions

1. **Goal.** Failover execution must be ready for a real, operator-attended trial during the week of 2026-10-06.

2. **Implementation authority.** All ten PRs of [FAILOVER_EXECUTION_PLAN.md](FAILOVER_EXECUTION_PLAN.md) §3 are
   approved for implementation. This includes PRs 2–4 (schedule integrity), the durable mutation boundary,
   crash reconciliation (PR 6) and the schema changes those PRs require.
   - The plan's §4 invariants and the council synthesis in
     [FAILOVER_PILOT_COUNCIL_2026_10_06_ASTRA.md](FAILOVER_PILOT_COUNCIL_2026_10_06_ASTRA.md) §5–§6 are the
     implementation authority for those PRs.
   - Every migration is still dry-run on the live database in BEGIN/ROLLBACK before deploy.

3. **Scope.** The feature applies to the whole enrolled estate. The operator selects the cluster at run time.
   - There is no hardcoded or pilot-only enrollment list.
   - One fleet mutation at a time, durably enforced.

4. **Approval policy.**
   - `admin`: a single authenticated administrator may approve and start.
   - A separate "operation admin" role requires two distinct authenticated identities (four eyes).
   - `aiview` / `replay_viewer`: view only.
   - Identities always come from the server-side authenticated principal.

5. **Operator flow.**
   1. Manual initiate.
   2. Warning prompt and explicit OK.
   3. The full readiness battery runs.
   4. If clean, the UI shows "failing over" and the failover executes.
   5. Checks rerun immediately afterwards and verify that traffic flows and the cluster works.
   6. There is no second confirmation after clean readiness.
   7. No automatic retry, failback or quarantine release.

6. **Check Point mutation.** The existing gated `clusterXL_admin down` on the verified active member, then
   `clusterXL_admin up` on the former active (V101 gate rows). No other write command.

7. **Post-failover verification.**
   - **Connection table:** `fw tab -t connections` (including `-s`) is **rejected** and is removed from the failover
     workflow. The result reads "session continuity not evaluated"; it is never a pass.
   - **Policy:** `cpstat fw -f policy` is **approved** for the failover phases. Purpose, context and frequency go
     through the gate registry; the repository's existing key is `cpstat -f policy fw`.
   - **Traffic:** the existing gated interface-byte rate (`/proc/net/dev`, two samples 5 s apart, new-active rate ≥ 50 %
     of baseline) is measured at three points: baseline, post-switch and post-return.
   - **cpview:** a cpview-style read (candidate `cpview -p`) may be added only after a one-off measurement and the
     PO's exact-command OK. It is not approved by this record.

8. **Parked.** SNMP and Splunk log verification (backlog).

## Not authorized by this record

- No new device command beyond item 7's `cpstat fw -f policy`.
- No automatic or scheduled failover execution. The generic manual/scheduled routes stay disabled.
- No execution on any unit before the PO starts it in the UI.
