# Phase 0.6.0A2 — Palo Alto Configuration Structural Validation

## Purpose

0.6.0A proved that SecurityExpert can retrieve an active PAN-OS configuration through Panorama, publish an immutable XML snapshot, hash it, and detect FIRST/SAME/CHANGED deterministically.

0.6.0A2 answers the next question before fleet-wide scale-out:

> Is the XML only syntactically valid, or does it contain the configuration structures we expect from a useful PAN-OS configuration artifact?

This phase remains Palo Alto only. It does not change the Phase 0.5 Network Inventory collectors or UI.

## Retrieval method is unchanged

```text
SecurityExpert
    |
    | HTTPS POST + X-PAN-KEY
    v
Panorama
    |
    | target=<serial>
    v
Managed PAN-OS firewall
    |
    | type=config
    | action=show
    | xpath=/config
    v
Active configuration XML
```

0.6.0A2 does not introduce a second retrieval method. It validates the evidence already being collected.

## Two validation layers

### Hard artifact validation

A snapshot is not published as successful unless:

1. content is non-empty;
2. XML parses successfully;
3. the root element is `<config>`;
4. the artifact is written successfully;
5. SHA-256 calculated from the written file matches the in-memory SHA-256.

A hard validation failure remains a collection failure.

### Structural observation

After XML parsing, the collector records privacy-safe structure indicators:

```text
presence
├── devices
├── device_entry
├── deviceconfig
├── network
├── vsys
├── shared
├── panorama
└── mgt_config
```

It also records aggregate counts such as:

```text
device entries
VSYS entries
virtual-router entries
zone entries
interface definition nodes
local security-rule entries
Panorama pre-rule entries
Panorama post-rule entries
```

Only booleans, fixed warning codes, and aggregate counts are emitted. Object names, VSYS names, VR names, interface names, IP addresses, usernames, rule names, and XML text are not part of structural telemetry.

## PASS and WARN semantics

`structural_validation.status = pass` currently means:

- a `devices/entry` structure is present; and
- at least one of `deviceconfig`, `network`, or `vsys` is observed below the device entry.

`warn` means the XML remains a valid immutable snapshot, but expected structural signals were not observed.

A warning is intentionally **not** converted into a collection failure in this POC. PAN-OS configuration shape can vary by platform, release, licensing, virtual-system use, and local-versus-Panorama-managed configuration. We must observe the real fleet before promoting an optional section into a universal hard requirement.

Therefore:

```text
XML valid + structural WARN
    != backup complete
    != collection failed

It means: inspect the retrieval scope before scale-out.
```

## Privacy boundary

The local evidence remains sensitive:

```text
data/configs/**
output/pan_config_telemetry.json
```

Do not share these files.

The shareable file remains:

```text
output/config_support_<run_id>.zip
```

0.6.0A2 adds structural booleans/counts to that support bundle while continuing to exclude raw XML and real device identifiers.

The raw run ID is also removed from `summary.json`; only the HMAC-pseudonymized run token is shared.

## First test

Use two connected managed firewalls again:

```powershell
py.exe .\main.py --only pan-config --pan-config-limit 2
```

Then share only the new:

```text
output/config_support_<run_id>.zip
```

The first 0.6.0A2 run can report `FIRST` if you extracted this release into a new directory because `data/configs/` is intentionally runtime state and is not shipped inside release ZIPs. FIRST/SAME/CHANGED is meaningful within the same persistent evidence store.

## Decision gate after the two-device run

We will inspect:

- `structural_pass` / `structural_warn`;
- device-level presence booleans;
- VSYS, virtual-router, zone, interface, and policy counts;
- artifact byte sizes;
- duration and failure state.

If the structures are plausible for the selected devices, the next step is the full connected Palo Alto fleet scale test.

If structures are unexpectedly absent or counts are implausibly small, we stop and investigate the API retrieval scope before running all devices.

## Not a completeness certification

Structural PASS is a POC gate, not a declaration that every recoverable or policy-relevant PAN-OS configuration layer has been captured. Native recovery artifacts, Panorama/device bundles, candidate configuration, pushed policy semantics, and restore validation remain separate work.

## Next sequence

```text
0.6.0A2  PAN structural validation        <- current
0.6.0A3  connected PAN fleet scale test
0.6.0B   PAN native recovery artifact
0.6.0C   Configuration history/diff UI
0.6.1    Check Point standalone
0.6.1.x  Check Point ClusterXL
0.6.2    VSX
```


## A2.1 follow-up

The first real A2 run observed valid `devices/deviceconfig/network/vsys` containers but zero interface, virtual-router, zone and rule entries in two production samples. Phase 0.6.0A2.1 therefore adds a privacy-safe schema-path inspector and separates `schema_status` from `evidence_status`. See `PHASE0_6_0A2_1_PAN_SCHEMA_INSPECTOR.md`.
