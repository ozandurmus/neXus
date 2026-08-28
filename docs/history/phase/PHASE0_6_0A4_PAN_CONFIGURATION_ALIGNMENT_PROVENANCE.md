# Phase 0.6.0A4 — PAN Configuration Alignment & Provenance

## Goal

A4 promotes direct `show config effective-running` evidence to the primary PAN configuration evidence gate and adds a read-only Panorama intent/provenance layer. Panorama remains the discovery and intent plane; the firewall remains the primary actual-state evidence source.

This phase does **not** yet claim exact per-setting local override detection. It reports observed differences, Panorama sync state, Template Stack / Device Group assignment, and provenance-marker telemetry so the next fact-level compiler can classify overrides without guessing.

## Read-only collection model

```text
Panorama
  ├─ show devices all                         -> serial / management IP / sync state
  ├─ action=show xpath=/config (no target)    -> Panorama active management config
  └─ action=show xpath=/config target=SERIAL  -> target active comparison artifact

SecurityExpert collector
  └─ HTTPS direct to firewall management IP
       ├─ keygen
       ├─ show system info                    -> serial identity gate
       ├─ action=show xpath=/config            -> local/direct active evidence
       ├─ show config merged                  -> merged evidence
       ├─ show config effective-running       -> PRIMARY actual-state evidence
       └─ show config pushed-template         -> optional evidence probe
```

No commit, save, override, import, backup generation, or remote configuration mutation is performed.

## A4 status semantics

`effective-running` is the primary evidence requirement. A missing direct-active artifact no longer makes the whole configuration job fail if identity verification and effective-running succeed.

```text
primary_evidence_status
  success       identity + effective-running are available
  failed        primary actual-state evidence is unavailable

alignment_evidence_status
  complete      identity + effective + merged + direct-active
  partial       identity + effective + merged, but local/direct-active is absent
  insufficient  effective or merged evidence is unavailable
```

The fleet `stage_pass` gate uses primary evidence, not optional/secondary evidence.

## Panorama intent/provenance

A4 reads Panorama's active management configuration once and maps, when present in the XML model:

- Templates
- Template Stacks
- Template order within the stack
- firewall serial membership in a Template Stack
- Device Groups
- firewall/vsys membership in Device Groups
- parent Device Group metadata
- whether a Template Stack has stack-level configuration

The active Panorama configuration is stored only in the local immutable evidence store. The shareable support bundle contains counts and HMAC-pseudonymized assignment names only.

A4 deliberately does not compile the final expected configuration from Template Stack precedence and Device Group inheritance. Until that compiler exists, a difference is not automatically called an override or drift.

## Alignment classifications

Current evidence-level classifications:

- `CANONICALLY_ALIGNED`
- `PANORAMA_OUT_OF_SYNC`
- `DIFFERENCE_OBSERVED`
- `UNKNOWN`
- `INSUFFICIENT_EVIDENCE`

`LOCAL_OVERRIDE` is **not** asserted in A4. A4 may emit a `local_override_candidate`, but the status remains `NOT_PROVEN` until exact Panorama expected intent/value provenance is compiled.

## Provenance markers

A4 records value-free counts of XML `src` attributes. Palo Alto documents `src="tpl"` in its configuration override API example, so `src=tpl` is normalized to a `template` provenance category. Unknown source values are counted as `other`; raw attribute values are not exported to support bundles.

## Failure diagnostics

A4 separates remote query failures from local evidence-store failures. This closes an ambiguity found in A3, where a `PermissionError` during the combined direct-active operation could not tell us whether the firewall API query or the local snapshot publish failed.

Local-only diagnostics are written to:

```text
output/pan_config_failures_<run_id>.json
```

They include the real local device identity so the operator can check that firewall. The shareable support bundle pseudonymizes those identities.

Methods/stages include:

- `PANORAMA_DISCOVERY_MANAGEMENT_IP`
- `PANORAMA_HTTPS_API_ACTIVE_MANAGEMENT_CONFIG`
- `PANORAMA_HTTPS_API_TARGET_ACTIVE_CONFIG`
- `DIRECT_HTTPS_API_KEYGEN`
- `DIRECT_HTTPS_API_SYSTEM_INFO`
- `DIRECT_HTTPS_API_ACTIVE_CONFIG`
- `DIRECT_HTTPS_API_EFFECTIVE_RUNNING`
- `DIRECT_HTTPS_API_MERGED_CONFIG`
- `DIRECT_HTTPS_API_PUSHED_TEMPLATE`
- `LOCAL_IMMUTABLE_EVIDENCE_STORE`

SSH is explicitly reported as not attempted in A4. It remains a future fallback/diagnostic method rather than being silently mixed with API collection.

Example terminal failure line:

```text
PAN A4 FAIL device=<actual-local-device-name> method=DIRECT_HTTPS_API_ACTIVE_CONFIG transport=DIRECT_HTTPS_XML_API stage=direct_active_api_query error=PanoramaConfigError hint=panos_api_rejected_unsupported_or_role_permission
```

or, if the API read succeeded but local publish failed:

```text
PAN A4 FAIL device=<actual-local-device-name> method=DIRECT_HTTPS_API_ACTIVE_CONFIG transport=LOCAL_IMMUTABLE_EVIDENCE_STORE stage=direct_active_local_store error=PermissionError hint=local_filesystem_permission_or_lock
```

## A3 edge case carried into A4

The A3 full-fleet support bundle showed one pseudonymized device where:

```text
direct keygen       success
direct identity     success
direct active       failed (PermissionError)
direct effective    success
direct merged       success
```

A3 wrapped the API query and local snapshot publish in one try/except, so the exact failure domain was ambiguous. A4 intentionally splits those stages. The next full-fleet run will print the real local device name and tell the operator whether to investigate PAN-OS API permissions/behavior or the local filesystem/evidence store.

## Support-bundle privacy

The shareable `config_support_<run_id>.zip` contains no raw configuration and no credentials. Device identity, serial, management IP, Template Stack names, Template names, Device Group names, and vsys identifiers are HMAC-pseudonymized. Raw error messages are not copied into the bundle; only method/stage/error type and a bounded diagnostic hint are shared.

## References used for the method

Palo Alto Networks documents `action=show` as retrieval of active configuration, templates/template stacks as the mechanism for standardized device/network settings, Device Groups as the hierarchy for policies/objects, and the configuration `override` action for settings pushed from a Panorama template. The implementation keeps all A4 calls read-only.
