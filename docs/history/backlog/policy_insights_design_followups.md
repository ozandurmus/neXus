# Policy Insights design follow-ups: missing hygiene-modes design file the code cites, stale work-budget figure

status: planned · target: POLICY_INSIGHTS_DESIGN_2026_11_24.md section 11

2026-11-26: reported contradiction 1 closed. docs/design/POLICY_HYGIENE_MODES_AND_RESOLUTION_DESIGN_2026_11_13.md restored from side-branch commit 12b7bc34 as DRAFT with a dated status note: the source implements only the legacy mode of its section 3.2 (PolicyHygiene.analyzeLegacy, PolicyLegacyView, PolicyNormalization.upperBound), not wired to the policy screen; every other part is not authority. The existing source citations now resolve. Section 11 of the FROZEN design is left as the historical report; its paragraph is listed in test_contract_authority_status as a detector false positive.

2026-11-26: reported contradiction 2 closed. POLICY_HYGIENE_W1_A_NOTES.md now states the work budget as PolicyHygiene.RULE_WORK = 1,000,000 per rule and PolicyHygiene.TOTAL_WORK = 200,000,000 per snapshot (fixed constants, no property), read from PolicyHygiene.java; no other document stated the old 250,000 hygiene figure.
