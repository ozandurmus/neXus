# Phase 0.5 - Failure-Aware Snapshot + UI Freshness

## Goal
Preserve the working collectors while preventing collection failure, management-down state, or Panorama disconnects from being confused with inventory removal. Expose freshness directly in the existing static HTML UI.

## Behavior
- Successful current collection: `inventory_status.data_state=live`, green `LIVE` badge, current collection timestamp.
- Explicitly unavailable device with previous good data: previous entity data is retained as `last_known_good`, red `OLD DATA` badge, last successful collection timestamp.
- Explicitly unavailable device without previous good data: zero-data placeholder with red `NO LIVE DATA` badge.
- Partial CP collection is never shown as LIVE. Previous complete data is preferred when available; otherwise it is marked `PARTIAL DATA`.
- CP management states such as `uninitialized` remain vendor evidence and are shown as availability state; they are not rewritten as collector failures.
- Panorama `connected=no` becomes `availability_state=disconnected` and can use last-known-good data.
- Successful VSX entities are marked LIVE; existing collection method and prompt-aware reader are unchanged.

## Persistent local state
`data/state/last_known_good.json` stores the last successful entity payload locally. It is not included in the support bundle. Keep this directory between runs/build upgrades. In a future container/Kubernetes deployment this directory is intended to live on persistent storage/PVC rather than inside the ephemeral pod filesystem.

## Pipeline
`collect -> parse -> snapshot -> merge -> verify -> html -> support bundle`

The snapshot stage writes temporary effective source files in the run stage directory and merges those instead of raw current-only source lists.

## UI
Existing UI features are preserved. Device rows and the detail header now show:
- green dot + `LIVE` + `Updated: <timestamp>`
- red dot + `OLD DATA` + `Last live: <timestamp>`
- red dot + `NO LIVE DATA` when no successful history exists
- red `PARTIAL DATA` for incomplete CP data without a complete historical copy

The top bar also reports live versus old/unavailable logical-device counts.

## Compatibility
The legacy output files remain:
- `output/cp.json`
- `output/vsx_raw.json`
- `output/vsx.json`
- `output/panorama_runtime.json`
- `output/unified.json`
- `output/index.html`
- `output/verification.json`
- support bundle ZIP

Collectors, CLI usage, filters, searches, interface/routing tables and static HTML delivery are retained.

## Tests
`41 passed, 2 xfailed`.
The two existing xfails remain the previously tracked data-semantic issues and were not changed in this phase.
