# PO decision record - 2026-11-03: path analysis Phase A boundaries (D-1 to D-9)

**Status: RATIFIED - PO DECISION (chat, 2026-11-03).** Design-level decisions; no code, migration or device command is
authorized by this record alone. Implementation follows the stage plan in `PATH_ANALYSIS_DESIGN.md` §7 after A0 freezes
`PATH_ANALYSIS_VENDOR_SEMANTICS.md`.

## Context
The merged design `PATH_ANALYSIS_DESIGN.md` (DRAFT) listed nine PO decisions. Four of them (storage, results, aiview query
input, RBAC) were put to a read-only three-seat decision council (role-diverse review only: all seats ran on one model family).
The council changed the design's recommendation for D-1 and tightened D-2, D-3 and D-8. The synthesis was presented to the PO
with the other five recommendations from §8 of the design.

## PO answer (verbatim)
"Önerilen gibi devam edelim." ("Let us proceed as recommended.")

## Decisions

**D-1 Storage - hybrid.**
- The topology graph is built in memory from inventory and rebuilt on demand; it is not stored node by node.
- Persisted: `topology_link_override`, `topology_conflict`, a `topology_snapshot` header (opaque id, build time, input
  fingerprints, counts), and the new inventory columns `device_route.is_active, is_ecmp, metric, next_hop_kind`,
  `device_interface.routing_context, evidence_grade`, plus `policy_target_binding`.
- A NULL in any new column means `UNKNOWN`, never "false" or "default".
- Device deletion must remove or orphan-mark every new row that references the device (overrides, conflicts, bindings); the
  migration states which.
- Reason for rejecting full relational storage: every inserted row adds an audit row and there is no audit purge, so storing
  every node/segment/attachment per rebuild grows the audit log without bound.

**D-2 Results.** Path results are not stored. Each query writes one row to the existing `authz_decisions` trail with the
masked query and the verdict; no separate `path_query_audit` table. Result history is Phase D.

**D-3 aiview query input - approved with five conditions.**
1. aiview may only submit masked values; a raw value from aiview is refused.
2. The reverse map is built per topology snapshot, kept in memory only, never persisted or logged.
3. A masked value that resolves to more than one raw value is refused (`AMBIGUOUS_PSEUDONYM`).
4. Resolution has no side effects (no collection, no cache warm-up visible to other users).
5. Refusals are uniform: unknown, ambiguous and not-permitted values return the same shape so the map cannot be probed.

If the conditions cannot all be met, aiview falls back to picking endpoints from masked lists (no free text).
Note: `TopologyNamePseudonymizer.resolveClusterName` already resolves names (last write wins, no ambiguity check); the
path feature must not reuse it without the ambiguity check of condition 3.

**D-4** "Up to the edge" mode (`ALLOWED_TO_EDGE`) for paths that leave known topology: approved, clearly labelled.

**D-5** PAN `application-default`: match on the application's default ports with an "App-ID assumed" caveat; such a hop is
never plain `ALLOWED`.

**D-6** CP gateway topology / anti-spoofing / security-zone resolution from `show-gateways-and-servers`: deferred until the
first path results exist. Until then those rules resolve to `UNKNOWN`.

**D-7** New read-only device commands (PAN advanced-routing route read in A1; A6 validation matchers): approved in principle
only. Each exact command still needs its own PO approval under the 2026-09-26 amendment.

**D-8 RBAC - follow today's rule.**
- Roles that see inventory raw today see topology and path results raw; only aiview (`replay_viewer`) is masked. The design's
  "operator/viewer masked" line is withdrawn: no enforcement point exists for it and operators already see raw inventory.
- New actions in `ActionRegistry`: `path_analysis_read`, `topology_read`, `topology_override_write`, `topology_rebuild`.
- `topology_override_write` and `topology_rebuild` are `security_admin` only. aiview is never allowed either, even when the
  same account also holds an admin role.
- No new collection rights for aiview.
- An override carries a reason code plus an optional free-text note; the note is hidden from aiview.

**D-9** Phase B load-balancer vendor and Phase C APIC read-only account: open, PO input when Phase B is planned.

## Not decided here
- FW-TANGO-08 local log retention change (7 to 2 days) needs its own explicit PO yes.
- Pre-existing weaknesses found by the council are logged as backlog items, not fixed by this record: tier-2 audit redaction
  uses unsalted sha256 (IP values are recoverable by enumeration), and `authz_decisions` has no retention.
