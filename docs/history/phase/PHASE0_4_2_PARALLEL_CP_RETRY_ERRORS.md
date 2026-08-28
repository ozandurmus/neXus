# Phase 0.4.2 - Bounded Parallel CP Collection, Retry, and Error Reporting

## Goal

Reduce Check Point collection time and remove timeout-induced false empties without changing the validated live data source. Phase 0.4.2 also makes partial collection explicit in the run manifest and shareable support bundle.

## CP collection method remains live

Discovery is still performed on the MDS with the existing `cpmiquerybin` query. Runtime data is still collected from gateways through `cprid_util` using:

- `ip -details -4 addr show`
- `ip -4 route show table all`

The collection source and parser contract are not replaced.

## What changed

### Bounded parallelism across gateways

Gateway workers are now executed concurrently with a conservative bounded fan-out. The default is 6 workers.

Inside a single gateway worker, interface collection and route collection remain sequential. This avoids issuing two simultaneous runtime queries to the same gateway while still allowing different gateways to be collected in parallel.

Configuration:

- `FBUDDY_CP_PARALLELISM` (default `6`)

### Duplicate runtime calls removed

The previous shell script queried each gateway twice for interfaces and twice for routes because CSV and RAW outputs were collected separately.

Phase 0.4.2 performs one live interface capture and one live route capture per attempt. The legacy CSV files are derived from those same RAW captures, so the CSV feature is preserved without a second remote query.

### One bounded retry

A failed or empty first capture is retried once with a longer timeout.

Configuration:

- `FBUDDY_CP_FIRST_TIMEOUT_SECONDS` (default `10`)
- `FBUDDY_CP_RETRY_TIMEOUT_SECONDS` (default `30`)
- `FBUDDY_CP_MAX_RETRIES` (default `1`, clamped to one retry in this phase)

The retry is performed only for a failed or empty command result. A successful first result is not repeated.

### Extended per-device telemetry

`.collection_status.tsv` keeps its original first three fields for compatibility and appends:

- interface / route attempt count
- first return code
- final error classification
- first-attempt error classification

Error classes are deliberately simple and safe:

- `none`
- `timeout`
- `command_error`
- `empty_output`
- `mdsenv_error`

`cp_telemetry.json` summarizes successful, failed, retried, and recovered devices plus the actual parallelism/timeout parameters used by the remote collector.

### Error reporting in support bundle

The shareable support bundle now includes `errors.json`.

It contains HMAC-pseudonymized CP failed devices, VSX command/completeness failures, and Panorama unavailable/failed devices. Real device names, IP addresses, serials, contexts, and HMAC key material are not included.

### Degraded run semantics

A collector can complete technically while some devices fail. Phase 0.4.2 distinguishes that case from full success:

- CP stage becomes `degraded` when one or more CP devices fail after retry.
- Panorama stage becomes `degraded` when one or more discovered devices cannot be collected.
- A full run with a degraded collection stage is written as top-level `degraded` rather than `completed`.

This does not yet implement last-known-good carry-forward. That remains the Phase 0.5 goal.

## Why the parallel design is bounded

Unbounded fan-out could overload the MDS, create too many simultaneous `cprid_util` processes, or put avoidable load on managed gateways. Bounded parallelism gives most of the runtime benefit while keeping resource usage predictable.

The default of six workers is intentionally configurable so a lower value can be used if the MDS shows load pressure.

## Compatibility

The following remain unchanged:

- CP discovery query
- CP runtime data commands
- CP Python interface/route parsers
- VSX collection/parser method
- Panorama collection/parser method
- merge schema
- HTML/UI contract
- output filenames

Legacy `gw_interfaces.csv` and `cp_routes.csv` are still generated on the MDS.

## Regression result

`33 passed, 2 xfailed, 0 failed`

The two expected failures are the pre-existing characterized data-semantics items and are not changed in this patch.
