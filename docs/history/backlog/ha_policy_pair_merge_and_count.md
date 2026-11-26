# HA policy pairs: merge and count a pair once (follow-up to HA_POLICY_DIVERGENT)

status: planned · target: After 1588f36f (read route GET /api/v2/policy/ha-pairs)

Nothing is merged or counted once yet; read route GET /api/v2/policy/ha-pairs shipped 1588f36f.

2026-11-26 backlog truth (origin/main, code and tests checked): the hygiene half is DONE in code, automated validation only. 1d6448ef: PolicyHygieneFleetSummary counts a MATCH-corroborated SAME ASA/FortiGate pair once via HaPolicyPairService.evaluatePairs; DIVERGENT and UNKNOWN pairs are annotated and never merged. Pinned by PolicyHygieneFleetSummaryTest (sameAsaAndFortiGateHaPairsCountOnceAndReappearImmediatelyWhenDivergent, sameHaPairCountsContentOnceButHitCounterFindingsPerMemberWhateverMemberIsStandby, unknownHaPairsAndUnavailablePairServiceNeverMerge, viewNeverEvaluatesPairsAndAFailedTickKeepsTheLastGoodIndexAndFingerprint, privacyMaskPreservesHaFieldsAndCountsWithoutLeakingHostnamesOrWithholding) and PolicyHygieneFleet.test.tsx. The earlier line 'Nothing is merged or counted once yet' is stale for hygiene. NOT done: the zone matrix half (PO decision record 2026-11-14 item 6, design Q5): no HaPolicyPairing use in zone matrix code; it waits for the Product Owner answer on ZONE_MATRIX_ASA_FORTIGATE_AMENDMENT_PROPOSAL_2026_11_25 (DRAFT). Status stays planned; live validation open.
