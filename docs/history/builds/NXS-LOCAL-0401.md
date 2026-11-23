# NXS-LOCAL-0401 — VSX VS0 independent failover unit (V265), PAN idle-pair session sync warning and Overview section hardening

## Summary

On a VSX chassis VS0 fails over as its own unit in HA and VSLS mode, independent of every VS (PO 2026-11-23, V265 widens only max_frequency of the existing VS0 gate rows, b0848e12), and an idle PAN pair's zero session sync counters are a warning instead of a failure (b0848e12). An overview section that is null or failed reads UNKNOWN and one failing attention rule no longer crashes the list (46050da0).

## Batches

b0848e12 (batch auto-1123-1909: lanes 389-cp-vsx-vs0-independent-failover, 421-port-394-port-381-fix-pan-idle-session-sync-warn), 46050da0 (2026-11-23, 19:09 through 19:20).

## Open

Not real-environment validated. Decision records: `docs/design/PO_DECISION_RECORD_2026_11_23_VSX_VS0_INDEPENDENT.md`, `docs/design/PO_DECISION_RECORD_2026_11_23_PAN_IDLE_SESSION_SYNC.md`.
