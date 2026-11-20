# PO decision record: per-VS cpstat policy read for Check Point failover (2026-11-19)

**Status:** RATIFIED. The PO answered "Onaylıyorum" on 2026-11-19 to the exact literal below.

## Finding
A Check Point failover run on one virtual system of a VSX/VSLS cluster stopped at the start with
`COMMAND_GATE_UNAVAILABLE`, before any device contact (`mutation_possible = false`). The executor gates every command of
the run up front; for a VS run it builds `bash -lc 'vsenv <VSID> && <command>'`. Every per-VS failover row (`*_vsid`,
including `cp_failover_down_vsid` and `cp_failover_up_vsid`) was SIGNED_OFF, but the policy-install read existed only
as `cp_policy_install_cpstat` (default context) and `cp_policy_install_cpstat_vsenv0` (V177). No per-VS failover had
been attempted before, so the gap was not visible.

## Decision
Add one read gate, seeded SIGNED_OFF by V254:

| gate_id | canonical command key | class |
|---|---|---|
| `cp_policy_install_cpstat_vsid` | `bash -lc 'vsenv <VSID> && cpstat -f policy fw'` | read |

Same timeout (30 s), retry (none), frequency and projection as the `vsenv 0` row: once pre, once post-switch and once
post-return per member per VS failover run; policy identities stay in memory, only equality, presence and install-time
skew are projected. Approval of this command does not approve any other.

## Not changed
Readiness rules, the per-VS write rows, and the refusal of a VS failover whose pre-checks fail (a member DOWN, pnotes
FAIL) are unchanged.
