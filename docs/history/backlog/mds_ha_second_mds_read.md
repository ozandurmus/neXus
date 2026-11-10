# MDS HA: read the second MDS / HA state (mdsstat or an MDS-level query) -- needs a gate entry

status: planned · target: CP management plane

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): MDS HA: read the second MDS / HA state (mdsstat or an MDS-level query) -- needs a gate entry

2026-11-10 (plan refresh): real_env_validated. Gate V194 (mgmt_cli show-ha-status), one-off fingerprint acceptance V196, PR 779: an MDS-level reply with domain-type mds and no servers is the documented Active/Active case and reads ACTIVE live; the PO confirmed a secondary MDS exists. PR 781 (V197) adds per-Domain Active/Standby placement from the already gated show-domains read; its tree view has no recorded live check yet. Evidence: CP_MEASUREMENTS_2026_11_06_AFTERNOON.md sections 5-8.
