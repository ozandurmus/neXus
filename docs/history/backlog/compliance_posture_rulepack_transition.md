# 0.6.6B — Compliance Rule-Pack Transition Foundation

status: automated_validated · target: 0.6.6B

AUTOMATED_VALIDATED 2026-08-28. New utils/compliance_rulepack.py holds a static versioned in-repository pack (pack_id securityexpert.baseline.cp-pan, pack_version 0.6.6B, certification_claim=false, 10 rules) built from the single-source-of-truth BASELINE_CONTROLS. compliance_posture._subject_controls routes the ten through DEFAULT_RULE_PACK and stamps each result with {pack_id,pack_version,rule_id}; additive top-level rule_pack block; platform/fleet controls carry rule_pack=null; COMPLIANCE_SCHEMA_VERSION -> 0.6.6B. Outcomes for existing synthetic evidence unchanged (proven per-control against the raw evaluator). Evidence: pytest 440 passed / 3 skipped / 0 failed; --render-only PASS (rule_pack embeds in HTML); repository privacy gate PASS/0. No collector/network/CAS/scheduler/UI-logic change. Dynamic/signed packs, scoring and formal framework governance remain 0.7.x.
