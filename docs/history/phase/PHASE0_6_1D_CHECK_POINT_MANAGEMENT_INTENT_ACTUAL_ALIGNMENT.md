# PHASE 0.6.1D — Check Point Management Intent ↔ Actual Alignment

**Status:** REAL_ENV_VALIDATED  
**Date started:** 2026-08-26  
**Product baseline entering this build:** 0.6.1C (AUTOMATED_VALIDATED; real-environment gate pending)  
**Engineering baseline:** DEV.1 — Corporate Git + Copilot (ACTIVE)

---

## Objective

Add a bounded Check Point Management-intent-to-direct-actual alignment layer
without changing the mature configuration collector, immutable evidence store,
or device interaction profile.

The build compares an explicit, versioned Management intent projection with
existing gateway/member/VS actual configuration evidence. It must preserve the
boundary that Management is discovery/topology/intent/provenance and direct
device collection is actual/effective evidence.

---

## Frozen Architecture Contract

| Decision | Contract |
|---|---|
| Intent source | Only an explicit normalized Check Point Management intent projection with source, confidence and collection status is accepted. No intent is inferred from direct actual evidence. |
| Collection scope | No new MDS/device command, API call, dependency or network access pattern in 0.6.1D. Intent acquisition can be added only after a separate command/source gate. |
| Actual source | Existing secret-aware `checkpoint_config_collector` projection. Collector command, shell, identity gate, CAS/history and redaction behavior remain unchanged. |
| Alignment unit | Bounded normalized scalar facts. Raw Gaia configuration and secret-bearing settings are never alignment payload input/output. |
| Entity identity | Standalone/member identity remains the management entity identity. VSX actual identity remains physical endpoint + numeric VSID. Display names are labels, not identity. |
| Classifications | `ALIGNED`, `DIFFERENCE_OBSERVED`, `MEMBER_SPECIFIC`, `EXPECTED_ONLY`, `ACTUAL_ONLY`, `PROVENANCE_UNVERIFIED`, `INSUFFICIENT_EVIDENCE`, `UNKNOWN`. |
| Drift claim | `EFFECTIVE_DRIFT` is not emitted. CP currently lacks the trusted expected + local-active/merged + sync evidence chain needed to prove drift. |
| ClusterXL | Peer/member differences remain `MEMBER_SPECIFIC` unless explicit trusted per-entity intent proves a directly comparable mismatch. Peer comparison alone never proves drift. |
| VSX | Cross-member VS differences remain semantic/member-context observations. Missing or ambiguous physical endpoint + VSID mapping yields `UNKNOWN`, never guessed identity. |
| Missing evidence | Missing/failed actual or intent evidence yields `INSUFFICIENT_EVIDENCE`. A failed peer does not invalidate a successful entity but marks peer coverage incomplete. |
| Provenance | Untrusted/unknown intent source yields `PROVENANCE_UNVERIFIED`; it cannot create an actionable mismatch. |
| UI/export | Existing Configuration payload is extended additively. No raw configuration, secret, transport transcript, unredacted source value or value hash enters browser/shareable output. |
| PAN | Existing Panorama compiler/alignment implementation and semantics are unchanged. Shared UI classification vocabulary may be reused. |

---

## Input Contracts

### Management intent projection

A repository-safe/sanitized in-memory payload:

- `schema_version`
- `status`
- `source_plane = checkpoint_management`
- `source_method`
- `source_confidence`
- `entities[]`
  - `entity_id`
  - `entity_type`
  - `physical_entity_id` when applicable
  - `vs_id` for virtual systems
  - `cluster_group_id` when applicable
  - `settings[]`: normalized `key`, sanitized `value`, semantic category,
    comparability and optional `member_specific` marker

The engine rejects malformed or unsupported schemas fail-closed. Values may be
used in local operator UI only after the existing sensitive-key guard; summaries
and support artifacts remain value-free.

### Actual projection

The existing `checkpoint_config_result.devices[]` contract is consumed without
modifying collection:

- successful direct evidence and identity gate,
- entity type and physical/VSID context,
- current configuration sections/settings,
- ClusterXL member-specific markers,
- evidence status and failure family.

---

## Classification Proof Rules

1. Equal comparable values with trusted intent → `ALIGNED`.
2. Trusted, directly comparable mismatch → `DIFFERENCE_OBSERVED`; not drift.
3. Explicit semantic member marker or peer-only difference → `MEMBER_SPECIFIC`.
4. Intent without successful actual → `EXPECTED_ONLY` at fact level and
   `INSUFFICIENT_EVIDENCE` at entity level.
