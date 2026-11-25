# Zone matrix: freeze contract, then deliver in steps

status: in_progress · target: Council 2026-11-13; bank inputs pending

2026-11-14: contract DRAFT on branch (zone-matrix-contract session). Council 2026-11-13 asked for rule-level evaluation with an upper-bound match function, NAT-aware destinations (no false COMPLIANT on unmapped VIPs) and multi-layer handling. Bank inputs still open: regulation, retention, staleness, compliance_admin team. Steps: freeze, step 1 rule-level evaluation, later flow view.

2026-11-14: contract FROZEN on main; step 1 (V216) in ship batch 1, step 2 (V220) and screen in batch 4.

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Step 2 built: 'Zone matrix step 2: cell edits, template import, reference confirmation, loosening diff', migration V220, 'Zone matrix screen: add Matrix tab and step-2 Versions diff and loosening rules'. Step 1 (V216) is on main. Open: bank inputs, live dry run of V220, steps 3 and later.

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): on main and live: step 1 (V216), step 2 cell edits, template import, loosening diff and Matrix tab (V220), step 3 evaluation engine, on-demand Evaluate tab and witness audit kind (V225 then renumbered V228). Remaining: bank inputs (regulation, retention, staleness, compliance_admin team), real-environment counts-only measurement of step 3 on the largest stored policies, steps 4 and later.

Step 4b-1 (V267 durable runs and findings, own pool, startup recovery) shipped 1588f36f; step 4b-2 (lifecycle, triggers, routes, tabs) open.

Step 4a (finding lifecycle and per-policy staleness as pure functions, 24 h interim STALE threshold, no storage) on main in e6f0167e; PO answers in PO_DECISION_RECORD_2026_11_23_P1_MIDDAY_ANSWERS (529776a9).

## 2026-11-23 acceptance traceability (steps 1-3)

Controlling text: `docs/design/ZONE_MATRIX_CONTRACT.md` section 10.2 (FROZEN, revision 2), delivery order in 10.1. Steps 1-3 are on main
(V216, V220, V228 and the evaluation engine). Step 4 (durable runs, finding lifecycle, staleness) is being built in other lanes and is
not covered here. Counts and test names only, no data. Abbreviations: `Acc` = `ZoneMatrixAcceptanceCriteriaTest` (service),
`Eval` = `ZoneMatrixEvaluatorTest`, `Svc` = `ZoneMatrixServiceTest`, `Two` = `ZoneMatrixStepTwoTest`, `Part` = `ZonePartitionTest`,
`Sec` = `ZoneMatrixSecurityTest`, `Schema` = `ZoneMatrixSchemaTest` (integration-tests, needs PostgreSQL), `Arch` = `Ui2ArchitectureTest`.

Status: `PROVEN` = a test proves the whole sentence for the steps delivered; `PARTIAL` = delivered part proven, the rest named;
`DEFECT` = disabled test (none); `STEP n` = belongs to a later delivery step.

