# SecurityExpert 0.6.1B.1 — Check Point Configuration Coverage + Device UX Hardening

## Purpose

0.6.1B proved the production Check Point current-configuration pipeline for Standalone gateways, ClusterXL members, VSX hosts, and VSX contexts. The first real fleet run produced useful configuration data but also exposed three hardening needs:

1. `Failed` was too coarse to distinguish transport/authentication failures from platform capability differences, especially Quantum Spark / Gaia Embedded.
2. Check Point device header metadata (Model, Serial, HA runtime role) was incomplete.
3. The Configuration UI needed vendor fleet separation, logical VSX grouping, collapsible device identity, and responsive behavior for smaller screens.

0.6.1B.1 addresses those gaps without changing the proven Check Point inventory collector, VSX runtime collector, PAN collectors, alignment semantics, or CAS storage model.

## Read-only collection contract

The environment-proven login contract remains:

```text
SSH login -> Expert shell -> explicit clish -c 'show ...'
```

All Gaia adapter commands accepted by `_run_gaia_read()` must begin with `show `. A write verb is rejected before SSH execution.

For appliances whose SSH exec channel lands in Gaia Clish, the adapter has a narrow read-only fallback:

```text
clish -c 'show ...' -> if CLI-shape rejected -> direct 'show ...'
```

This fallback does not introduce configuration writes and does not replace the estate-wide Expert-shell contract.

VSX configuration evidence continues to use the A.1 validated context path:

```text
Expert -> vsenv <numeric VSID> -> clish -c 'show configuration'
```

with the already validated Clish context-selector fallback retained.

## Quantum Spark / Gaia Embedded handling

Platform classification is evidence-driven and conservative.

Strong evidence:
- explicit `Gaia Embedded`
- explicit `Quantum Spark`
- explicit Spark appliance/product marker

Medium-confidence hint:
- exact known 4-digit Spark family/model token found on a product/model/appliance identity line

The model-token rule is deliberately bounded. For example, `15000` is not treated as `1500`, and an arbitrary four-digit year/build number is not treated as a Spark model.

Platform state is reported as:
- `gaia`
- `gaia_embedded`
- `unknown`

0.6.1B.1 does not assume that all prior collection failures are Spark devices. The next real run will provide the evidence distribution.

## Failure taxonomy

Unavailable Check Point configuration is no longer represented by one opaque failure bucket.

Key families include:
- reachability failure
- authentication failure
- authorization failure
- identity failure
- operational command failure
- VSX context failure
- collector failure
- platform capability gap

A command that is explicitly unsupported on a device classified as Gaia Embedded is recorded as a `capability_gap`, not as a transport/authentication failure.

Authorization errors remain authorization failures even on Spark; they are never downgraded to capability gaps.

A successful command whose output does not contain the expected canonical `set ...` configuration shape is explicitly classified. On Gaia Embedded this becomes a capability/shape gap; on regular Gaia it remains an operational anomaly requiring review.

The safe summary now also reports:
- observed vs planned configuration entities
- unmaterialized planned entities
- failure families
- failure reasons
- platform coverage
- management-reported-down physical hosts
- unclassified platform entities
- entity-type coverage

`collector_gate` and `coverage_complete` are intentionally different:
- capability gaps do not automatically become operational failures
- 100% coverage still requires all planned entities to have current evidence

## Model and Serial metadata

The collector reads scalar hardware identity evidence with read-only Gaia commands and immediately discards raw command output after scalar extraction.

Parser coverage accepts common `label: value`, `label=value`, and column/table layouts for:
- Model / Model Name / Product Model / Appliance Model / Appliance Type
- Serial Number / Serial / Serial No / Chassis Serial / Appliance Serial Number

No raw asset output is stored in browser payloads or support bundles.

## ClusterXL / VSX HA runtime role

HA role is runtime evidence only. It is never inferred from device names, member suffixes, or static configuration.

For ClusterXL members and VSX hosts the collector performs the read-only Expert command:

```text
cphaprob state
```

