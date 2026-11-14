# API latency measurement and fixes, 2026-11-14

Status: MEASUREMENT RECORD (not a contract). Branch `perf/api-endpoints-1114`.

The Product Owner found the UI slow. A screen-by-screen measurement on the live app (warm browser cache, server time
to first byte) listed eleven endpoints at 0.5-2.3 s. This record names where the time went, what changed, and how
each number was measured, so a later measurement can repeat it.

## How the numbers were taken

- Live server times ("before"): the Product Owner session's screen measurement of 2026-11-14.
- Database times: `EXPLAIN (ANALYZE, BUFFERS)` of the statement the code runs, on the live ui2 database, read-only
  (`echo "<sql>" | ops-scripts/dbq.sh`). Rewrites were checked for identical results with
  `select md5(string_agg(x::text, ',' order by x::text)) from (<old>) x` against the same for `<new>`.
- Index-dependent "after" plans need the indexes, which a read-only session cannot create: they are measured by the
  V216 dry run (`ops-scripts/dryrun.sh` on `ops-scripts/perf-1114/V216_dryrun_with_after_plans.sql`: the migration,
  then `EXPLAIN (ANALYZE, BUFFERS)` of the four reads it serves, all rolled back). Those four statements are
  `docs/reference/API_LATENCY_2026_11_14_after_queries.sql`; run alone they give the "before" plans.
- End-to-end "after" server times need a deploy: repeat the screen measurement.

## The common cause

`JooqDeviceRepository.DEVICE_SUMMARY_SELECT` (the device list) took 510-547 ms for 136 devices and ran behind
`/devices`, Overview, Compliance (twice per request), the change feed, the failover summary, Lifecycle, diagnostics
targets and System storage. Its per-device lookups had no usable index: the discovery-candidate match (three OR'd
expression arms, a seq scan of 15.9 k candidates per device, ~288 ms), the virtual-system prefix match (~138 ms),
the latest job per device (~62 ms) and the parent candidate (~20 ms). The name-identity read
(`NAME_IDENTITY_QUERY`, 303 ms, behind the policy and change-feed name caches) has the same match.
V216 adds one index per predicate, and the virtual-system match gains an index-usable "C"-collation range next to
its LIKE (0 of 1112 live pairs differ).

## Per endpoint

| Endpoint | Before (live) | Where the time went | Change | After (measured) |
|---|---|---|---|---|
| `/api/v2/system/pods` | 1.1-1.3 s | Kubernetes API reads, then module health ran a full `jobs` scan per module (10 x ~100 ms) | Background snapshot (refreshed every 10 s while read; body states `read_at`, `cache_age_seconds`, `refresh_every_seconds`); the per-module scans became one grouped read | grouped read 15 ms (was ~1 s); request path does no reads (deploy to confirm) |
| `/api/v2/system/storage` | 0.58 s | device list 0.5 s, backup sums 67 ms | Background snapshot, 30 s | request path does no reads (deploy to confirm) |
| `/api/v2/overview` | 2.1 s | a 60 s cache rebuilt on the request thread on each miss | Same 60 s staleness, rebuilt in the background; only the first read after a pod start builds inline | request path does no reads after warm-up (deploy to confirm) |
| `/compliance/overview`, `/compliance/controls` | 1.47 s, 1.25-1.5 s | target list = two device-list reads (2 x 518 ms) per request, plus an uncached catalog HTTP read for controls | Target list shared and refreshed in the background every 10 s; catalog kept until the rules fingerprint changes (re-read at most every 5 min) | target list off the request path; remaining work is the existing in-memory evaluation cache (deploy to confirm) |
| `/api/v2/policy/devices` | 1.35 s | rule counts decompressed every policy snapshot (261 MB of jsonb) per request (~1 s); name identity 303 ms per 30 s cache expiry | Rule count kept per (policy, snapshot version); recounted only for a changed snapshot | catalog read 0.63 ms (was ~1 s); name identity: V216 dry run |
| `/api/v2/changes/feed` | 2.3 s | policy rows 506 ms (current-snapshot label fallback expanded 14 snapshots per request, 471 ms); device list 530 ms | Fallback labels kept per (policy, snapshot version), same first-match rule | policy rows 23 ms (was 506 ms); device list: V216 dry run. About 1 s of the live time is not database time and was not measured (see open items) |
| `/api/v2/cp-failover/summary` | 0.73-1.1 s | device list 530 ms, readiness 128 ms, latest run per unit 52 ms, members 21 ms | Readiness check rows read per run through the unique index; V216 run-history indexes | readiness 23.5 ms (was 128 ms, identical rows); device list and latest-run: V216 dry run |
| `/api/v2/lifecycle` | 0.6-0.9 s | device list 530 ms, latest inventory per device 17.7 ms | Latest run per device through the existing index | inventory read 1.6 ms (was 17.7 ms, identical rows); device list: V216 dry run |
| `/devices` | 0.52-0.57 s | device list ~0.5 s of it | V216 | V216 dry run |
| `/api/v2/diagnostics/targets` | 0.52 s | filtered device list 371 ms, latest contexts 159 ms | Contexts read per latest run through the run index | contexts 22 ms (was 159 ms, identical rows); device list: V216 dry run |

## Open items (stated, not fixed here)

- Change feed: about 1 s of the 2.3 s is Java-side work (projection of up to 500 rows, chunk decrypt on a cold
  cache, serialisation of ~300 KB). Needs a timing log or a profile on the live pod before a fix.
- Lifecycle: advisory matching (`AdvisoryEstate.evaluate`, 799 advisories x 136 devices) is unmeasured Java work,
  estimated 50-300 ms.
- Diagnostics targets: the CPVIEW eligibility check still reads each Check Point gateway and its latest inventory run
  (estimated 30-50 ms). It is a command-eligibility gate, so it is left as it is.
- `failover_run` keeps every readiness run (about 154 per unit); retention would keep the run-history reads flat.
- The e2e latency allowlist entry for the change feed (expires 2026-12-14) can go once the deployed feed is measured.
