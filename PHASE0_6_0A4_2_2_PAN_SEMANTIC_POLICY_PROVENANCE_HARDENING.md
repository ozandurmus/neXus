# Phase 0.6.0A4.2.2 — PAN Semantic Policy & Provenance Hardening

## Goal

A4.2.2 hardens the setting-level Alignment engine using real operator validation from the production-like Panorama/PAN-OS fleet. The phase does **not** add write operations and does not change the validated direct HTTPS collection method.

The central rule is:

```text
XML/hash difference
      !=
configuration drift
```

A difference can represent a real local override, a member-relative HA value, an unverified source/provenance relationship, or two names for the same logical identity.

## Manual-validation findings incorporated

The A4.2.1 checklist established four important semantic cases:

1. A permitted-management-IP description difference was verified as a real local override pattern.
2. A high-availability peer-address difference was shown to be member-relative and is not generic override proof.
3. Device telemetry values could be observed on the firewall, but the compiled Template source could not be independently verified in the operator Template UI. These mismatches are provenance-guarded.
4. A VSYS-friendly name on the Panorama/Template side and an internal `vsysN` identifier on the firewall side can represent the same virtual system. This is identity translation, not an override.

## A4.2.2 semantic policies

### 1. Member-specific HA policy

Narrowly validated HA peer-address paths are excluded from generic override/drift classification:

```text
expected peer address != firewall representation
                 ↓
          MEMBER_SPECIFIC
```

They are **not** promoted to `LOCAL_OVERRIDE` or `EFFECTIVE_DRIFT`.

This is intentionally path-specific; A4.2.2 does not declare every HA setting member-specific. Cluster-wide settings such as HA enablement/config-sync remain directly comparable until independently proven otherwise.

### 2. Provenance guard

Device telemetry settings currently have insufficient operator-verifiable Template provenance in the validated environment. A mismatch therefore becomes:

```text
PROVENANCE_UNVERIFIED
```

rather than `LOCAL_OVERRIDE`/`EFFECTIVE_DRIFT`.

The expected value can still be retained as evidence, but the product must not make a stronger claim until the source/precedence semantics are verified.

### 3. VSYS identity resolver

PAN-OS can represent a virtual system using two identities:

```xml
<entry name="vsys5">
  <display-name>Friendly-VSYS</display-name>
</entry>
```

A4.2.2 builds a **per-firewall, in-memory** mapping from the direct effective configuration:

```text
internal ID     display name
vsys1       ↔   Friendly-A
vsys5       ↔   Friendly-B
```

The raw mapping is never copied to the shareable support bundle.

The resolver is used in two places.

#### Path identity normalization

Expected Template paths can use a friendly VSYS identity while the direct firewall tree uses `vsysN`:

```text
Expected:
/config/.../vsys/entry[@name='Friendly-B']/...

Firewall:
/config/.../vsys/entry[@name='vsys5']/...
```

When the display-name mapping is unique, A4.2.2 canonicalizes the expected path to the internal ID **before** scalar matching.

This can convert false `EXPECTED_ONLY` + `LOCAL_ONLY` pairs into a real comparison.

#### Value identity normalization

Typed VSYS-valued settings such as:

```text
/config/shared/user-id-hub/vsys
```

can contain the friendly name on the expected side and `vsysN` on the firewall side. When the direct VSYS map proves they refer to the same identity, the result is:

```text
ALIGNED
reason=vsys_internal_id_and_display_name_resolve_to_same_identity
```

not `LOCAL_OVERRIDE`.

If mapping is unavailable or ambiguous, the result remains:

```text
IDENTITY_TRANSLATION_REQUIRED
```

and is a non-finding.

## Fact-level classification vocabulary

A4.2.2 uses:

```text
ALIGNED
LOCAL_OVERRIDE
EFFECTIVE_DRIFT
PANORAMA_OUT_OF_SYNC
EXPECTED_ONLY
LOCAL_ONLY
MEMBER_SPECIFIC
PROVENANCE_UNVERIFIED
IDENTITY_TRANSLATION_REQUIRED
UNKNOWN
```

`MEMBER_SPECIFIC`, `PROVENANCE_UNVERIFIED`, and `IDENTITY_TRANSLATION_REQUIRED` are semantic exclusions/coverage states, not configuration errors.

## Override contract after hardening

`LOCAL_OVERRIDE` now requires all of the following:

```text
same normalized semantic setting
+ directly comparable semantic policy
+ trusted expected provenance
+ expected != effective
+ local-active == effective
```

Identity-equivalent values never satisfy an override claim.

## New privacy-safe support telemetry

`config_support_<run_id>.zip` can report counts only:

```text
semantic_policy_member_specific
semantic_policy_provenance_unverified
semantic_policy_identity_translation_required
semantic_policy_identity_path_normalized_settings
semantic_policy_identity_value_normalized_settings
semantic_policy_vsys_identity_map_entries
semantic_policy_engine_gate
a4_2_2_stage_pass
```

It does not include VSYS display names, internal IDs, setting paths, expected values, local values, or the in-memory identity map.

## Local artifacts

The existing local Alignment/Semantic Validation artifacts continue to be used:

```text
data/derived/panorama_alignment/<run_id>/setting-alignment.json
output/pan_setting_alignment_<run_id>.json

data/derived/panorama_semantic_validation/<run_id>/semantic-validation.json
output/pan_semantic_validation_<run_id>.json
output/pan_semantic_validation_samples_<run_id>.csv
```

The CSV can contain selected non-sensitive values and remains **LOCAL ONLY**.

## Run

From the management-reachable VM:

```powershell
py.exe -m pip install -r requirements.txt
py.exe .\main.py
```

No additional flag is required.

## What to validate in the next fleet run

The next support bundle should show:

1. `a4_2_2_stage_pass=true` and `semantic_policy_engine_gate=true`.
2. The previously observed HA peer-address false positive moves from `LOCAL_OVERRIDE` to `MEMBER_SPECIFIC`.
3. Telemetry mismatch samples move to `PROVENANCE_UNVERIFIED` rather than override/drift.
4. The known friendly-VSYS-name versus `vsysN` case becomes `ALIGNED` when the direct mapping is present.
5. `identity_path_normalized_settings` is non-zero on multi-VSYS devices where Panorama uses friendly VSYS names.
6. Some prior `EXPECTED_ONLY`/`LOCAL_ONLY` coverage gaps may disappear because the per-device VSYS path identity is now canonicalized.
7. Verified management permitted-IP description differences remain eligible for `LOCAL_OVERRIDE`.

A large reduction in coverage gaps is welcome but is **not** forced. Unresolved identity or provenance remains explicit rather than being guessed.

## Phase gate

A4.2.2 adds:

```text
semantic_policy_engine_gate
a4_2_2_stage_pass
```

These gates validate engine execution/schema usage, not the absence of findings. A firewall can contain valid local overrides or semantic exclusions and still pass the collection/analysis phase.

## Read-only / security posture

A4.2.2 remains read-only:

```text
Panorama API       read only
Direct PAN-OS API  read only
SSH fallback       not automatic
Remote config      unchanged
```

TLS verification is still a production-hardening item. Configure corporate CA trust before productionizing the collector plane.
