# Architectural Review — `ui2-compliance`

## Verdict: [APPROVED WITH RECOMMENDATIONS]

The dedicated, stateless compliance-service direction is architecturally sound. Approval is conditional and design-level only: the current specification remains a [DRAFT](/Users/OzanDur/Codo/nexus/docs/design/UI2_COMPLIANCE_MICROSERVICE_ARCHITECTURE.md:3), while both cited compliance documents are `DESIGN`, and the configuration-microservice precedent is still `CONSENSUS DRAFT`. They do not authorize implementation or deployment under the repository hierarchy.

The concept should proceed to a `CONTRACT` movement after the mandatory issues below are resolved.

## 1. Service boundary and statelessness

Strict statelessness is the correct boundary:

- `ui2-service` owns authentication, authorization, assignments, waivers, orchestration, PostgreSQL transactions, and UI responses.
- `ui2-compliance` owns deterministic evaluation only.
- `ui2-compliance` must have no database, SSH/API, credential, device-network, mutable catalog, or external Ingress access.
- The request should contain a typed, sanitized evidence envelope—not raw configuration text or encrypted artefacts.
- The response should contain verdicts, safe reason codes, evidence references/fingerprints, and aggregates—not raw observed values.
- Configuration collection must remain successful even if compliance evaluation fails; record a failed evaluation run without rolling back collected evidence.

The architecture diagram’s worker-to-compliance path should be removed. `ui2-worker` should notify or call `ui2-service`; only `ui2-service` should invoke `ui2-compliance`. This preserves one orchestration and persistence owner.

`POST /api/v1/compliance/evaluate` is appropriate if it is bounded by payload size, device count, timeout, and an idempotency key. Retries are safe because evaluation is a pure function.

Built-in controls should remain an immutable, versioned catalog packaged with the service. PostgreSQL should store catalog version/hash and evaluation snapshots, not become the authoring source for built-in definitions.

## 2. Rule and evidence model

The fixed declarative model is adequate for the initial release and matches the existing repository direction: fixed selectors, no `eval`, and an explicit operator vocabulary already exist in the design ([selectors and operators](/Users/OzanDur/Codo/nexus/docs/design/COMPLIANCE_CHECK_ENGINE.md:123)).

Use the existing complete operator set:

`present`, `absent`, `equals`, `not_equals`, `matches`, `not_match`, `any_match`, `none_match`, `gte`, `lte`, `in`, `not_in`, `count_gte`, `count_lte`.

Required corrections:

- Remove `evidence_requirement.is_collected`. Collection availability is run-specific, not catalog metadata.
- Replace `required_command` with `required_evidence_id` and, where applicable, a gate-controlled `primitive_id`. The exact device command belongs in the approved command registry, not each control definition.
- Add `evidence_basis`: `configured`, `direct_runtime`, `management_observed`, or `inferred`.
- Add vendor/platform/software applicability and a `control_semantics_hash`.
- Distinguish missing, null, empty, malformed, stale, and unsupported evidence.
- Apply regex length, complexity, and execution-time guards already required by the existing design.
- `NOT_APPLICABLE` must require a proven applicability predicate. An unproven predicate produces `UNKNOWN`, not `NOT_APPLICABLE`.

Do not create separate Check Point and PAN-OS rule engines. Use one rule engine plus vendor-specific evidence normalizers/adapters. Add a registered custom evaluator only when a real control cannot be expressed through the fixed operators; never allow arbitrary evaluator code from a catalog.

The engine’s `PASS` means “the assertion passed against the declared evidence plane,” not “the organization satisfies the complete regulatory requirement.”

## 3. Missing evidence and scoring

The hard missing-evidence requirement should be represented without breaking the repository’s established `UNKNOWN` semantics:

```json
{
  "verdict": "UNKNOWN",
  "reason_code": "EVIDENCE_MISSING",
  "display_status": "DATA_UNAVAILABLE",
  "missing_evidence": [{
    "evidence_id": "gaia.ssh.negotiated_ciphers",
    "primitive_id": "cp_gaia_ssh_ciphers_v1"
  }]
}
```

Additional reason codes should include:

- `COLLECTION_FAILED`
- `STALE_EVIDENCE`
- `PARSER_INCONCLUSIVE`
- `UNSUPPORTED_PLATFORM_VERSION`
- `SEMANTICS_UNVERIFIED`

Every assigned, applicable control must emit an item even when evidence is absent. This aligns with the existing “no evidence → UNKNOWN, never inferred PASS” rule ([engine contract](/Users/OzanDur/Codo/nexus/docs/design/COMPLIANCE_CHECK_ENGINE.md:127)).