| ZM | Status | Proof or what is missing |
| --- | --- | --- |
| ZM-1 | PROVEN | `Acc#zm1_partitionPutsEveryAddressInExactlyOneZoneAcrossBothFamiliesAndRefusesDuplicates` (IPv4 and IPv6 random nested blocks, contiguous cover, counts sum to the address space, longest prefix, cross-zone duplicate refused); `Part#partitionEqualsBruteForceLongestPrefixOnRandomBlocks`, `Part#theSameBlockInTwoZonesIsRefusedAndWithinOneZoneIsReported`; service refusal `Svc#invalidEditsChangeNothing` (`ZONE_BLOCK_DUPLICATE`) |
| ZM-2 | PROVEN | `Acc#zm2_publishedVersionStaysByteIdenticalWhileEditsGoToTheNextDraft` (service and store); `Schema#publishedVersionsAreImmutableAndHistoryIsAppendOnly` (trigger level, database test, not run in this lane: no Docker) |
| ZM-3 | PROVEN | `Acc#zm3_citationHashRecomputesFromStoredContent`; `Eval#zm3_zm4_zm5_zm9_statementsVendorZoneIgnoreAndDeterministicCitation`; `Svc#anEditorWithComplianceAdminLetsASecurityAdminPublishButNotAConfirmerOrEditor` publishes hash-bound |
| ZM-4 | PROVEN | `Eval#zm3_zm4_zm5_zm9_...` (vendor zones ignored), `Eval#zm14_...`, `Eval#zm19_zm21_zm27_zm30_...` (a decided violation wins over an undecided IPv6 part); `Acc#zm4_everyUndecidedRuleCarriesAReasonFromTheFixedList` |
| ZM-5 | PROVEN | `Acc#zm5_anyServiceInAllowListedPairAndAnyServiceInDenyAllPairAreViolations`; `Eval#zm5_zm41_unassignedToUnassignedStaysDenyAll...` |
| ZM-6 | PROVEN | `Acc#zm6_disabledDenyAndNatRulesNeverViolateAndAreCountedSeparately` (Palo Alto and Check Point, NAT sections); `Eval#zm6_zm24_zm25_notApplicableCountsSchedulesAndHygieneCoverFlag` |
| ZM-7 | PROVEN | `Acc#zm7_collectionFailureGivesIncompleteCoverageAndNeverZero`; `Eval#zm7_zm26_headlineNeverShowsZeroWhenUndecidedOrIncompleteOrNotEvaluated` |
| ZM-8 | STEP 4 | runs start on completed collections only |
| ZM-9 | PROVEN | `Eval#zm3_zm4_zm5_zm9_...` (same snapshot, version and engine give equal rule findings and headline) |
| ZM-10 | PROVEN | `Eval#zm10_zm33_serviceRbacWitnessAuditMaskingAndZeroSensitiveDataInLogs` (planted names, masked projection, log events); `Svc#viewsHideBlocksUnlessPermittedAndMaskForAiview`; `Two#theMaskedViewHidesNamesTextAndPeople` |
| ZM-11 | STEP 6 | evidence report |
| ZM-12 | PARTIAL | `ZoneMatrixScaleMeasurementTest#zm12_tenLargestSyntheticPoliciesAgainstFiveHundredZonesAndTwoHundredThousandBlocksStayWithinBudget` proves the budget on synthetic policies; the counts-only measurement on the 10 largest stored policies is still open (real environment) |
| ZM-13 | PROVEN | `Arch#zm13_zone_matrix_never_reaches_device_transport_or_commands` (named device-facing packages) and `Arch#zm13_zone_matrix_has_no_dependency_on_the_job_engine_worker_scheduler_cli_or_capability_registry` (the whole job engine, worker, scheduler, CLI, capability registry, directory client) |
| ZM-14 | PROVEN | `Eval#zm14_paloAltoPublicDestinationNatToDenyAllServerNeverCompliantAndCpGroupWithExclusionFailsClosed` |
| ZM-15 | PROVEN | `Eval#identityApplicationsAskInformAndApplicationDefaultFollowStep3UpperBounds` (rule `cp-4` access role with an outside proxy is `NOT_EVALUABLE`, `cp-5` violates with `IDENTITY_IGNORED`); the test named `zm15_nat...` is the NAT dual view, not this criterion |
| ZM-16 | PROVEN | `Eval#zm16_checkPointMultipleOrderedLayersCheckWitnessAgainstOtherLayersAndHeadlineExcludesUnproven` |
| ZM-17 | PROVEN | `Eval#zm17_checkPointApplyLayerParentAndInlineChildIntersection`; `Acc#zm17_applyLayerParentIsNeverActionSemanticsNotModelled` |
| ZM-18 | PROVEN | `Acc#zm18_panDefaultOverrideWithActionAllowCarriesDefaultRule` (local and Panorama pre/post default sections, deny override counted not found); `Eval#zm18_zm20_paloAltoDefaultOverridesAndLocalVsPanoramaCoverage` |
| ZM-19 | PARTIAL | mixed IPv4/IPv6 object gives "IPv6 not evaluated": `Eval#zm19_zm21_zm27_zm30_...`; reporting its IPv4 violation once per-family resolution lands is STEP 7 |
| ZM-20 | PROVEN | `Eval#zm18_zm20_paloAltoDefaultOverridesAndLocalVsPanoramaCoverage` (`PAN_LOCAL_RULES_NOT_COLLECTED`, `PAN_INTRAZONE_DEFAULT_ALLOW`, `PAN_DEFAULT_RULE_LEVELS_NOT_COLLECTED`, coverage INCOMPLETE) |
| ZM-21 | PROVEN | `Eval#zm19_zm21_zm27_zm30_...` (shared rule uid across two sections counts once) |
| ZM-22 | PROVEN | `Svc#publishingNeedsTwoPeopleAndACompliancePerson` (`SELF_APPROVAL` canonical ids, `COMPLIANCE_ROLE_MISSING`, root and replay_viewer `PRINCIPAL_NOT_ELIGIBLE`, `REQUEST_CHANGED`); `Svc#anEditorWithComplianceAdminLetsASecurityAdminPublishButNotAConfirmerOrEditor`; `Two#aConfirmerIsAParticipantOfTheFourEyesRule`; `Acc#zm22_fourEyesCountsTheEditorsOfTheCurrentDraftOnly` |
| ZM-23 | PROVEN | `Two#onlyComplianceConfirmsAndAChangeResetsTheConfirmation`; `Sec#everyRouteResolvesToItsActionAndTheRolesMatchTheContract` (confirm route is compliance_admin only) |
| ZM-24 | PROVEN | `Eval#zm6_zm24_zm25_...` (ended one-time window is `NOT_APPLICABLE` with its date and counted; within the time-zone margin stays `TIME_IGNORED` violation); `Eval#identityApplications...` (`IDENTITY_IGNORED`) |
| ZM-25 | PARTIAL | `COVERED_BY_EARLIER_DENY` with covering rule id on a violation: `Eval#zm6_zm24_zm25_...`. The "same install targets" condition is decided by the hygiene conflict analysis and is not pinned here; "toggling keeps first-observed" is STEP 4 |
| ZM-26 | PARTIAL | evaluation-level headline never renders "0" for incomplete coverage or undecided rules: `Eval#zm7_zm26_...`, `Acc#zm7_...`; "distinct open" findings and a stale policy are STEP 4 |
| ZM-27 | PROVEN | `Eval#zm19_zm21_zm27_zm30_...` |
| ZM-28 | PARTIAL | CSV formula-guard half proven on the evidence report content: `ZoneMatrixEvidenceReportInputsTest#zm28_plantedFormulaReferencesAndRuleNamesAreEscapedByTheR1CsvGuardAndTheBytesHashIsReproducible` (planted `=cmd`, `+1`, `-1`, `@SUM(1)` in reference fields and rule names escaped by `CsvReportRenderer`; CSV bytes and their sha256 reproducible). Missing (step 6b-2): the report is not in the Reports catalogue, is not stored as a file, and no audit row carries the stored file's sha256 written before the first byte is streamed |
| ZM-29 | PROVEN | `Acc#zm29_publishDiffListsMovedAddressesWithEffectiveCounts`; `Part#movesListAddressesThatChangeOwner`; `Svc#addressesLeavingAZoneNeedARationaleAndTheOldVersionRetires` |
| ZM-30 | PARTIAL | headline and Matrix tab show no BDDK pass or score: `Eval#zm19_zm21_zm27_zm30_...`, frontend `ZoneMatrixStepTwo.test.tsx` ("never renders compliant, BDDK, pass, score, percent or green in the Matrix tab"); report title and "N of M cells confirmed" header are STEP 6 |
| ZM-31 | PARTIAL | projection half proven: `ZoneMatrixEvidenceReportInputsTest#zm31_maskedAndRawInputsDifferAndTheMaskedOneCarriesNoPlantedNameAndNoViewCarriesWitnessData` (the same stored data assembled with the masked and the raw `summaryLabels` differs; the masked input and built report carry no planted zone, policy, source, container, rule, service set, actor or reference text; zone names are `ZONE-<n>`; no view carries blocks, witness addresses or NAT views). Missing (step 6b-2): the rights test through a route (which persona gets which view, `report_raw_view` for raw content) and the opt-in blocks appendix (ZM-38) |
| ZM-32 | STEP 6 | report period and resolved findings |
| ZM-33 | PROVEN | `Sec#everyRouteResolvesToItsActionAndTheRolesMatchTheContract` (viewer and aiview hold neither `zone_matrix_zones_read` nor edit, evaluate or publish); `Eval#zm10_zm33_...` (witness refused without the right, `WITNESS_VIEW` audit row, fail-closed when the row cannot be written); `Svc#viewsHideBlocksUnlessPermittedAndMaskForAiview`, `Svc#aViewIsRefusedWhenItsAuditRowCannotBeWritten`, `Svc#addressesLeavingAZoneNeedARationaleAndTheOldVersionRetires` (lookup audit row) |
| ZM-34 | STEP 4 | `BECAME_UNDECIDED`, rule missing from an uncollected scope |
| ZM-35 | STEP 4 | `STALE` and "at least N" on the Overview |
| ZM-36 | PARTIAL | loosening list with required rationale: `Two#theDiffListsEveryLooseningChangeAndPublishNeedsARationale`, `Two#unassignedShrinkingIntoAZoneAsStrictAsUnassignedIsNotLoosening`; findings resolved by a version change listed per version and shown on the Overview for 7 days are STEP 4 and STEP 5 |
| ZM-37 | PARTIAL | audit capture names only ids, change kind and hashes: `Acc#zm37_auditCaptureFunctionsNameOnlyAllowlistedColumns` (migration text) and `Schema#publishedVersionsAreImmutableAndHistoryIsAppendOnly` (database, audit row has `content_sha256` and no `content`; not run in this lane); append-only revisions: same Schema test; no delete route or store method for revisions: `Acc#zm37_noDeleteRouteAndAppendOnlyStoreSurface`. Runs and findings do not exist yet: STEP 4. A database-level test of the exact audit key set was not added because no database was available to run it |
| ZM-38 | STEP 6 | blocks appendix opt-in, `report_raw_view` |
| ZM-39 | STEP 4 | own run pool, heap ceiling, timeout, pod kill |
| ZM-40 | PARTIAL | total-block cap (200,000 accepted, one more refused, draft unchanged), per-zone cap, 500-zone cap, name, description, rationale, service-count and block-text bounds refuse and never truncate: `Acc#zm40_capsRefuseAndNeverTruncate`, `Acc#zm40_requestFieldBoundsAreRefusedNotTruncated`; `Two#cellsFollowTheStatementRulesAndRefusedLinesAreReportedOneByOne` (`TOO_MANY_SERVICES`). No byte-level HTTP body limit exists on the edit routes (`ZoneMatrixController`, outside this lane's fix scope); the size of a request is bounded today only through those field limits |
| ZM-41 | PROVEN | `Two#unassignedToUnassignedIsDenyAllWhateverTheDefault`; `Two#cellsFollowTheStatementRulesAndRefusedLinesAreReportedOneByOne` (`ALLOW_ANY` default refused, `INVALID_DEFAULT`); `Eval#zm5_zm41_unassignedToUnassignedStaysDenyAll...` |
| ZM-42 | STEP 4 | `firstObservedAnyVersion` |

Counts (42 criteria): PROVEN 24, PARTIAL 10, STEP 4 five (ZM-8, ZM-34, ZM-35, ZM-39, ZM-42), STEP 6 three (ZM-11, ZM-32, ZM-38), DEFECT 0.
Partial rows and their open part: ZM-12 (real-environment measurement), ZM-19 (STEP 7), ZM-25 (install-target pin; first-observed STEP 4), ZM-26 (STEP 4), ZM-30 (STEP 6),
ZM-36 (STEP 4/5), ZM-37 (runs and findings STEP 4; exact audit key set at database level), ZM-40 (no byte-level body limit),
ZM-28 (catalogue, stored file and its audit-row sha256: step 6b-2), ZM-31 (route-level rights test and ZM-38 appendix: step 6b-2).
No code contradicted the contract in this pass: no test was disabled and no production code changed.

2026-11-25: step 6a content builder (`ZoneMatrixEvidenceReport`, pure, pins ZM-11, ZM-30 title part, ZM-32), not wired: catalogue, rights, masking cell kind, stored file and ZM-28, ZM-31, ZM-38 remain for a later step.

2026-11-25: DRAFT amendment proposal for Cisco ASA and FortiGate coverage written (`docs/design/ZONE_MATRIX_ASA_FORTIGATE_AMENDMENT_PROPOSAL_2026_11_25.md`, engineering default (a) of the 2026-11-23 midday record); it approves nothing, freezes nothing and changes no contract text. It reports the stale ASA/FortiGate sentence of contract 5.8 as a contradiction and lists 11 questions for the Product Owner.

2026-11-25: CLI parity for the zone matrix routes: `ZonesMatrixCli` (named so because ZM-13 keeps `ZoneMatrix*` classes off the cli package) gives a named `zone-matrix-*` command to each of the 20 `ZoneMatrixController` mappings, pinned by `ZoneMatrixCliParityTest`; rows added to the `NEXUS_CLI.md` command table.
2026-11-25: step 6b-1: evidence report input assembler (`ZoneMatrixEvidenceReportInputs`, pure, stored summary to report input for the caller's screen view) and its projection test; ZM-28 formula-guard half pinned, ZM-31 projection half pinned. Catalogue, route, stored-file audit row, ZM-38 appendix and rights still open for 6b-2.
