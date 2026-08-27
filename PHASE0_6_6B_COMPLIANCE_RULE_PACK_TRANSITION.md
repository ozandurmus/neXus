# 0.6.6B — Compliance Rule-Pack Transition Foundation

## Status

**PLANNED — architecture contract frozen 2026-08-27**

Product baseline: `0.6.5 REAL_ENV_VALIDATED`.

## Objective

Introduce a minimal versioned, vendor-neutral compliance rule-pack execution
boundary over the existing normalized CP/PAN evidence and the existing ten
deterministic compliance controls. This is the bounded bridge from the 0.6.1B
posture/control-pack foundation toward the formal 0.7.x compliance engine.

## Scope

### In scope

- Audit `utils/compliance_posture.py`, its existing CP/PAN adapters and its
  current UI payload consumer.
- Define an in-repository, versioned rule-pack model with explicit metadata:
  `pack_id`, `version`, `control_id`, applicability, evidence fields,
  evaluation state and benchmark/reference metadata.
- Adapt the existing ten deterministic controls to execute through one default
  rule pack without changing their observed PASS/FINDING/UNKNOWN/
  NOT_APPLICABLE/PLANNED outcomes.
- Preserve existing traceability in the additive UI payload; expose pack and
  version only if they are safe, static metadata and can be rendered without
  interaction redesign.
- Add deterministic synthetic CP/PAN tests proving rule-pack version,
  control identity, applicability and no-certification semantics.

### Explicitly out of scope

- New collection commands, vendors, network access, device writes, policy
  install, remediation or scheduler behavior.
- New facts derived from raw configuration, credentials, topology or runtime
  artifacts.
- External framework certification claims, compliance scoring guarantees,
  automatic framework attestation or a database/CAS migration.
- OIDC/deployment/server work, secret/vault integration, event/webhook intake,
  TI egress, native backup or broad Compliance UI redesign.

## Architecture decision

The rule pack is a **local, declarative evaluation boundary**, not a policy
source of truth and not a certification engine. Each control receives only the
existing normalized, privacy-safe evidence projection. Rule evaluation returns
the current bounded state plus traceability; absence/insufficiency of evidence
must remain `UNKNOWN` or `PLANNED`, never a guessed PASS.

The first pack is in-repository and static. Dynamic uploads, remote pack
download, runtime modification and tenant-specific policy overrides are
explicitly deferred until deployment-era governance and signature decisions.

## Compatibility and privacy contract

1. The existing 10 control IDs, meanings, evidence fields and state semantics
   remain stable unless a test demonstrates an existing defect.
2. UI consumers continue to receive the existing payload shape; any rule-pack
   metadata is additive and static.
3. No raw configuration, secrets, real device/network identity, endpoint,
   credential, file path or certificate content is included in rules, test
   fixtures, reports or UI payload.
4. Framework/benchmark references remain evidence-area traceability only and
   must never state or imply certification/compliance attestation.
5. Evaluation is deterministic, offline and side-effect free.

## Implementation plan

1. Map the existing control evaluator inputs/outputs and tests; freeze their
   current contract before adapter changes.
2. Create a small rule-pack schema and default pack with a version identifier.
3. Route the existing controls through the pack evaluator while preserving
   their deterministic adapters and state outcomes.
4. Add additive traceability projection only where an existing consumer can
   safely render it; otherwise retain it in internal payload for later UI work.
5. Add focused synthetic tests plus affected compliance/UI regression and
   repository privacy gate.
6. Run `--render-only` to ensure the current configuration presentation stays
   healthy; do not run network collection for this build.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | A static default rule pack has immutable `pack_id` and version metadata. |
| AC-2 | All existing ten controls execute through the rule-pack boundary with stable IDs and equivalent state outcomes for existing synthetic evidence. |
| AC-3 | Every evaluated control records bounded applicability, evidence-field and benchmark/reference traceability. |
| AC-4 | Missing or insufficient evidence produces `UNKNOWN`/`PLANNED` rather than an inferred PASS. |
| AC-5 | Existing fleet/subject partitioning and existing UI payload consumers remain backward-compatible; new safe metadata is additive only. |
| AC-6 | Rule packs and outputs contain no secret, raw config, real identity or certification claim. |
| AC-7 | Targeted compliance tests, impacted UI/render regression and repository privacy gate pass. |

## Validation and merge gate

This build introduces no network-facing behavior, so synthetic evidence tests
and `--render-only` are the required validation path. Merge to `main` is
blocked until AC-1 through AC-7 pass, the default pack is reviewable and stable,
and a diff review confirms no collector/CAS/scheduler/storage semantics changed.

## Definition of done and follow-up

`DONE` means the rule-pack boundary is automated-validated and the durable
project state links it to `compliance_posture_rulepack_transition`. Formal
framework governance, signed/dynamic packs, scoring, certification assertions
and additional crypto/PQC controls remain 0.7.x work.
