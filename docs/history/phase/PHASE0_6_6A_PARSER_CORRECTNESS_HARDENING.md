# 0.6.6A — CP/PAN Parser Correctness Hardening

## Status

**PLANNED — architecture contract frozen 2026-08-27**

Product baseline: `0.6.5 REAL_ENV_VALIDATED`.

## Objective

Close two existing parser characterization defects without changing collection
transport, command vocabulary, evidence storage or user-interface contracts:

1. VSX interface `network` must be canonical `network/prefix`, rather than
   the observed `host/prefix` value.
2. PAN default-destination routes must be classified as `default`, even when
   an additional static-route flag is present in the source response.

## Scope

### In scope

- Audit the normalization branch in `checkpoint/vsx_parser.py` used by
  `parse_ifconfig()`.
- Audit route type ordering in `panorama/panorama_runtime_runner.py` used by
  `parse_routes()`.
- Replace exactly the two strict `xfail` characterization tests with passing
  regression expectations.
- Add focused edge-case coverage only where it distinguishes the corrected
  semantic precedence from previous behavior.
- Preserve the existing normalized field names and JSON-compatible types.

### Explicitly out of scope

- Any new device command, management/direct collection path, retry, timeout,
  polling frequency, concurrency, scheduler or coordinator change.
- Changes to endpoint identity, VSX physical endpoint + VSID semantics,
  ClusterXL `MEMBER_SPECIFIC` semantics, TLS/SSH trust policy, CAS/history,
  configuration alignment or UI presentation.
- Broad parser refactor, fixture replacement with real operational evidence,
  new vendor/platform coverage or firewall writes.

## Correctness contract

### VSX interface network

For each parseable interface address, `network` is the CIDR network address
computed from `ip` and `prefix`. The original host address remains in `ip`.
Invalid/missing address input retains existing conservative omission/error
behavior; this build must not invent a network value.

Example synthetic expectation: `10.20.30.2` with prefix `24` becomes
`network=10.20.30.0/24`, while `ip=10.20.30.2` remains unchanged.

### PAN route type precedence

A route whose normalized destination is `0.0.0.0/0` is type `default` before
static/connected flag classification. All non-default routes retain current
connected/static interpretation unless targeted regression evidence proves a
separate issue.

## Privacy and safety invariants

1. Fixtures must stay synthetic and contain no real endpoint, username,
   credential, certificate, raw configuration or management identity.
2. Parser corrections must be pure local transformations; tests create no
   network connections.
3. No raw source response is newly persisted or added to browser payloads.
4. Existing output schemas remain additive-free: only corrected values in
   existing `network`/`type` fields are permitted.

## Implementation plan

1. Locate the two ordering/normalization branches and their direct consumers.
2. Apply the smallest deterministic parser changes satisfying the correctness
   contract.
3. Convert both strict `xfail` cases into ordinary passing regression tests.
4. Run targeted parser tests, affected CP/PAN regression, privacy gate and
   full regression if shared normalization consumers are affected.
5. Inspect output diff for prohibited interaction, storage and UI drift.

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | VSX `network` is canonical CIDR network address while `ip` retains host address. |
| AC-2 | PAN `0.0.0.0/0` routes are classified as `default` irrespective of static flag ordering. |
| AC-3 | The two known strict `xfail` tests become passing regressions in the same change. |
| AC-4 | Existing non-default PAN route and VSX interface fields retain documented behavior. |
| AC-5 | No device command, network access, retry/timeout, polling, concurrency, scheduler, CAS or UI behavior changes. |
| AC-6 | Targeted tests, impacted vendor regression and repository privacy gate pass with no new xfail. |

## Validation and merge gate

This is a local parser-semantic build; no real-device run is required unless a
regression indicates source-response ambiguity. Merge to `main` requires
AC-1 through AC-6, clean privacy output and a focused diff review. A normal
`--render-only` validation is recommended after automated tests because
normalized fields feed existing inventory/configuration projections.

## Definition of done

`DONE` only when the two named xfails are removed, replacement regressions
pass, no new xfails are introduced and current project metadata records the
closure.
