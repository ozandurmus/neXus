> **Phase 0.4.2 update:** CP orchestration remains automated, but gateway collection is now bounded-parallel with one retry and explicit degraded/error telemetry. See `PHASE0_4_2_PARALLEL_CP_RETRY_ERRORS.md`.

# Phase 0.4.1 - Automated Check Point Collection

## Goal

Remove the manual MDS step from Check Point inventory collection while preserving the already validated collection method.

A normal local run now performs the complete CP flow:

1. SSH to the configured MDS with the runtime credentials already used by F-Buddy.
2. Upload the repository copy of `checkpoint/scripts/cp_inventory.sh` to `/home/admin/cp_inventory.sh`.
3. Read the uploaded bytes back over SFTP and verify that the local and remote SHA-256 values match.
4. Remove only the previous `.collection_meta` and `.collection_status.tsv` markers so a failed launch cannot make old RAW files look current.
5. Execute `bash -l /home/admin/cp_inventory.sh` on the MDS and wait for its `DONE` marker and remote exit status.
6. The script uses the existing CP method (`cpmiquerybin` discovery + `cprid_util` + live `ip` commands), deletes only collector-owned old RAW interface/route files, and creates new collection markers/status.
7. Python validates the newly created marker, downloads the new RAW set, parses it, and writes `cp.json` plus `cp_telemetry.json`.

No manual upload or manual shell execution is required.

## Collection method deliberately unchanged

This patch does **not** replace the CP data source or commands. The bundled shell script still uses the Phase 0.4 collection logic. Only orchestration changed from "operator runs the script first" to "F-Buddy deploys and runs its own version first".

## Additional integrity evidence

`cp_telemetry.json` now records:

- local collector SHA-256
- uploaded remote collector SHA-256
- upload verification result
- remote process exit status
- whether `DONE` was observed
- reported/processed gateway counts
- remote collection marker/status and RAW file freshness data already introduced in Phase 0.4

The shareable support bundle exposes these non-sensitive integrity fields so a support bundle can prove that the current application run launched the exact bundled collector.

## Privacy

Live CP progress displays counts only, for example:

`[CP 14 / 83] collecting live data...`

The gateway name and management IP printed by the remote script are intentionally not echoed into the local console/log by the Python orchestrator.

## Compatibility

Existing output names, parsers, merge behavior, UI contract, VSX collector, and Panorama collector remain unchanged.
