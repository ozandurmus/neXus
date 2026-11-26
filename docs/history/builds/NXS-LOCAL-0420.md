# NXS-LOCAL-0420 — Zone matrix on-demand run route and 4b-2 tabs, PAN applications by name, Policy Insights I4 and detail drawer, aiview sweep rounds 3-7 with leak fixes, CLI parity, lane runner quota handling

## Summary

Zone matrix gained the on-demand run route POST /api/v2/zone-matrix/runs (586cb710), Violations and Coverage tabs with per-cell open-violation counts (5002c05c, 05893fd8) and Palo Alto application-name evaluation (683ec5d5); Policy Insights gained I4 partial shadow and a detail drawer (54bb2f73, 5be2d6b7). The aiview raw-versus-masked sweep reached round 7 and its findings were fixed in the masker and controllers (ef2601cf, 05893fd8, 8e34f0b1), with CLI parity for policy, path, topology, failover and script routes; automated validation only.