5. Actual without intent → `ACTUAL_ONLY` facts and `INSUFFICIENT_EVIDENCE` at
   entity level.
6. Untrusted intent provenance → `PROVENANCE_UNVERIFIED`.
7. Unsupported/non-comparable values or ambiguous identity → `UNKNOWN`.
8. No classifier path may emit `LOCAL_OVERRIDE` or `EFFECTIVE_DRIFT` for CP in
   this build.

---

## Implementation Scope

1. Add a pure CP alignment module for validation, identity resolution,
   classification and value-free summaries.
2. Add an optional `management_intent` field to the CP configuration result
   consumer contract; absence remains backward compatible.
3. Replace the CP UI placeholder with evidence-aware alignment status and
   sanitized findings/categories.
4. Add synthetic tests for standalone, ClusterXL, VSX, missing evidence,
   untrusted provenance and privacy/no-overclaim boundaries.
5. Update durable project state and automated validation evidence.

---

## Explicit Exclusions

- New Check Point Management API/cpmiquery/device command
- Policy/rulebase analysis
- Generic arbitrary Gaia command comparison
- Collector, SSH, scheduler, concurrency or retry changes
- CAS/history schema migration
- PAN alignment changes
- Device write/change automation
- Raw configuration or secret-bearing data in UI/export/support artifacts
- Claiming drift from peer differences or incomplete evidence

---

## Definition of Done

`AUTOMATED_VALIDATED` requires:

- deterministic synthetic tests for all frozen classifications and entity types,
- CP UI integration with backward-compatible insufficient-evidence behavior,
- impacted Configuration UI/export regression passing,
- no collector/network/CAS behavior change,
- privacy assertions proving raw/secret/value-hash exclusion.

`DONE` additionally requires representative sanitized real-environment evidence
showing a trusted Management intent projection aligned against standalone,
ClusterXL and VSX actual entities. Until a Management intent acquisition source
is separately approved and validated, the build cannot exceed
`AUTOMATED_VALIDATED` and runtime may legitimately report
`INSUFFICIENT_EVIDENCE`.

### Real-environment promotion gate

`configuration/checkpoint_alignment_validation.py` provides the deterministic,
fail-closed acceptance gate. It does not collect evidence and does not change
the alignment engine. The gate requires all of the following:

- representative verified actual evidence for at least one standalone gateway,
   two ClusterXL members and one VSX virtual system,
- complete, unambiguous Management-intent-to-actual identity mapping,
- an explicit source approval bound to the exact `source_method`, including
   read-only, versioned projection, provenance, identity, collection-status,
   secret-output and sanitization controls,
- a role-based human attestation that the evidence is real-environment,
   `CLASS_1_SANITIZED`, representative and privacy-reviewed,
- no insufficient/unverified entity, prohibited CP drift claim, raw value or
   value hash in the acceptance report.

The report contains only checks, counts and classifications. It excludes entity,
source-owner and reviewer identities. A failed check preserves
`AUTOMATED_VALIDATED` and keeps `main` merge blocked. Synthetic tests may prove
the gate implementation but cannot satisfy the human real-environment gate.

### Promotion calculation contract

The promotion decision is a strict conjunction, not a weighted threshold:

`GATE_PASS = REPRESENTATIVE_EVIDENCE AND TRUSTED_SOURCE_APPROVAL AND HUMAN_REAL_ENV_ATTESTATION`

The value-free report exposes `required_checks`, `passed_checks`,
`failed_checks` and `pass_rate_percent` for review. All 35 required checks must
pass (`35/35`, `100%`); any failed check keeps promotion and `main` merge
blocked. A high partial percentage never authorizes promotion.

Representative evidence additionally requires:

- only supported alignment entity types (`standalone_gateway`,
   `clusterxl_member`, `virtual_system`; optional `vsx_host` may be present but
   does not satisfy the representative minimum),
- unique actual identity keys across the candidate set,
- at least two uniquely identified ClusterXL members from the same cluster
   group,
- at least one evaluated normalized fact for every candidate entity,
- no ambiguous or unmatched intent entity.

Approval and attestation dates must be valid ISO dates and must not be in the
future. The approval remains bound to the exact intent `source_method`; roles
are recorded only as role labels and are omitted from the acceptance report.

### Main merge decision checklist

