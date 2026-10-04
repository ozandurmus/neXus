# Phase 0.6.0A2.2 — PAN Direct Firewall Effective-Configuration Compare

## Decision at this checkpoint

A2.1 confirmed an important semantic fact in the first two real samples: the
Panorama-mediated `action=show xpath=/config target=<serial>` response contained
real local configuration sections, but the observed network/VSYS containers had
no `entry` children for virtual routers, data-plane interfaces or zones in the
sampled devices. This is consistent with a Panorama-managed firewall where much
of the effective device/network configuration is pushed from Panorama.

Therefore A2.2 does **not** scale the Panorama-mediated config query to the full
fleet. It changes the POC architecture to:

```text
Panorama
  -> discover serial + management IP + connection state

SecurityExpert
  -> connect directly to firewall management IP over HTTPS
  -> generate a short-lived/in-memory API key from runtime credentials
  -> show system info
  -> verify returned serial == Panorama-discovered serial
  -> read direct active configuration
  -> read direct effective-running configuration
  -> read direct merged configuration
```

Panorama remains a discovery/control plane. Direct firewall evidence is the
candidate source for effective configuration and, later, live operational data.
It is **not promoted to primary yet**; real-device comparison is the promotion
gate.

## Why direct API before SSH for PAN-OS

PAN-OS exposes both configuration and operational commands through its native
XML API. Direct HTTPS gives structured XML, avoids terminal/prompt parsing and
is already the protocol used by PAN-OS for automation. A2.2 therefore tests the
native direct API first.

SSH remains useful as:

- a fallback when XML API access is unavailable;
- a diagnostic path to compare CLI/API semantics;
- a vendor-specific transfer/control option where an API export is insufficient.

For native recovery artifacts, PAN-OS also supports XML API file export with
`type=export&category=device-state`. That is a strong candidate for the next
backup POC because it can retrieve a vendor-native device-state bundle directly
from the firewall without inventing an SSH scraping workflow. It is deliberately
not invoked by A2.2.

## A2.2 read-only calls

Control path through Panorama:

```text
type=config
action=show
xpath=/config
target=<serial>
```

Direct firewall path:

```text
1. type=keygen
2. type=op    cmd=<show><system><info/></system></show>
3. type=config action=show xpath=/config
4. type=op    cmd=<show><config><effective-running/></config></show>
5. type=op    cmd=<show><config><merged/></config></show>
```

No `set`, `edit`, `delete`, `commit`, `save`, import, restore or backup creation
operation is issued.

## Identity gate

Using a management IP discovered from Panorama is not sufficient by itself.
Addresses can be stale or accidentally reused. Before configuration collection,
A2.2 queries `show system info` directly and requires:

```text
direct serial == Panorama discovered serial
```

If the serial does not match, no direct configuration query is performed for
that target. The support bundle exposes only `identity_verified=true/false`, not
raw serials or IPs.

## Artifacts written locally

The POC can create four independent evidence streams per firewall:

```text
Panorama control
  panos_active_config_via_panorama

Direct firewall
  panos_direct_active_config
  panos_direct_effective_running_config
  panos_direct_merged_config
```

Each artifact has its own SHA-256, immutable snapshot and change history.
A2.2 also fixes a foundational history issue: previous-hash comparison is now
scoped by `artifact_type`, so an effective-running snapshot cannot accidentally
be compared with the previous active or merged snapshot.

## Comparison telemetry

The shareable support bundle contains only pseudonymized identity and safe
structural metrics. It compares:

```text
Panorama active <-> Direct active
Direct active   <-> Direct effective-running
Direct active   <-> Direct merged
Direct merged   <-> Direct effective-running
```

For each comparison it records only:

- exact SHA-256 equality;
- size delta;
- deltas for VSYS, virtual-router, zone, interface and security-rule counts;
- whether the right-hand artifact contains richer structural signals.

No configuration values, object names, IP addresses, zones, rules or interface
names are included in support output.

## What will decide the long-term PAN architecture

The preferred future shape is:

```text
DISCOVERY / OWNERSHIP
Panorama
  serial, management IP, HA/management relationship, template/device-group ownership

EFFECTIVE CONFIG EVIDENCE
Direct firewall API
  effective-running (+ additional pushed-policy evidence if real tests prove required)

LIVE RUNTIME EVIDENCE
Direct firewall API where reachable
  operational state queried from the device that is actually forwarding traffic

RECOVERY BACKUP
Direct PAN-OS native export/device-state API first
  SSH/SCP fallback only where vendor/API limitations require it
```

This keeps the management plane authoritative for ownership while the firewall
itself is authoritative for its effective and live state.

## Run

Use only two connected devices:

```powershell
py.exe -m pip install -r requirements.txt
py.exe .\main.py --only pan-config --pan-config-limit 2
```

Share only:

```text
output\config_support_<run_id>.zip
```

Do not share `data/configs/` or `output/pan_config_telemetry.json`.

## Expected decision outcomes

### Direct API succeeds and effective-running is materially richer

Promote direct firewall API as the **candidate** effective configuration source,
then add any missing pushed-policy artifact before fleet scale-out.

### Direct API succeeds but effective-running is not richer

Inspect `merged` and, if needed, add `pushed-template` / `pushed-shared-policy`
as separate evidence classes. Do not call a partial tree complete.

### Direct API cannot authenticate or is not network-reachable

Do not fall back silently. Record the reason, preserve Panorama as control, and
then decide whether production collector network placement or SSH fallback is
appropriate.

### Identity mismatch

Stop direct collection for that target. Treat it as a discovery/address
integrity problem, not as a collector success.

## References used for design

- Palo Alto Networks PAN-OS XML API documentation: direct firewall and Panorama
  both expose XML API; `action=show` retrieves active configuration.
- Palo Alto Networks documents `target=<serial>` for queries redirected through
  Panorama to a managed firewall.
- PAN-OS CLI hierarchy includes `show config effective-running`, `show config
  merged`, `show config pushed-template` and `show config pushed-shared-policy`.
- Palo Alto Networks Panorama administration documentation states that
  `show config merged` is used on a firewall to view local configuration plus
  Panorama-pushed template configuration.
- PAN-OS XML API export supports `category=configuration` and
  `category=device-state` for file export from a firewall.
