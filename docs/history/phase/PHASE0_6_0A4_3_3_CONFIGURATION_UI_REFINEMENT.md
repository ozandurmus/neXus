# SecurityExpert Phase 0.6.0A4.3.3 — Configuration UI Refinement

## Objective

Refine the vendor-neutral Configuration experience so an operator sees real current device values first, while keeping Alignment, Policy & Objects, History and Backup as separate capabilities.

This phase does not introduce a new collector method. Palo Alto current configuration continues to use the already-proven direct `effective-running` evidence collected by the existing PAN configuration pipeline.

Check Point configuration collection is intentionally not started in this phase. The storage API remains ready for future Check Point text artifacts, while standalone Check Point configuration collection stays on the 0.6.1 roadmap and VSX configuration stays on 0.6.2.

## Existing behavior preserved

- Check Point inventory collection
- ClusterXL hierarchy and member rendering
- VSX host/virtual-system hierarchy
- Panorama/PAN runtime collection
- PAN direct API identity gate
- PAN local/merged/effective evidence planes
- expected configuration compiler
- setting-level alignment and semantic policy
- Network Inventory UI contract
- content-addressed configuration history/storage

## Changes

### Current configuration projection

The projection schema advances to `0.6.0A4.3.3`.

Existing non-secret structured values remain sourced only from direct effective-running evidence:

- System
- DNS
- NTP
- Management
- Telemetry
- High Availability
- Network Configuration summary counts

A bounded `highlights` view is now produced from the already-redacted structured rows. It surfaces operator-oriented values such as hostname, domain, timezone, DNS, NTP, HA and management access counts without embedding raw XML or secret-bearing values.

### Configuration device header

The header now includes:

- Vendor
- Model
- Software
- Management IP
- Serial Number
- HA / Role
- VSYS / VS count
- Policy scope
- Config freshness
- Current source plane

### Basic configuration operator snapshot

The Current Configuration tab now renders a compact Basic Configuration / Operator Snapshot above the detailed per-section tables. Origin badges remain vendor-adapted:

- PAN
- LOCAL
- OVERRIDE
- MEMBER
- EFFECTIVE / UNKNOWN when provenance cannot be proven

The detailed tables remain available for all projected values and preserve search/filter behavior.

### Secret handling

The browser payload still excludes:

- raw configuration blobs
- passwords
- private keys
- PSKs
- API/auth keys
- SNMP communities
- credentials/tokens
- value hashes

The UI displays only the count of withheld secret-bearing settings, not their values.

### Vendor roadmap visibility

The Configuration fleet overview now explicitly states:

- Check Point Gaia: storage-ready; configuration collector deferred to 0.6.1
- VSX Host / VS: configuration collector deferred to 0.6.2

This avoids implying that Check Point configuration collection exists in A4.3.3.

## Risk

Primary risk is UI/projection regression, not collection risk, because no new device command/API method is introduced.

No new PAN schema path is required for collection. The Basic Configuration snapshot is derived only from values already present in the existing structured projection.

## Tests

- targeted Configuration UI tests: PASS
- full regression: `137 passed, 2 known xfailed, 0 failed`
- Python compileall: PASS
- JavaScript syntax (`node --check static/app.js`): PASS

Known xfails remain unchanged:

- VSX network canonicalization safety gap
- PAN default-route classification safety gap

## Rollback

Rollback is file-level and non-destructive: restore the prior A4.3.2.1 build. No storage migration or data schema mutation is performed by A4.3.3 itself.

## Definition of done

- Real PAN current configuration values are visible without raw configuration exposure.
- Basic operator values are visible before detailed tables.
- Configuration remains distinct from Alignment and Policy & Objects.
- Device header contains operational identity/freshness context.
- No Check Point configuration collector is introduced.
- Existing Network Inventory behavior remains regression-tested.
- Full test suite remains green aside from the two pre-existing xfails.