Do not publish a single unsupported percentage. For assigned, applicable, non-advisory cells:

- Observed compliance: `PASS / (PASS + FAIL)`
- Evidence coverage: `(PASS + FAIL) / (PASS + FAIL + DATA_UNAVAILABLE)`
- Assurance lower bound: `PASS / (PASS + FAIL + DATA_UNAVAILABLE)`

If `PASS + FAIL = 0`, observed compliance is `null / NOT_EVALUABLE`, never `0%` or `100%`.

The UI should therefore display something like:

> 88% observed compliance · 72% evidence coverage · 6 data gaps

Severity-weighted posture can be a separate risk score. Do not call arbitrary severity weighting “regulatory compliance.”

A waiver should be a disposition over an underlying result:

```text
verdict=FAIL, disposition=WAIVED
```

This preserves technical truth. Waivers require approver, reason, ticket, creation time, and expiry.

For drift, keep three independent measures:

- Outcome drift: `PASS ↔ FAIL` over comparable cells.
- Coverage drift: evidence became available/unavailable.
- Scope drift: assignments or applicability changed.

Only compare cells with the same subject, control ID, and semantic hash. Catalog-version changes are otherwise `NOT_COMPARABLE`. If one headline drift score is required, use the percentage of comparable cells whose `PASS/FAIL` outcome changed and show regressions and improvements separately.

## 4. PostgreSQL assignment and evaluation model

Recommended minimal schema:

| Table | Essential content |
|---|---|
| `compliance_catalog_release` | Catalog ID, version, content hash, engine compatibility, activation time |
| `compliance_profile` | Immutable profile version and framework/version metadata |
| `compliance_profile_control` | Profile version to control IDs |
| `compliance_assignment` | Opaque target ID, target type, control/profile reference, include/exclude effect, validity, actor, audit metadata |
| `compliance_waiver` | Subject/control, underlying finding, reason, approver, ticket, expiry |
| `compliance_eval_run` | Run ID, trigger, evidence-run reference, catalog/assignment hashes, engine version, timestamps, status, aggregate numerators/denominators |
| `compliance_eval_item` | Run, opaque subject ID, control/version/hash, verdict, reason code, disposition, severity, evidence basis/reference/fingerprint |

Constraints:

- Unique evaluation item on `(run_id, subject_id, control_id)`.
- Unique idempotency key for evaluation runs.
- Store no raw configuration, command output, IP address, hostname, or secret-bearing observed value.
- Profile versions are immutable; historical runs retain the exact version/hash used.
- Device-specific assignment overrides group assignment; ambiguity between equal-precedence groups fails closed.
- Unknown control/profile IDs fail the assignment transaction.
- Do not use `FW-PCI-*` hostname patterns as assignment identity. Presentation names are not security identifiers. Use opaque device IDs or registry group/tag IDs.

The catalog control remains assigned while evidence is unavailable; when the required evidence appears, the next run evaluates it automatically.

## 5. Regulatory mapping corrections