The parser accepts only a state associated with the local member (or the exact observed Gaia hostname). Example runtime values include ACTIVE, STANDBY, READY, DOWN and vendor compound states when present.

HA-role collection is best-effort and failure-isolated: inability to obtain a role must not discard otherwise-valid configuration evidence.

## VSX logical presentation

Actual evidence identity remains unchanged:

```text
physical member endpoint + numeric VSID
```

If an authoritative cluster group id is available, it is used for UI grouping.

If an authoritative group id is absent but conventional VSX member naming clearly identifies a pair, B.1 creates a **presentation-only** inferred group. This is explicitly marked `inferred_member_name_pattern_presentation_only` and is never used as an evidence identity, alignment key, or collection target.

The UI can therefore present:

```text
VSX Cluster / VSX Pair
  Members
    member-1
    member-2
  Virtual Systems
    logical VS A
      member-1 · VSID n
      member-2 · VSID n
```

The logical VS appears once while both member-specific actual evidence views remain inspectable.

## Configuration Fleet information architecture

Configuration now has vendor fleet filters:
- All
- Check Point
- Palo Alto

Each filter includes entity counts and scopes the Configuration fleet view. This is information-architecture groundwork for future bulk workflows only; 0.6.1B.1 adds no write or bulk operation.

The Check Point fleet overview adds platform-aware coverage diagnostics for:
- regular Gaia
- Quantum Spark / Gaia Embedded
- unclassified platform
- Standalone
- ClusterXL members
- VSX hosts
- VSX virtual systems
- operational failures
- capability gaps
- management-reported-down hosts
- Model / Serial / HA coverage

## Responsive and collapsible device UX

Device identity facts can be collapsed into a compact summary bar. The preference is local to the browser export when storage is available.

Default behavior:
- large viewport: expanded
- medium/small viewport: compact by default

At smaller widths:
- Configuration device fleet becomes an off-canvas device drawer
- device facts collapse to one/two columns as space permits
- configuration sections become one column
- wide tables use contained horizontal scrolling instead of breaking the full page

The responsive component is vendor-neutral and applies to PAN and Check Point views.

## Secret and evidence contract

Unchanged from 0.6.1B:
- raw `show configuration` is memory-only
- secret-bearing lines are withheld before artifact persistence
- raw config is not written to CAS, UI, telemetry, or support bundles
- redacted configuration is stored in CAS/history
- full canonical SHA-256 is used only as an internal change fingerprint so secret-only changes remain detectable without storing secret values

## Compatibility / non-goals

Unchanged:
- Check Point inventory method
- ClusterXL inventory semantics
- VSX runtime collection method
- PAN runtime/config/alignment
- Configuration vs Alignment separation
- CAS object/history semantics
- host-key compatibility debt (strict trusted host keys remain required for controlled production)

Not implemented in this increment:
- Check Point Management intent ↔ actual Alignment
- write/change automation
- bulk actions
- native recovery backup

## Validation command

Use the existing CP-only configuration development workflow; do not rerun the full fleet integration checkpoint yet:

```powershell
py.exe -B .\main.py --cp-config-collect --cp-config-stage all
```

Expected safe summary includes platform/failure/entity coverage. Share only the safe console summary and screenshots. Keep `output\cp_config_telemetry.json` local because it contains real device identity, addresses, and non-secret configuration values.

## Real-environment Definition of Done

B.1 is ready for real-environment validation when:
- Configuration collection still returns usable Standalone/ClusterXL/VSX evidence.
- Spark/Embedded devices, if present, are visibly classified from evidence.
- capability gaps and operational failures are not conflated.
- Model/Serial coverage materially improves where Gaia exposes the fields.
- ClusterXL/VSX runtime roles appear when `cphaprob state` exposes local state.
- VSX pair presentation no longer duplicates the same logical VS at the top level.
- All / Check Point / Palo Alto fleet filters work.
- header collapse/expand works.
- small-screen device drawer and one-column layouts remain usable.
- raw secret-bearing configuration remains absent from browser/support output.
