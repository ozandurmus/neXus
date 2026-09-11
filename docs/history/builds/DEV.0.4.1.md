# DEV.0.4.1 — Runtime Inventory Exclusion Policy Foundation

## Summary

Removed repository hard-coded CP non-device identities. Vendor-neutral local RuntimeRoot policy now supplies exact pre-poll exclusions through the existing shell hook with safe quoting; malformed policy fails before network access. Privacy gate: 0 findings.
