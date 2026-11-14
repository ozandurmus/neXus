# PAN active/active is not excluded as a failover unit (CP load-sharing is)

status: planned · target: docs/design/PAN_ACTIVE_ACTIVE_FAILOVER_UNIT_GAP_2026_09_12.md; blocked on open decision op_aa_vsls_scope

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): PAN active/active is not excluded as a failover unit (CP load-sharing is)

Target (full): docs/design/PAN_ACTIVE_ACTIVE_FAILOVER_UNIT_GAP_2026_09_12.md; blocked on open decision op_aa_vsls_scope

2026-11-14 backlog truth: DONE. Evidence: CpFailoverServiceTest.panActiveActiveRefusedWithoutVsUnits (UNSUPPORTED_HA_MODE); PlatformAndModeGateCheck; de21a95a, PR 500, 547. PAN active/active is refused as a failover unit
