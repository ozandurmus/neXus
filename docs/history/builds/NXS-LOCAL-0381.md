# NXS-LOCAL-0381 — Check Point management facts: identity panel, hotfix levels, MDS HA role, Domain placement, VSX VIPs

## Summary

The device header shows platform identity as a panel, and R82 MDS and Quantum Spark hotfix levels (V195), the MDS HA role (V194, V196; Active/Active per the R82 guide), per-Domain Active/Standby placement (V197) and VSX VIP prefixes from management objects shipped (PRs 765-781). Live: MDS hotfix level read, MDS role ACTIVE, ADDRESS_PREFIX_UNKNOWN 54 to 30; the Domain placement view has no recorded live check yet.

Evidence (2026-11-10 plan refresh): CP_MEASUREMENTS_2026_11_06_AFTERNOON.md sections 5-8. MDS hotfix level read live through the direct cpinfo literal (V195) in about 5 s; the MDS API fingerprint was accepted once through neXus with PO approval (V196, switch removed afterwards); an MDS-level show-ha-status reply maps to ACTIVE (outcome MDS_LEVEL_ACTIVE_ACTIVE, PR 779); VSX stage 2: ADDRESS_PREFIX_UNKNOWN 54 to 30, all topology conflicts 300 to 274. Per-Domain placement (V197, PR 781) parses the already gated show-domains read; a live check of the management tree view is not recorded yet.
