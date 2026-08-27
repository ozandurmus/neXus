# Phase 0.6.0A4.3 — SecurityExpert Configuration UI Foundation

## Goal

Expose the validated PAN configuration-intelligence plane as a first-class UI module without changing the frozen Phase 0.5 inventory semantics or weakening the A4.2.2 evidence model.

A4.3 is a presentation/orchestration increment. It does **not** change PAN collection methods, expected-state compilation, setting classification, support-bundle privacy, or write-plane behavior.

## Application shell

The standalone HTML now has three top-level modules:

```text
SecurityExpert
├── Overview
├── Network Inventory
└── Configuration
```

The legacy F-Buddy user-facing brand is removed. Internal compatibility names/environment variables are not mass-renamed in this phase.

### Overview

The new overview surfaces the latest observation cycle with separate operational semantics:

- inventory LIVE vs old/unavailable logical views
- PAN primary effective-configuration evidence coverage
- local override count and affected devices
- unexplained effective drift
- semantic/evidence coverage states
- native backup roadmap placeholder

Color semantics are deliberate:

```text
GREEN  aligned/current/healthy evidence
AMBER  local override / operator attention
RED    effective drift, out-of-sync, collection failure
CYAN   expected member-specific difference / informational
GRAY   provenance, identity, expected-only/local-only coverage gaps
```

A local override is not automatically a compliance failure.

## Configuration module

```text
Configuration
├── Overview
├── Alignment
├── Evidence
├── History
└── Backup
```

A device sidebar provides a fleet view plus per-firewall navigation.

### Configuration / Overview

Fleet and device views expose:

- primary effective-running availability
- Panorama Template sync state
- semantic alignment counts
- local overrides
- effective drift
- expected member differences
- observed expected-setting coverage
- Template Stack / Device Group assignment context

### Configuration / Alignment

A4.3 renders category-level counts for DNS, NTP, System, HA, Interfaces, Routing, VPN, Logging, Profiles, VSYS and Other.

Operator-facing detail rows are limited to bounded semantic states:

```text
LOCAL_OVERRIDE
EFFECTIVE_DRIFT
PANORAMA_OUT_OF_SYNC
MEMBER_SPECIFIC
PROVENANCE_UNVERIFIED
IDENTITY_TRANSLATION_REQUIRED
UNKNOWN
```

Large `EXPECTED_ONLY` / `LOCAL_ONLY` sets remain aggregate coverage telemetry instead of flooding the console.

The detailed local A4.2.2 manifest can contribute setting paths, expected-source kind/name, confidence and reason to the **local HTML only**. Raw configuration values and value hashes are not copied into the browser payload.

### Configuration / Evidence

Per-device evidence cards distinguish:

```text
Effective-running       PRIMARY actual/compliance evidence
Merged                  provenance/alignment support
Local active            local override support
Panorama control view   control-plane comparison support
```

Method, transport, schema result, size and current change-state are shown. The UI explicitly states that the plane is read-only.

### Configuration / History

A4.3 exposes the existing FIRST/SAME/CHANGED snapshot signals and current active/merged/effective change state. A chronological selection/diff experience remains a later Configuration UI increment.

### Configuration / Backup

The Backup tab is intentionally a disabled capability placeholder for **0.6.0B PAN Native Device-State Backup**. Configuration evidence snapshots are not mislabeled as recovery backups.

## Local UI payload

`utils/config_ui.py` derives a compact local-only browser model from the current configuration result and the local alignment manifest.

Allowed in local HTML:

- real device identity
- management IP/model/version
- Template Stack / Device Group names
- bounded finding setting paths
- expected source name/type
- classification/reason/confidence
- aggregate counts and evidence metadata

Explicitly excluded:

- raw configuration values
- expected/effective/local value hashes
- credentials/API keys
- raw configuration XML

The shareable support bundles are unchanged and remain pseudonymized.

## Full-run coupling

The normal workflow remains:

```powershell
py.exe .\main.py
```

During a full run, the current `config_result` is passed to the same HTML export that publishes the current unified inventory. This avoids silently presenting stale configuration telemetry from another run.

`--skip-config` still produces the inventory UI; the Configuration module then clearly reports that configuration evidence was not attached to that HTML export.

## Navigation

The standalone file supports local module anchors:

```text
#overview
#inventory
#configuration
```

The last selected module is also remembered locally. Existing `fbuddy-theme` local-storage data is accepted as a backward-compatible theme migration key while new writes use `securityexpert-theme` as well.

## Security / product boundaries

- A4.3 remains read-only.
- No push, commit, save, failover or configuration operation is added.
- No support-bundle privacy contract changes.
- Network Inventory 0.5 behavior remains intact.
- Configuration is a separate module; it does not absorb Inventory.
- TLS verification for PAN configuration collection is still a production hardening item and is surfaced in Evidence rather than hidden.

## Validation

A4.3 adds UI/payload regression tests for:

- multi-module shell and preserved inventory DOM contract
- local-only Configuration payload generation
- exclusion of raw values and value hashes
- semantic classification presentation
- current full-run configuration result passed into HTML export
- Backup placeholder kept separate from evidence/history