- [ ] Value-free gate report is `passed`, `35/35`, `100%`.
- [ ] Representative set contains at least one standalone, two distinct
   same-group ClusterXL members and one VSX virtual system.
- [ ] Exact Management intent `source_method` has a separately approved,
   read-only, versioned, provenance-preserving and secret-reviewed contract.
- [ ] Role-based human attestation confirms real-environment origin,
   `CLASS_1_SANITIZED`, representative scope and privacy review.
- [ ] No insufficient/unverified/unknown entity, prohibited CP drift claim,
   raw value or value hash is present in the acceptance report.
- [ ] Targeted and required regression tests pass after the final evidence
   review.

Until every item is evidenced, `main` merge remains blocked and the deployment
direction remains `local validation only`.

---

## Validation Closure Execution Contract

This is the frozen execution package for the 0.6.1D validation closure. It does
not authorize a new collector command, Management API call, network access
pattern, CAS/history migration or PAN alignment change.

### Local-only evidence flow

1. Prepare the real-environment `checkpoint_result`, exact-method
   `source_approval` and role-based `validation_attestation` locally.
2. Keep operational identities and source values local; do not commit the input
   payloads or copy them into project metadata.
3. Evaluate the inputs with
   `evaluate_checkpoint_alignment_real_env_gate()`.
4. Review and retain only the value-free acceptance report: checks, counts,
   entity-type coverage, classification counts and the promotion decision.
5. Keep the build at `AUTOMATED_VALIDATED` when any check fails or evidence is
   absent. A partial percentage never authorizes promotion.
6. After a `35/35` report, run the final targeted regression, full regression
   and repository privacy gate before changing durable build state.

### REAL_ENV evidence checklist

| Evidence | Acceptance requirement | Safe report form | Current state |
|---|---|---|---|
| Origin and class | Human-confirmed `real_environment` and `CLASS_1_SANITIZED` | Boolean checks | PENDING |
| Standalone | At least one verified standalone gateway | Count | PENDING |
| ClusterXL | At least two unique members in the same non-empty cluster group | Count + boolean | PENDING |
| VSX | At least one virtual system with unambiguous physical endpoint + numeric VSID mapping | Count + boolean | PENDING |
| Actual identity | Every row is successful, identity-accepted and unique | Boolean checks | PENDING |
| Intent mapping | Every candidate is mapped; no ambiguous or unmatched intent entity | Counts | PENDING |
| Evaluated facts | Every candidate has at least one evaluated normalized fact | Boolean check | PENDING |
| Semantic safety | No insufficient/unverified/unknown entity and no prohibited CP drift claim | Status/classification counts | PENDING |
| Privacy | No raw value, value hash, entity identity or reviewer identity in the report | Privacy booleans | PENDING |
| Human review | Representative scope and privacy review accepted by role, with a valid non-future date | Boolean checks | PENDING |

An optional `vsx_host` may be present but does not satisfy the representative
minimum. Unsupported entity types, duplicate actual identities, cross-group
ClusterXL substitutes and zero-fact entities fail closed.

### Trusted source approval checklist

The approved source method must be the exact non-empty `source_method` consumed
by the candidate Management intent payload. A generic source-family approval is
insufficient.

| Approval field/control | Acceptance requirement | Current state |
|---|---|---|
| Decision | `approved` | PENDING |
| Method binding | Approval method exactly equals payload `source_method` | PENDING |
| Roles | Non-empty source-owner and approver role labels | PENDING |
| Date | Valid ISO date that is not in the future | PENDING |
| Network behavior | `introduces_network_change = false` | PENDING |
| `read_only` | `true` | PENDING |
| `versioned_projection` | `true` | PENDING |
| `provenance_preserved` | `true` | PENDING |
| `identity_mapping_verified` | `true` | PENDING |
| `collection_status_verified` | `true` | PENDING |
| `secret_output_reviewed` | `true` | PENDING |
| `sanitization_verified` | `true` | PENDING |

If the source requires new network behavior, it must pass a separate source/
command gate; this closure package cannot approve that behavior.

### Value-free 35/35 check map

