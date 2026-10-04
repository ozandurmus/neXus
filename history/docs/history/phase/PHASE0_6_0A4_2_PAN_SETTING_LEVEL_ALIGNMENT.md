# Phase 0.6.0A4.2 — PAN Setting-Level Alignment Engine

## Goal

A4.2 turns the A4.1 Expected Configuration Compiler into a real, read-only alignment engine.

The comparison authority is intentionally split:

```text
Panorama active management config
        │
        └── Template / Template Stack
                    │
                    ▼
        A4.1 Expected Compiler
                    │
                    │ normalized scalar setting key + expected SHA-256
                    ▼
              EXPECTED STATE
                    │
                    ↕ A4.2
                    │
              ACTUAL STATE
                    ▲
                    │
Direct firewall show config effective-running
```

Direct `effective-running` remains the primary actual-state evidence. Direct local-active and merged configuration are corroborating evidence used to explain a mismatch.

A4.2 is read-only. It does not commit, push, override, save, export, SCP, SSH, or otherwise mutate a firewall or Panorama.

## Why A4.2 is bounded

The goal is not to produce the maximum number of differences. The goal is to produce differences that have defensible evidence.

A4.2 therefore aligns only A4.1 settings marked `alignment_ready=true`:

- scalar leaf settings
- named-entry identity preserved in the normalized path
- Template Stack precedence already compiled
- unresolved Template variables excluded from exact comparison
- repeated un-keyed collection leaves such as `<member>` remain excluded
- Device Group rule/object values are not yet setting-aligned

This prevents list ordering, object inheritance, variable substitution, or policy expansion from being mistaken for drift.

## Normalized setting key

A Template and a firewall can use a different literal identity for the root `/config/devices/entry`. That identity is not part of the semantic setting.

A4.2 normalizes only that root identity:

```text
/config/devices/entry[@name='localhost.localdomain']/deviceconfig/...
/config/devices/entry[@name='SERIAL-OR-OTHER']/deviceconfig/...
```

becomes:

```text
/config/devices/entry[@name='__DEVICE__']/deviceconfig/...
```

VSYS names, interface names, Virtual Router names, profile names and other named entries remain in the key because they are semantically meaningful.

## Classification contract

### ALIGNED

```text
expected hash == effective-running hash
```

This is high-confidence alignment for the same normalized scalar key.

### LOCAL_OVERRIDE

A4.2 requires all of the following evidence for a strong local-override classification:

```text
expected != effective
AND
local-active value == effective value
```

Merged equality increases confidence but is not the only evidence. A4.2 does not infer an override from whole-file `active != merged`.

### EFFECTIVE_DRIFT

A4.2 uses a deliberately strict drift contract:

```text
expected != effective
AND
local-active does not explain effective
AND
merged == effective
AND
Panorama Template sync status is known
```

This is a setting-level observation that the vendor-merged/effective value differs from the compiled Panorama expectation without a local-active explanation.

### PANORAMA_OUT_OF_SYNC

For Template-Stack scalar settings, only Panorama **Template** sync state is used. Shared Policy sync is a different configuration plane and does not classify a Template setting.

If a setting differs while Panorama reports Template `out_of_sync`, A4.2 reports `PANORAMA_OUT_OF_SYNC` rather than drift.

### EXPECTED_ONLY

The compiler expects the setting but it is not observed in effective-running.

This is **not automatically drift**. PAN-OS can suppress mode-specific or non-applicable settings, and A4.2 does not invent applicability semantics.

### LOCAL_ONLY

A scalar exists in direct local-active but has no compiled Template-Stack scalar counterpart.

This is informational. It can represent legitimate firewall-specific local configuration and is **not automatically drift**.

### UNKNOWN

Evidence is insufficient for a stronger classification. Unresolved Template variables are also kept UNKNOWN.

## Semantic categories

A4.2 groups setting-level counts into operational categories without changing the underlying normalized key:

- DNS
- NTP
- System
- HA
- Interfaces
- Routing
- VPN
- Logging
- Profiles
- VSYS
- Other

