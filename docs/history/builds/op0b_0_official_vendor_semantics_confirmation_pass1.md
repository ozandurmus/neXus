# op0b_0_official_vendor_semantics_confirmation_pass1 — OP.0b.0 - official vendor semantics confirmation, pass 1 (documentation-only)

## Summary

Second session against the OP.0b.0 draft's D-V1-D-V7/D-V9 blocking rows: a page-fetch tool returned EGRESS_BLOCKED against every official Check Point/Palo Alto documentation host (identical failure class to session 1's CONNECT 403), but a separate search tool was reachable and narrowed D-V1, D-V2, D-V4, D-V5 from UNKNOWN to PARTIALLY_CLOSED via genuine official-page/KB excerpts; D-V3a, D-V6, D-V7 stayed STILL_UNKNOWN and D-V9a PARTIAL, unchanged. No row reached CLOSED_BY_DOCS, so the contract stays DRAFT -- DO NOT FREEZE.

## Evidence

docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md: new dated section with the required decision matrix and source table, D-V3/D-V9 split into a/b sub-rows, Open decisions and Freeze decision updated. Document only; no code, collector, parser, schema, UI or transport changed; no device contacted. git diff --check clean.

## Risks forward

Two consecutive sessions have now hit an identical structural WebFetch-class block against every official vendor host; a third automated retry is not expected to behave differently. D-V3a (PAN serial field semantics), D-V6 (CP -ia/-l list and cphaprob state field set) and D-V7 (CP recovery-setting attribute name) need either a human to fetch the named pages/sk-articles and paste their body text in, or a genuinely unblocked network. D-V3b, D-V9b and the D-V5/D-V9a residual gaps stay real-env-only regardless (S0/S2/S8), unaffected by this session.