The draft should use PCI DSS v4.0.1. PCI DSS v4.0 was retired after 31 December 2024, and v4.0.1 is the supported version. [PCI SSC v4.0.1 announcement](https://blog.pcisecuritystandards.org/just-published-pci-dss-v4-0-1)

Several proposed values are stricter organizational baselines, not exact PCI thresholds:

- PCI 8.3.7 refers to the last four passwords, while the draft requires five. [PCI SSC assessment text](https://listings.pcisecuritystandards.org/documents/PCI-DSS-v4-0-SAQ-D-Merchant.pdf)
- PCI 8.3.4 allows lockout after no more than ten attempts, while the draft uses five. [PCI SSC assessment text](https://listings.pcisecuritystandards.org/documents/PCI-DSS-v4-0-SAQ-C.pdf)
- PCI 8.2.8 requires reauthentication after more than fifteen idle minutes, while the draft uses ten. [PCI SSC FAQ](https://www.pcisecuritystandards.org/faqs/1147/)

These are good stricter policies, but encode them as `mapping_type: ORG_STRICTER_THAN`, not as direct proof of the PCI requirement.

Further requirements:

- Call framework cards “mapped-control posture,” not certification or attestation. The existing design already preserves that boundary ([mapping semantics](/Users/OzanDur/Codo/nexus/docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md:80)).
- Pin CIS Palo Alto benchmarks to product major and benchmark version. CIS currently lists distinct Palo Alto Firewall 10 and 11 benchmarks. [CIS benchmark catalog](https://www.cisecurity.org/cis-benchmarks)
- NIST mappings must identify SP 800-41 Rev. 1 and the exact SP 800-53 release. NIST published Release 5.2.0 updates in 2025. [SP 800-41](https://csrc.nist.gov/pubs/sp/800/41/r1/final), [SP 800-53](https://csrc.nist.gov/pubs/sp/800/53/r5/upd1/final)
- ISO must be pinned to ISO/IEC 27001:2022 and its applicable amendment/crosswalk. [ISO](https://www.iso.org/standard/27001)
- BDDK mappings must reference an exact regulation, article, publication date, and applicability—not merely “BDDK.”
- COBIT mappings are governance alignment, not device-level regulatory compliance.
- Every framework mapping needs provenance, version, applicability, mapping type, and reviewer approval.
- Resolve CIS commercial licensing before embedding benchmark content. CIS states that commercial product integration requires Product Vendor licensing. [CIS licensing](https://www.cisecurity.org/cis-securesuite/pricing-and-categories/product-vendor)

## 6. UI and `aiview`

The table-first Compliance screen is appropriate. However, the cited frozen replay contract currently allows only `GET /devices` and `GET /devices/{deviceId}` and refuses unclassified surfaces ([C9 surface boundary](/Users/OzanDur/Codo/nexus/docs/design/UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md:122), [fail-closed behavior](/Users/OzanDur/Codo/nexus/docs/design/UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md:226)).

Therefore, Compliance APIs and the screen must return the existing refusal envelope to `aiview` until a successor frozen field-classification contract is approved.

That classification must cover:

- Device and group IDs/names
- Exact device counts and small-group inference
- Custom control titles and rationale
- Expected and observed values
- Missing-command text
- Assignment/profile names
- Waiver reasons, approvers, and ticket references
- Run and evidence IDs
- Search, caching, and exports

For `aiview`:

- Pseudonymize identities only in the existing server-side response funnel.
- Never return raw observed values, internal subnet/IP data, selectors, patterns, waiver text, or ticket IDs.
- Search only projected values.
- Make cache keys role/projection-aware.
- Refuse downloads/exports until separately classified.
- Suppress exact command strings; show a safe collection-capability label.

Remove executable “remediation CLI command” from v1. Show non-executable remediation guidance only. The browser must never become a device-command path.

## 7. K3s feasibility

Port `8085` behind an internal `ClusterIP` is feasible. Reuse the configuration service’s hardened pod pattern ([existing deployment specification](/Users/OzanDur/Codo/nexus/docs/design/UI2_CONFIGURATION_MICROSERVICE_ARCHITECTURE.md:230)):

- Non-root
- Read-only root filesystem
- No privilege escalation
- Drop all capabilities
- Runtime-default seccomp
- Disable service-account token automount
- No external Ingress
- NetworkPolicy ingress from `ui2-service` only
- Default-deny egress

Use separate probes:

- `/healthz`: process liveness only.
- `/readyz`: catalog loaded, validated, and engine ready.
- Catalog validation failure makes the pod unready.

The existing `64Mi/50m` request and `256Mi/200m` limit may be used as a starting hypothesis, not a frozen capacity claim. Validate it with cold start, maximum allowed request size, and representative fleet/control-count tests. The current repository remains development-ready rather than production-ready and still lists NetworkPolicy, OIDC/RBAC, database separation, and secret management as incomplete ([current posture](/Users/OzanDur/Codo/nexus/CURRENT_STATE.md:61)).

## Mandatory actions before contract freeze

1. Make `ui2-service` the sole caller, persistence owner, and orchestration boundary.
2. Replace dynamic `is_collected` and raw command strings with evidence/capability IDs.
3. Freeze the missing-evidence taxonomy and the three-part scoring contract.
4. Freeze deterministic assignment precedence, profile versioning, waiver semantics, and schema constraints.
5. Correct regulatory versions/mappings and complete CIS/ISO/COBIT licensing review.
6. Add a frozen `aiview` Compliance field-classification extension; refuse access until then.
7. Remove executable remediation CLI from v1.
8. Define K3s resource validation, readiness, request limits, timeout, and NetworkPolicy enforcement evidence.
9. Require golden tests for deterministic evaluation, missing-evidence retention, catalog mismatch, idempotency, scoring, drift comparability, and adversarial privacy leakage.

## SESSION CLOSE

Read-only architectural review completed. No source, configuration, project state, deployment, database, device, or production data was changed. No tests or live-cluster validation were run. Git showed pre-existing untracked draft/consultation files; this review did not create or modify them.

Next movement: `CONTRACT`, High reasoning, revising and freezing the specification around the mandatory actions above. Implementation and merge remain blocked until that contract, regulatory provenance, privacy classification, schema migration plan, and validation plan are approved.

