# Phase 0.6.0A2.1 — PAN Configuration Schema Inspector

## Why this checkpoint exists

The first two PAN configuration-evidence runs proved transport, targeting,
repeatability and byte-level integrity. Phase 0.6.0A2 then proved that the
returned payload is a real `<config>` tree, but the first two production
samples contained VSYS containers while the validator observed zero virtual
routers, zones, interfaces and security rules.

That result must not be interpreted as either "the API method is wrong" or
"the configuration is complete" without further evidence.

A2.1 therefore has two goals:

1. discover the real XML schema paths without exporting configuration values;
2. stop treating schema validity as evidence completeness.

## What the current method actually does

The current POC sends a read-only XML API request to Panorama:

```text
POST https://<panorama>/api/
X-PAN-KEY: <api-key>

type=config
action=show
xpath=/config
target=<managed-firewall-serial>
```

Panorama redirects the request to the selected managed firewall by serial.
The firewall/PAN-OS API returns the active configuration node selected by the
XPath. The collector then serializes the returned `<config>` element and
creates an immutable copy on the SecurityExpert host under `data/configs/`.

### Important semantic distinction

This method does **not**:

- ask the firewall to generate a backup file;
- create a new named configuration snapshot on the firewall;
- change the candidate or running configuration;
- commit anything;
- create a recovery bundle on Panorama.

It is an API **read** followed by a **local artifact write**.

The support telemetry now states this explicitly:

```text
remote_artifact_created       false
remote_configuration_changed  false
local_artifact_created        true
retrieval_scope               active_config_api_tree_under_validation
```

## Is `action=show xpath=/config` the right method?

It is a vendor-supported method for retrieving active configuration through
the PAN-OS XML API, and `target=<serial>` is the supported Panorama mechanism
for redirecting a query to a managed firewall.

However, "active config returned by this API query" and "complete effective
configuration/recovery backup" must not be assumed to be the same artifact.
Panorama-managed firewalls can inherit device/network settings from templates
and policy/objects from Panorama. PAN-OS also exposes operational commands such
as `show config effective-running`, `show config pushed-template`, and
`show config merged` specifically to distinguish local and pushed/effective
configuration views.

Therefore A2.1 intentionally labels the current artifact:

```text
artifact_type = panos_active_config_api_tree
```

rather than claiming it is already a complete effective/recovery backup.

The next method decision will be based on the schema evidence returned by this
build. If the current `/config` tree contains only local configuration, the
next POC should compare it side-by-side with an effective/merged read-only
view rather than silently changing the primary method.

## Schema inspector privacy contract

A2.1 parses the raw XML locally and emits only XML element paths and occurrence
counts. It never emits:

- element text;
- attribute names or values;
- device names;
- serial numbers;
- management IP addresses;
- interface names;
- VSYS names;
- virtual-router names;
- zone names;
- rule names;
- usernames or object values.

Example safe telemetry:

```json
{
  "path": "/config/devices/entry/network/interface/ethernet/entry",
  "occurrences": 8
}
```

The `entry` object's `name=` attribute is deliberately not included.
Unexpected/non-standard element names are replaced with a stable hash token.

## New evidence semantics

A2 used one status for too many meanings. A2.1 separates them:

```text
schema_status   = pass | warn
evidence_status = unknown
```

`schema_status=pass` means the returned XML is structurally plausible as a
PAN-OS configuration tree.

`evidence_status=unknown` is intentional in A2.1. It means we have not yet
proved that this artifact represents the complete configuration scope required
for SecurityExpert backup/configuration intelligence.

The old `status` field remains as a compatibility alias for `schema_status`
during the POC.

## Run

Use only the first two connected firewalls again:

```powershell
py.exe -m pip install -r requirements.txt
py.exe .\main.py --only pan-config --pan-config-limit 2
```

Share only:

```text
output/config_support_<run_id>.zip
```

Do not share `data/configs/`, `running-config.xml`, or local telemetry.

## What the next review will answer

From the support bundle we will inspect, without seeing real configuration
values:

- which descendants actually exist below `/config/devices/entry/network`;
- which descendants exist below each VSYS;
- whether interface/virtual-router/zone/rule paths are absent or merely shaped
  differently than the A2 validator expected;
- whether the current active-config API tree looks like local-only evidence;
- whether a side-by-side `effective-running`/merged configuration POC is needed.

Fleet-wide scale-out remains blocked until this scope question is resolved.
Native Panorama/device backup export is also deliberately deferred; it is a
separate recovery artifact from this readable/diffable configuration evidence.

## Vendor references used for the method decision

- Palo Alto Networks, *PAN-OS XML API Request Types and Actions*: `action=show`
  retrieves active configuration and `action=get` retrieves candidate
  configuration.
- Palo Alto Networks, *Query a Firewall from Panorama*: `target=<serial>`
  redirects Panorama API queries to a managed firewall.
- Palo Alto Networks, *Preview, Validate, or Commit Configuration Changes*:
  Panorama-managed firewalls distinguish local configuration from pushed and
  merged configuration; the documented verification commands include
  `show config pushed-template` and `show config merged`.
- Palo Alto Networks CLI hierarchy documents `show config effective-running`.
- Palo Alto Networks, *Save and Export Panorama and Firewall Configurations*:
  Panorama can generate/export Panorama and devices configuration bundles for
  recovery/backup workflows. That is a later artifact class and is not invoked
  by A2.1.
