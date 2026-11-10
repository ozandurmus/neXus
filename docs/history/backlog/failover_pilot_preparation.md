# Failover pilot preparation (status doc lane)

status: in_progress · target: docs/design/FAILOVER_EXECUTION_PLAN.md

2026-10-20 (in progress). PO 2026-10-06: a real failover trial within the week, one pilot cluster; execution path is top priority. A status-document lane is in progress for the pilot preparation (in flight at the time of this record; its result is not recorded here). Jobs, including failover, run only in the 00/06/12/18 Europe/Istanbul windows. No trial has been performed; nothing in this item authorises execution (typed request, approval, fresh readiness and uncertain-outcome fencing still apply). Related open items: failover_pilot_fence_is_compiled_in, failover_p0_closure_set, failover_recheck_on_request. 2026-10-20 also shipped a faster units lookup (66c04da2) for the readiness panel.

2026-11-10 (plan refresh): status doc FAILOVER_PILOT_STATUS_2026_10_20.md; P0-1..P0-3 closed in code (PR 653, record RATIFIED 2026-10-20); resume of the return step (PR 656); PAN write routes and config-sync remediation (PRs 667, 676); VSX chassis is its own unit (PR 672); one framed shell per member behind a switch with a per-cluster allow-list (PR 670). First Check Point pilot run 2026-10-21 stopped before the up step (check 11, fixed in PR 666). No completed failover and return is recorded. Next: repeat the pilot on one cluster, record the closure evidence (P0-4) under aiview.

PO decision 2026-11-10 ('Yes, failover next'): the failover pilot on one Check Point cluster is the roadmap Next, ahead of overview acceptance. Confirms the 2026-11-10 plan refresh proposal.
