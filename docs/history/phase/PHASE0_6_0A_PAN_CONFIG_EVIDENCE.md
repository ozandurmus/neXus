# Phase 0.6.0A — Palo Alto Configuration Evidence POC

## Goal

Phase 0.6 starts with Palo Alto only. The purpose of 0.6.0A is to prove that SecurityExpert can collect the active running configuration of managed PAN-OS firewalls through Panorama, preserve it as immutable local evidence, validate the artifact, hash it, and produce privacy-safe diagnostics.

This phase deliberately does **not** change the Phase 0.5 Network Inventory pipeline or UI.

## Why this method

The collector uses the existing Panorama management path, but the new configuration collector has its own hardened request layer:

```text
SecurityExpert collector
        |
        | HTTPS POST
        v
     Panorama
        |
        | XML API target=<serial>
        v
managed PAN-OS firewall
        |
        +-- type=config
        +-- action=show
        +-- xpath=/config
        v
active running configuration
```

The API key is carried in the `X-PAN-KEY` HTTP header. Username/password are used only in the POST body for key generation. The new collector does not put the API key or credentials in the URL.

Disconnected managed firewalls are not queried for a "current active" configuration. They are recorded as `skipped_disconnected` rather than being treated as a valid fresh backup.

## Evidence contract

A successful collection creates:

```text
data/configs/
└── panorama/
    └── <serial>/
        └── <timestamp>_<id>/
            ├── running-config.xml
            ├── metadata.json
            └── sha256.txt
```

`running-config.xml` is the serialized `<config>` payload returned by the active configuration API request.

`metadata.json` includes:

- source and entity identity
- artifact type
- collection timestamp
- collection method
- status
- SHA-256
- byte size
- collector version
- XML validation result
- `first`, `same`, or `changed` comparison against the previous successful snapshot
- model/software metadata when Panorama provides it

Snapshots are never overwritten. A new immutable directory is created for every successful collection.

## Validation rules

Before publish, the artifact must:

1. be non-empty;
2. parse as XML;
3. have a `<config>` root;
4. be written successfully;
5. have the same SHA-256 after the filesystem write.

A failed validation is not published as a successful configuration snapshot.

## Privacy boundary

Raw configuration is sensitive and never enters the shareable support bundle.

Local-only files:

- `data/configs/**`
- `output/pan_config_telemetry.json`

Shareable file:

```text
output/config_support_<run_id>.zip
```

The shareable bundle contains HMAC-pseudonymized device/serial/IP identifiers, status, duration, artifact size, hash, and change state. It contains no configuration XML and no HMAC key.

## TLS

For compatibility with the current environment, the POC defaults to TLS verification disabled. This is explicitly recorded in telemetry and warned at runtime.

Production target:

```text
SECURITYEXPERT_PAN_CA_BUNDLE=<path-to-corporate-ca.pem>
```

or:

```text
SECURITYEXPERT_PAN_TLS_VERIFY=true
```

The preferred production state is certificate verification with the corporate CA bundle.

## Test sequence

Start with two connected managed firewalls:

```powershell
py.exe .\main.py --only pan-config --pan-config-limit 2
```

Do **not** share anything under `data/configs/`.

Share only:

```text
output/config_support_<run_id>.zip
```

After the first small POC is verified, run all managed firewalls:

```powershell
py.exe .\main.py --only pan-config
```

A second successful run without configuration changes should report `same`. A real configuration change should produce a new immutable snapshot with `changed`.

## Out of scope for 0.6.0A

- Panorama native configuration/device-state bundle export
- restore testing
- configuration UI
- semantic XML diff
- compliance analysis
- Check Point configuration backup
- VSX configuration backup
- scheduler/database/container changes

## Next Palo Alto step

0.6.0B should evaluate the vendor-native recovery artifact separately from the readable configuration evidence:

```text
Readable evidence: active running-config XML
Recovery artifact: Panorama / PAN-OS native configuration or device-state bundle
```

The two artifacts must not be treated as interchangeable.

## 0.6.0A2 follow-up

Repeatability was proven on the first two managed firewalls: a second collection with no configuration change produced identical byte sizes/SHA-256 values and `SAME` state. The next gate is privacy-safe structural validation before fleet-wide scale-out. See `PHASE0_6_0A2_PAN_STRUCTURAL_VALIDATION.md`.
