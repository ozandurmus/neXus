# DEV.0.3B.1 — Direct SSH Runtime Path Closure

Status: implemented; real-environment CP validation pending.

## Problem
The direct SSH fallback probe resolved its final artifact through RuntimePaths but still created/replaced its temporary artifact through the legacy module-level `output/` path. With a repository-safe workspace that has no repository `output/` directory, the live CP run failed after remote collection completed.

## Change
All three probe persistence branches now derive both the temporary and final `cp_direct_ssh_probe.json` paths from the same effective `output_file`. No repository-output fallback is used when RuntimePaths is present.

## Preserved
- CP commands, target selection, SSH behavior, retries/timeouts and host-key policy are unchanged.
- Probe schema and filename are unchanged.
- Inventory exclusion policy behavior is unchanged.
- History/CAS remains out of scope.

## Validation
- Focused direct-SSH probe suite: 7 passed.
- Full automated regression: 219 passed, 2 known xfailed.
- Local repository privacy gate: PASS, 0 findings on the packaged source candidate.
- Real-environment acceptance: rerun CP partial collection with the external RuntimeRoot and confirm the direct SSH probe completes and writes under RuntimeRoot/output.
