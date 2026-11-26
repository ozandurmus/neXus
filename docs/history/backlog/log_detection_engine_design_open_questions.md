# System-log detection engine: design FROZEN by the PO, P1 shipped in shadow (842ff7cb, V272); later phases open

status: planned · target: docs/design/LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md

2026-11-26 (d2a95f34, 33c26833): phase P2 (layer B: catalog, rarity, rate) landed as code behind its own switch p2_enabled, default off, with migration V273; it runs only when the P1 switch and a FROZEN catalog version exist and cannot be switched on while the P1 shadow period runs. Pending chunk queries now run newest day first. Not in P2: BOOT_BURST, expected windows, version/HA segments, backtest replay, sources beyond cp_messages and pan_system, UI. Automated validation only; the design document is FROZEN and the open questions stay with the PO.
