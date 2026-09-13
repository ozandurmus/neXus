# OP.0 - HA Readiness / Failover-Safety Assessment (read-only)

status: deferred · target: OP.0

Read-only per-vendor preflight battery (see docs/design/FAILOVER_ENGINE_ARCHITECTURE.md sections 3-4): viable target, state/session sync, version/policy/content parity, split-brain/flap, control+sync link health, preemption note. Emits SAFE_TO_FAILOVER / DEGRADED_PROCEED_WITH_RISK / UNSAFE_DO_NOT_FAILOVER / INSUFFICIENT_EVIDENCE with blocking line items, plus the SCC readiness dashboard + history. NO write capability. Needs the DEPLOY.1 server and real cluster access; buildable in the 0.8.x-0.9.x era as a VERIFY-plane feature. Foundation and safety net for OP.1 / OP.2.

2026-09-13 realignment: moved to the reserve. The readiness semantics are contract work (still active); their Python implementation is know-how only. PO_DECISION_RECORD_2026_09_12 section 2 (2026-09-12): all UI 2.0 feature work is Java written from scratch; the existing Python is know-how only. Backlog realignment 2026-09-13, approved by the Product Owner in session.
