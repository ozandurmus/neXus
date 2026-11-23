# PO decision record: Palo Alto session sync on an idle pair is a warning (2026-11-23)

**Status:** RATIFIED -- Product Owner decision in chat, 2026-11-23 (option 1 of 3). Amends check 5 of the FROZEN
`FAILOVER_EXECUTION_PAN_CONTRACT.md` (§6, §8); its §11 points here.

## 1. Problem
Check 5 (session synchronization) required the active member's session setup/update `sent > 0` and the passive
member's `recv > 0`. A pair that carries no sessions has nothing to synchronize, so both counters stay 0 and the
check was FAIL: the unit was NOT_READY and a failover was refused although nothing was wrong with the pair.

## 2. Rule
Check 5 is **WARN -- never PASS --** when all of these hold in the same readiness (or failover) phase:
- the active member's state sync is `enabled`;
- check 3 (HA links) evidence shows the HA2 link `up` on both members;
- check 6 (sessions carried) counted 0 active sessions on both members, with comparable reads (not UNKNOWN);
- the only failing condition is a zero counter (active `sent` = 0 and/or passive `recv` = 0).

Any other failure stays as today: sync disabled on the active member, a message type disabled on the active member,
HA2 down, a missing or unrecognised field (UNKNOWN), non-zero sessions with zero counters (FAIL).

Warning text: "No sessions to synchronize (0 active sessions on both members); the session sync path is not proven by
traffic." Derived evidence carries `"reason":"NO_SESSIONS_TO_SYNCHRONIZE"` plus the same counters as before.

## 3. Gate
The status vocabulary already has WARN as a result that does not block (last failover within 6 hours on Check Point
check 13, Cisco ASA and FortiGate check 9); the readiness outcome set stays `READY` / `NOT_READY` / `UNKNOWN`. A check 5
WARN does not block: the run's outcome is READY when every other blocking check passes, and an execution does not stop
on it. A WARN on any other Palo Alto check still blocks. The start keeps the existing single warning confirmation
(`warningConfirmed`, required for every start); no new confirmation or gate state is added.

Screens: the row shows the warning colour (amber), not red; the unit's badge never says a bare "Ready" -- it says
"Ready · 1 warning" (standard readiness card, Estate failover board, Estate dossier).

## 4. Reason
An idle pair cannot prove the sync path with traffic, but zero counters on an idle pair are not evidence of a broken
path either. Refusing the failover blocks maintenance on idle pairs; passing it would claim a proof that does not exist.
A warning tells the operator exactly that and leaves the decision with them (product rule: warn, do not refuse).

## 5. Rejected options
- **PASS with a note.** Rejected: a PASS would claim the sync path is proven when no traffic proved it; a note is easy to
  miss and the unit would read as fully Ready.
- **Keep FAIL.** Rejected: blocks every failover of an idle pair for a condition that is expected, not a fault.

## 6. Implementation
- Worker: `PanFailoverChecks.sessionSyncEvidence(active, passive, idle)`, `idlePair`, `blocks`;
  `PanFailoverJobExecutor.checks` passes the same phase's HA2 states and session counts.
- Service: `ReadinessCheckView` check 5 summary for WARN.
- Frontend: `screens/readinessWarnings.ts`, `ReadinessChecksTable` (amber row, "Ready · N warning(s)"), Estate board and
  dossier badges.
- Evidence limit: automated tests only; the idle-pair counters are as measured on 2026-10-03, not yet re-observed on an
  idle estate pair (real-environment validation pending).
