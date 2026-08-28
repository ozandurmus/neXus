# Phase 0.6.0A4.3.1 — Vendor-Neutral Configuration Information Architecture

## Decision

A4.3 proved that the configuration evidence can be rendered in the SecurityExpert shell, but the first UI over-emphasized Palo Alto Panorama alignment. A4.3.1 separates the product concepts before Check Point and VSX are added.

The UI contract is now:

```text
Configuration = What is actually configured on the device now?
Alignment     = Does current state match centrally expected state, and why?
Policy & Objects = Security policy / NAT / objects / management-plane analysis
History       = What changed over time?
Evidence      = Where/how was the state collected?
Backup        = Can the device be recovered from a native artifact?
Operations    = Future controlled write plane; not part of this phase
```

This is intentionally vendor-neutral. Palo Alto, Check Point Gaia and VSX can use different collectors/adapters while producing the same product concepts.

## Product review incorporated

The phase incorporates the operator, peer NetSec, manager, business-manager and director perspectives discussed during A4.3 review:

- Current device configuration is the primary purpose of the Configuration tab.
- Policy/NAT/objects are required later, but remain a separate plane.
- Operators must see real non-secret values such as DNS, NTP, management, HA and system settings.
- Device identity/orientation must be immediately visible: vendor, model, software, management IP, serial number, HA/role, VSYS/VS count and management-policy scope when available.
- Alignment must not dominate Configuration. PAN/LOCAL/OVERRIDE is a compact origin hint in current config; detailed expected-vs-current evidence stays in Alignment.
- Inventory remains runtime/operational state. Configured-vs-runtime comparison is valuable but deliberately backlog rather than duplicating the entire Inventory UI here.
- A vendor-native view is part of the long-term Configuration contract (`effective-running` XML for PAN, `show configuration` for Check Point, host/VS context for VSX), but is deferred until secret-aware authorization/redaction is hardened.
- Executive Overview is device-impact first. Large internal setting counters are moved away from the executive posture view.

## Information architecture

```text
SecurityExpert
├── Overview
├── Network Inventory
└── Configuration
    ├── Overview
    ├── Configuration       # current actual device configuration
    ├── Alignment           # expected ↔ current
    ├── Policy & Objects    # separate plane; placeholder in this phase
    ├── History
    ├── Evidence
    └── Backup
```

Selecting a device automatically opens the **Configuration** tab. Selecting the fleet opens **Overview**.

## Device orientation header

A device view exposes the operational identifiers needed in the first seconds of troubleshooting:

```text
Vendor
Model
Software
Management IP
Serial Number
HA / Role
VSYS / VS count
Policy / management scope
Collected-at / freshness context
```

For Palo Alto, the current policy/management scope is represented by Device Group assignment. A future Check Point adapter can map the same product concept to policy package/install-target context without changing the UI contract.

## Current configuration projection

A4.3.1 adds a bounded local projection from the already-collected direct PAN `effective-running` evidence. It does **not** issue additional device commands.

Initial structured domains:

```text
System
DNS
NTP
Management
Telemetry
High Availability
Network Configuration summary
```

The network section is intentionally a configured-state summary (VSYS, virtual routers, zones, interface counts) rather than a duplicate of the runtime Inventory interface/routing tables. Detailed configured-vs-runtime reconciliation remains backlog.

### Origin badges

The current configuration table uses a small origin vocabulary:

```text
PAN       centrally supplied / aligned Panorama intent
LOCAL     local device configuration
OVERRIDE  proven local override
MEMBER    member-specific value; not generic drift
EFFECTIVE current effective value where provenance is not stronger
UNKNOWN   provenance not established
```

These badges are orientation hints. Alignment is the authoritative place for expected/current comparison, evidence confidence and drift/override explanation.

## Security boundary

A4.3.1 changes the sensitivity of `output/index.html`.

The local operator HTML can now contain selected real non-secret current configuration values. Therefore it is a **sensitive local operational artifact** and must not be treated like a shareable support bundle.

The browser payload still excludes:

- raw configuration XML blobs,
- value hashes from alignment manifests,
- passwords,
- secrets,
- private/pre-shared/auth keys,
- API keys/tokens,
- SNMP/community-style secret-bearing values,
- credentials.

Secret-like paths are omitted from the structured current projection. Shareable `support_bundle_*.zip` and `config_support_*.zip` behavior is unchanged and does not gain these values.

## Vendor-neutral adapter direction

The current implementation has the PAN adapter only, because PAN configuration evidence is the validated 0.6 source. The schema is deliberately shaped for later adapters:

```text
Palo Alto
  current actual -> direct effective-running
  central origin -> PAN / Template Stack
  policy scope   -> Device Group

Check Point
  current actual -> Gaia show configuration / structured Gaia evidence
  central origin -> Management where applicable
  policy scope   -> policy package / install target

VSX
  current actual -> VSX Host + per-VS configuration contexts
  navigation     -> Host -> VS children
  central origin -> Management where applicable
```

No Check Point/VSX configuration is fabricated in this phase.

## Policy & Objects separation

The placeholder is intentional. The future plane will contain items such as:

```text
Security rules
NAT
Objects / groups / services
Policy packages / Device Groups
Policy analysis
Bulk operations (later)
```

This avoids turning device configuration into a Palo Alto-specific Panorama comparison page and keeps future bulk/change workflows separable from read-only configuration observation.

## Backlog decisions retained

1. Configured interface/routing details versus Inventory runtime values.
2. Vendor-native configuration view with secret-aware authorization/redaction.
3. Full time selector and value-level History/Diff.
4. Check Point Gaia current-configuration adapter.
5. VSX Host -> VS current-configuration hierarchy.
6. Policy & Objects normalization and analysis.
7. Write-plane actions, bulk operations and firewall failover remain later, approval-controlled capabilities.

## Run

No collector invocation changes were introduced:

```powershell
py.exe -m pip install -r requirements.txt
py.exe .\main.py
```

Then open:

```text
output\index.html
```

Review Configuration -> select a PAN device -> **Configuration**. Real non-secret current values should be visible with origin badges; detailed override/drift logic remains under **Alignment**.
