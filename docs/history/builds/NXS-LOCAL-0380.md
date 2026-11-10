# NXS-LOCAL-0380 — Policy credibility and hygiene: section joins, partial priors, NAT by package UID, fewer UNKNOWN

## Summary

Check Point sections and NAT rulebases are joined across pages and read by package UID (V191-V192), group members resolve from the object inventory, and hygiene passes over partial priors that provably cannot cover a rule, with Panorama scope and PAN application identities pinned by tests (PRs 695-761). Live counts: unresolved CP objects 32,429 to 670 and earlier-rule UNKNOWN 60,217 to 56,085; policy acceptance stays open.

Evidence (2026-11-10 plan refresh): first Check Point policy collection COMPLETED after the NAT read by package UID (V192, 2026-11-05) and again on 2026-11-09 after the MDS fingerprint was accepted once (V196). X1 live effect: earlier-rule UNKNOWN 60,217 to 56,085 rules and UNKNOWN shadow status 97,391 to 93,259 (PO_DECISION_RECORD_2026_11_05_EVENING_BATCH.md outcomes). Next levers: POLICY_UNKNOWN_REDUCTION_PLAN_2026_11_05.md section 0.1.
