# F-Buddy Phase 0.4 - Run Integrity, Collection Completeness, Freshness Evidence

> **Phase 0.4.1 update:** the manual CP deployment/run step described in this document is superseded. `run_cp()` now uploads the repository `checkpoint/scripts/cp_inventory.sh` to the MDS, verifies the uploaded script hash, executes it, validates the new collection marker, and only then downloads/parses the RAW data. See `PHASE0_4_1_AUTOMATED_CP_COLLECTION.md`.

Phase 0.4 keeps the existing collection commands and data pipeline intact. It adds evidence around them so a run can prove what was collected, whether the command output completed, and whether the artifacts belong to the same run.

## Changes

### 1. Manifest schema 0.4

`data/runs/<run_id>/manifest.json` now contains:

- run timestamps and final status
- per-stage `pending/running/success/failed` state
- stage start/end time and duration
- legacy artifact name/path mapping (kept for compatibility)
- SHA-256, byte size, JSON validity and object count for run artifacts

Core stages:

- `cp`
- `vsx_collect`
- `vsx_parse`
- `panorama`
- `merge`
- `verify`
- `html`

### 2. Check Point freshness evidence - collection method unchanged

The actual gateway commands remain the same:

- `cprid_util ... ip -details -4 addr show`
- `cprid_util ... ip -4 route show table all`

The updated `checkpoint/scripts/cp_inventory.sh` now:

- removes only its own old `*_interfaces.txt` / `*_routes.txt` files before a new collection
- writes `.collection_meta` with start/completion time and discovered gateway count
- writes `.collection_status.tsv` with exit codes for the two RAW commands per gateway

`cp_runner.py` still downloads and parses the same remote RAW files. It additionally writes:

`output/cp_telemetry.json`

with remote file size/mtime/age, collection marker and command result evidence.

Default stale warning threshold is 3600 seconds. Override with:

`FBUDDY_CP_RAW_MAX_AGE_SECONDS`

Set it to `0` to disable the age warning while keeping freshness telemetry.

**Important:** to get the new CP freshness proof, deploy/run the Phase 0.4 `cp_inventory.sh` on the MDS. If the old script is still used, inventory continues but verifier reports `CP_COLLECTION_MARKER_UNAVAILABLE`.

### 3. VSX completeness verification - collection method unchanged

The existing Phase 0.3 prompt-aware reader remains in place. Phase 0.4 promotes its evidence into the normal verifier:

- RAW context count vs parsed context count
- expected vs actual telemetry samples
- prompt seen/missed
- command timeout count
- RAW interface candidates vs parsed interfaces
- RAW route candidates vs parsed routes

The actual VSX commands remain unchanged:

- `cphaprob stat`
- `vsx stat -v`
- `vsenv <id>; ...; ifconfig`
- `vsenv <id>; ...; ip route`

### 4. Panorama operational-response validation - operational commands unchanged

The collection commands remain:

- `<show><devices><all>...`
- targeted `<show><interface>all...`
- targeted `<show><routing><route>...`

Phase 0.4 now validates HTTP/API success before parsing the response. A Panorama API error can no longer silently become an apparently valid empty inventory object.

New file:

`output/panorama_telemetry.json`

It records:

- discovered devices
- connected yes/no counts reported by Panorama
- successful/failed targeted collections
- interface/route request duration and parsed counts

### 5. Verification schema 0.4

`verification.json` remains observe-only and does not block the current pipeline.

New section:

`collection_integrity`

for:

- CP RAW freshness / marker / command results
- VSX command completion / raw-to-parsed preservation
- Panorama targeted API success/failure

The existing Panorama empty-inventory finding remains a warning.

### 6. Support bundle v2

The existing HMAC pseudonymization remains. The bundle now also includes anonymized:

- CP freshness and RAW command status
- VSX completeness checks
- Panorama discovery/target collection status
- stage states and artifact integrity from manifest schema 0.4

The HMAC key is still not included in the bundle.

## Compatibility

Existing published outputs remain:

- `output/cp.json`
- `output/vsx_raw.json`
- `output/vsx.json`
- `output/panorama_runtime.json`
- `output/unified.json`
- `output/verification.json`
- `output/index.html`

Additional local diagnostic files:

- `output/cp_telemetry.json`
- `output/vsx_telemetry.json`
- `output/panorama_telemetry.json`
- `output/support_bundle_<run_id>.zip`

No UI contract was intentionally changed.

## Test status

`25 passed, 2 xfailed, 0 failed`

The two existing xfails are intentionally retained data-semantic characterization items and are not changed in Phase 0.4.

## Recommended test

1. Deploy/run the included `checkpoint/scripts/cp_inventory.sh` on the MDS using the same operational procedure as before.
2. Run locally:

   `py.exe .\main.py`

3. Send only:

   `output/support_bundle_<run_id>.zip`

The support bundle should be sufficient for Phase 0.4 review without sharing real inventory JSON files.
