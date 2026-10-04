# Phase 0.6.0A4.2.1 — PAN Semantic Validation

## Goal

A4.2 proved that the engine can compare compiled Panorama Template-Stack scalar intent with direct firewall `effective-running` state and produce bounded classifications. The first real fleet run showed a useful pattern:

- most evaluated settings aligned;
- a small number were classified `LOCAL_OVERRIDE`;
- no unexplained `EFFECTIVE_DRIFT` was observed;
- `EXPECTED_ONLY` and `LOCAL_ONLY` remained large enough that path/schema coverage still needs validation.

A4.2.1 does **not** add a more aggressive drift detector. It validates A4.2 semantics conservatively before those findings are exposed as a product feature.

The phase has two jobs:

1. generate a small deterministic manual-validation checklist for high-value classifications;
2. look for conservative `EXPECTED_ONLY` ↔ `LOCAL_ONLY` schema-equivalence hypotheses without automatically changing either classification.

## Vendor semantic basis

Palo Alto supports firewall-specific local overrides of values pushed from Panorama Templates or Template Stacks. A local override is therefore a legitimate configuration state, not automatically a misconfiguration. Template/Stack variables can also be overridden per device. These vendor semantics are why A4.2.1 preserves `LOCAL_OVERRIDE` as an observation and keeps unresolved variables out of exact drift claims.

Official references:

- https://docs.paloaltonetworks.com/panorama/administration/manage-firewalls/manage-templates-and-template-stacks/override-a-template-setting
- https://docs.paloaltonetworks.com/panorama/administration/manage-firewalls/manage-templates-and-template-stacks/override-a-template-setting/override-a-template-setting-on-the-firewall
- https://docs.paloaltonetworks.com/panorama/administration/manage-firewalls/manage-templates-and-template-stacks/configure-template-or-template-stack-variables
- https://docs.paloaltonetworks.com/panorama/getting-started/panorama-overview/centrally-manage-firewall-configuration-and-updates-with-panorama/templates-and-template-stacks

## No automatic reclassification

A4.2.1 never changes an A4.2 finding merely because two values look related.

```text
A4.2 result
   │
   ├── LOCAL_OVERRIDE
   ├── EXPECTED_ONLY
   ├── LOCAL_ONLY
   └── ...
         │
         ▼
A4.2.1 semantic validation
   │
   ├── deterministic manual sample
   └── possible schema-equivalence hypothesis

NO automatic promotion to ALIGNED
NO automatic promotion to DRIFT
```

This is deliberate. The phase is intended to reduce false positives, not maximize finding count.

## Manual-validation samples

The engine selects a small fleet-representative sample instead of dumping thousands of paths.

Default per-category caps are intentionally small:

- up to 4 `LOCAL_OVERRIDE`
- up to 4 `EFFECTIVE_DRIFT`
- up to 4 `PANORAMA_OUT_OF_SYNC`
- up to 2 `UNKNOWN`
- up to 1 `EXPECTED_ONLY`
- up to 1 `LOCAL_ONLY`
- up to 2 `POSSIBLE_SCHEMA_EQUIVALENT`

Selection is deterministic for the same evidence so repeated runs should choose the same sample set unless configuration changes.

The local operator report can show:

```text
sample_id
firewall identity
semantic category
normalized setting path
Panorama expected source
expected value
local-active value
merged value
effective value
manual_result = PENDING
```

Sensitive-looking paths such as password/secret/private-key/pre-shared-key/auth-key/community/API-key are redacted even in the local operator report.

## Possible schema equivalence

The first A4.2 fleet run showed a large and very similar count of `EXPECTED_ONLY` and `LOCAL_ONLY` settings. A4.2.1 tests whether a subset might represent the same scalar through different XML path shapes.

A candidate requires all of the following:

```text
same device
same semantic category
same leaf tag
same scalar SHA-256
strong normalized path-shape similarity
clear best match when multiple candidates exist
```

Presence-only leaves are excluded from this heuristic because they are too ambiguous. Common values are also protected by the path-shape and unique-best-match requirements.

A match is recorded only as:

```text
POSSIBLE_SCHEMA_EQUIVALENT
```

and explicitly:

```text
promoted_to_aligned = false
```

A later normalized-fact layer may formalize vendor-specific schema aliases after manual validation.

## Local artifacts

Detailed hash/path manifest:

```text
data/derived/panorama_semantic_validation/<run_id>/semantic-validation.json
```

This contains real device identity, paths and hashes, but no raw config values.

Manual operator report:

```text
output/pan_semantic_validation_<run_id>.json
```

Manual checklist CSV:

```text
output/pan_semantic_validation_samples_<run_id>.csv
```

The JSON/CSV checklist is **LOCAL ONLY** and may contain selected non-sensitive configuration values. It must not be uploaded as a normal support artifact.

## Shareable support boundary

`config_support_<run_id>.zip` includes only aggregate semantic-validation telemetry:

- engine status
- manual sample count
- sample counts by classification
- possible schema-equivalent candidate count
- candidate counts by semantic category
- unexplained expected-only/local-only counts
- manual confirmation state

It does not include:

- sample setting paths
- raw sample values
- value hashes
- real device identity
- Template/Stack names
- local semantic-validation JSON/CSV

## Gate semantics

A4.2.1 distinguishes automation readiness from human semantic confirmation.

```text
a4_2_stage_pass
    existing A4.2 collection/alignment engine gate

semantic_validation_engine_gate
    semantic analysis completed
    + local semantic artifacts published

a4_2_1_engine_pass
    a4_2_stage_pass
    AND semantic_validation_engine_gate
```

The actual semantic phase verdict remains unset until manual samples are checked:

```text
a4_2_1_manual_validation_required = true
a4_2_1_stage_pass = null
manual_confirmation_status = pending
```

This prevents an automated heuristic from declaring itself semantically correct.

## Operator validation workflow

Run from the management-reachable VM:

```powershell
py.exe .\main.py
```

Then inspect locally:

```text
output\pan_semantic_validation_samples_<run_id>.csv
```

For each selected `LOCAL_OVERRIDE` sample, verify the Panorama Template/Stack source and the firewall's local/effective value. A PASS means the A4.2 classification agrees with the vendor UI/CLI semantics.

For `POSSIBLE_SCHEMA_EQUIVALENT`, verify only whether the two different normalized paths describe the same effective setting. Do not treat a candidate as aligned until that relationship is confirmed.

When reporting results back for analysis, the operator only needs to provide:

```text
sample_id, PASS|FAIL|UNKNOWN
```

Raw values are not required.

## Read-only boundary

A4.2.1 performs no write operation on Panorama or a firewall. It does not commit, push, override, save, export, SCP or SSH. All additional processing is local analysis of already-collected read-only evidence.
