# PO decision record - 2026-11-05: container scope principle and an "analysis" container direction

**Status: RATIFIED - PO DECISION (chat, 2026-11-05), direction only.** The architecture design, its review and the PO
go-ahead come before any implementation.

## Context
The PO wants policy and topology/path analysis in their own containers, and the container count to grow with the scope
of the work. The engineer, acting as PO assistant and devil's advocate, measured first (2026-11-05, kubectl top):

| Pod | Memory | Role |
|---|---|---|
| ui2-policy | 6.5 GiB | policy collection |
| ui2-service | 5.3 GiB | API; also policy analysis (shadow/hygiene engine, parsed-snapshot cache) and topology/path analysis |
| node total | 27 % memory, 14 % CPU | headroom available |

Policy collection is already separate; policy analysis and path analysis still run inside ui2-service.

## Decision
1. **Principle:** the container count grows with a measured need, not with feature scope. A new container must be
   justified by one of:
   - load isolation: work that slows other work;
   - failure isolation: one crash must not take down others;
   - an independent deploy cadence.

   Every new container has a cost:
   - a deploy target and drain barrier (a drain barrier deadlocked a deploy on 2026-11-04);
   - service-to-service authentication;
   - aiview masking re-established at the new boundary.
2. **Direction:** one new container, **analysis**, holding policy analysis and topology/path analysis together. Both
   are read-only, both compute over stored snapshots, and both can starve the operational API during heavy runs.
   Inside it, policy and path stay separate packages; the path package already has an architecture fence. Path splits
   into its own container later, if A6 validation or query load justifies it.
3. **Order of work:**
   1. Measure what holds ui2-service memory. The assumption that it is mostly the policy cache is UNKNOWN until
      measured.
   2. Argon architecture design: the code to move, the inter-container API, the masking boundary, deploy and drain,
      and the rollback.
   3. Opus security and concurrency review.
   4. PO go-ahead.
   5. Implementation (Sonnet lanes).

   Scheduled after the UI review B/C work.
4. **Steering:** the PO assistant must challenge PO directions with evidence, not just agree, and every PO session
   that follows inherits this role.