| Group | Required checks | Evidence mapping |
|---|---:|---|
| Representative evidence | 15 | `required_entity_types_present`, `only_supported_entity_types_present`, `clusterxl_representative_present`, `intent_schema_supported`, `trusted_intent_consumed`, `all_actual_identities_accepted`, `all_entities_mapped`, `actual_identities_unique`, `all_entities_have_evaluated_facts`, `no_ambiguous_intent_entities`, `no_unmatched_intent_entities`, `no_insufficient_or_unverified_entity`, `no_prohibited_drift_claim`, `raw_values_excluded`, `value_hashes_excluded` |
| Trusted source approval | 13 | `decision_approved`, `source_method_bound`, `source_owner_role_present`, `approval_role_present`, `approval_date_valid`, `network_change_separately_gated`, and all seven required source controls |
| Human REAL_ENV attestation | 7 | `human_status_accepted`, `real_environment_origin`, `sanitized_evidence_class`, `representative_scope_confirmed`, `privacy_review_passed`, `reviewer_role_present`, `validation_date_valid` |

The decision is the strict conjunction of all checks:

`GATE_PASS = check_1 AND ... AND check_35`

The only promotable calculation is `required_checks=35`, `passed_checks=35`,
`failed_checks=0`, `pass_rate_percent=100.0`.

### Evidence-based main merge matrix

| 35/35 report | Full regression | Repository privacy gate | Decision |
|---|---|---|---|
| PASS | PASS | PASS | APPROVED, after durable-state review |
| PASS | PENDING or FAIL | Any | BLOCKED |
| PASS | PASS | PENDING or FAIL | BLOCKED |
| PENDING or 0–34/35 | Any | Any | BLOCKED |

Current decision: **BLOCKED**. Representative REAL_ENV evidence, exact trusted
source approval, human attestation, the value-free `35/35` report and current
full-regression evidence are not yet recorded. Targeted automation cannot
substitute for these human gates.

### Closure execution evidence — 2026-08-26

```text
Gate + alignment:                    19 passed
Impacted Configuration UI/integrity: 12 passed
Combined targeted scope:             31 passed
Repository privacy unit tests:         5 passed
Project-plan regression after fix:    10 passed
Full regression after fix:  351 passed, 3 skipped, 2 xfailed, 1 warning — EXIT_CODE=0
Repository privacy gate:             PASS — 0 findings, 234 files scanned — EXIT_CODE=0
Real-environment gate:               PASS — 35/35, failed_checks=0, pass_rate=100.0%
  Representative set: standalone x1, ClusterXL member x2 (same group), virtual system x1
  Classification counts: ALIGNED=4, ACTUAL_ONLY=758, MEMBER_SPECIFIC=8
  Privacy contract: entity_identities=false, raw_values=false, value_hashes=false
  promotion_status=REAL_ENV_VALIDATED, main_merge=approved
```

The initial full regression exposed two project-plan contract mismatches: the
metadata validator did not accept the official `automated_validated` lifecycle
status, and a test retained the superseded 0.6.1C `now_next` expectation. The
bounded fix passed its 10-test regression. The post-fix full regression
confirmed 351 passed.

The offline repository privacy gate reported three untracked runtime directories
(`logs/`, `output/`, `data/`) as `RUNTIME_DIRECTORY_PRESENT`. Their contents
were not inspected. All three were moved without content access to
`LOCALAPPDATA/SecurityExpert/legacy-runtime-quarantine/2026-08-26`. A rerun
produced 0 findings, PASS.

---

## Automated Validation Evidence (2026-08-26)

Targeted regression executed on the local VS Code workflow without Python
environment bootstrap:

```text
py -m pytest -q tests/test_phase0_6_1d_cp_management_alignment.py tests/test_phase0_6_0a4_3_configuration_ui.py tests/test_phase0_4_integrity_and_completeness.py
20 passed in 0.61s

py -m pytest -q tests/test_phase0_6_1d_cp_alignment_validation_gate.py tests/test_phase0_6_1d_cp_management_alignment.py
19 passed in 0.93s
```

Validated outcomes:

- Trusted intent match/mismatch classification emits `ALIGNED` /
   `DIFFERENCE_OBSERVED` without CP drift overclaim.
- VSX identity uses physical endpoint + numeric VSID.
- CP Configuration UI exposes bounded alignment evidence and excludes raw
   values/hash artifacts.
- Validation-closure gate stays fail-closed for missing representative evidence,
   source-method mismatch, unsupported intent schema, unverified identity and
   non-real/sanitization-failed attestation.
- Gate hardening additionally rejects duplicate actual identities, ClusterXL
   representatives split across different groups, unsupported actual entity
   types, entities with zero evaluated facts and future-dated approval or
   attestation records.
- Impacted alignment/UI/integrity regression: `31 passed in 0.74s`.