This prepares the future Alignment UI so it can present semantic groups instead of thousands of XML paths.

## Performance boundary

`effective-running` can contain a very large Panorama policy expansion. A4.2 Template-Stack scalar keys live under `/config/devices/...`.

When all requested expected keys are in that configuration plane, the actual-state extractor scans only the `/config/devices` subtree and skips unrelated Panorama policy expansion. This keeps the setting-alignment cost focused on the device/network configuration plane.

Policy value alignment will be implemented separately using the A4.1 Device Group lineage model.

## Local derived evidence

Detailed setting-level results are written to:

```text
data/derived/panorama_alignment/<run_id>/setting-alignment.json
```

This local-only manifest can contain:

- real device identity
- normalized setting paths
- expected/effective/merged/local SHA-256 values
- expected Template / Template Stack source name and priority
- classification and reason
- provenance category

It does **not** copy raw configuration values.

A compact operator report is written to:

```text
output/pan_setting_alignment_<run_id>.json
```

It contains real device identity and classification counts, but not full setting paths or value hashes.

Both files are local security evidence and should not be shared externally by default.

## Shareable support bundle

`output/config_support_<run_id>.zip` contains only aggregate/per-device counts and contracts.

It does not include:

- raw firewall or Panorama configuration
- setting paths
- expected or actual value hashes
- real Template / Stack / Device Group names
- credentials or API keys

Support telemetry includes counts for:

```text
ALIGNED
LOCAL_OVERRIDE
EFFECTIVE_DRIFT
PANORAMA_OUT_OF_SYNC
EXPECTED_ONLY
LOCAL_ONLY
UNKNOWN
```

plus category counts and engine coverage.

## Pushed-template probe

The `show config pushed-template` probe is not required for A4.2 because Panorama Template/Stack intent is compiled directly and effective/merged evidence is collected directly from the firewall.

Normal `main.py` runs therefore leave this known-noisy probe disabled. It can be explicitly enabled for diagnostics:

```powershell
py.exe .\main.py --only pan-config --pan-config-stage all --pan-config-workers 3 --pan-probe-pushed-template
```

## Gate semantics

Existing gates remain intact:

```text
stage_pass
    direct identity + effective-running primary evidence

a4_1_stage_pass
    stage_pass
    + Expected Compiler gate
    + Device Group lineage gate
```

A4.2 adds:

```text
setting_alignment_engine_gate
```

which requires every selected device to complete the setting-level engine and the local derived alignment manifest to publish successfully.

The final phase gate is:

```text
a4_2_stage_pass = a4_1_stage_pass
                  AND setting_alignment_engine_gate
```

Finding counts do not make the gate fail. `LOCAL_OVERRIDE` or `EFFECTIVE_DRIFT` are product findings, not collector failures.

## Run

Normal unified run:

```powershell
py.exe .\main.py
```

PAN-only validation from the management-reachable VM:

```powershell
py.exe .\main.py --only pan-config --pan-config-stage all --pan-config-workers 3
```

## What to send for validation

For routine validation send only:

```text
output/config_support_<run_id>.zip
```

Keep these local unless a specific diagnostic requires them:

```text
output/pan_setting_alignment_<run_id>.json
data/derived/panorama_alignment/<run_id>/setting-alignment.json
output/pan_expected_compiler_<run_id>.json
data/derived/panorama_expected/<run_id>/expected-compiler.json
output/pan_config_failures_<run_id>.json
```

## Next decision after the real fleet run

The first real A4.2 fleet run should answer:

1. What percentage of the ~54k expected scalar settings map to the same effective key?
2. How many are ALIGNED?
3. How many have strong LOCAL_OVERRIDE evidence?
4. How many satisfy the stricter EFFECTIVE_DRIFT contract?
5. Which semantic categories dominate EXPECTED_ONLY / UNKNOWN?
6. Does path normalization need vendor/schema-specific adapters before an Alignment UI is built?

Do not build user-facing severity/compliance logic until these real-fleet distributions are validated.
