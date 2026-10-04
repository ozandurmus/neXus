# SecurityExpert Phase 0.6.0A4.3.3.1 — PAN Device Header Evidence Mapping

## Goal
Close two real-environment Configuration header gaps observed after A4.3.3 without changing collectors or evidence semantics.

## Changes
- Device Group now prefers the authoritative local `row.panorama_assignment` produced by Panorama intent mapping.
- Compatibility fallback remains for older `configuration_alignment.panorama_assignment` payloads.
- Expected compiler `device_group_assignments` is used only as a final evidence-backed fallback.
- HA / Role uses Panorama runtime `ha_state` when present.
- When runtime role is absent, static effective configuration may show `HA Enabled · Group <id>` or `HA Disabled`; it never infers Active/Passive.
- Generic PAN XML wrapper context `localhost.localdomain` is suppressed from Management/HA operator context.

## Explicit non-changes
- No CP, VSX, PAN runtime, Panorama, or PAN configuration collector changes.
- No new API calls or network round-trips.
- No CAS/history/storage changes.
- No Alignment classification changes.
- No Network Inventory changes.

## Risk
Low. Change is isolated to local Configuration UI projection/header mapping.

## Rollback
Return to A4.3.3 build. No data migration or persistent schema change is required.

## Definition of Done
- Device Group shown from existing Panorama assignment evidence.
- Runtime HA role wins if available.
- Static HA config shown without inventing runtime role when runtime role is unavailable.
- `localhost.localdomain` no longer appears as operator context.
- Full regression remains green.
