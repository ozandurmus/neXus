# Masking leak audit: compare aiview responses against the raw tables (hostnames, addresses, serials) and report any raw value that survives masking; consider a masked read-model/schema with a separate DB role for aiview

status: planned · target: PO 2026-09-22 proposal (separate masked table read by aiview)

2026-09-22 F20 found under aiview: cluster pseudonyms collide -- the space is one dictionary word x a digit 1-9 (hash[0] % dictionary, hash[1] % 9), so ~40 clusters produce collisions; the aiview device list shows masked cluster refs with 4, 5 and 6 members and 35-36 clusters instead of 39. Two real clusters merge in every aiview screen (Overview count, Operations, Configuration tree). Fix: collision-free pseudonyms (wider suffix, or a persisted pseudonym dictionary that guarantees uniqueness) + the leak/collision audit comparing masked vs raw distinct counts.

2026-09-23 F20 fixed: V53 pseudonym_registry makes cluster and standalone-device pseudonyms unique (computed candidate first, next free name otherwise, claim persisted by keyed hash, never the raw name). Non-colliding names are unchanged. The leak/collision audit itself is still open.

2026-09-26 aiview accessibility inspection: Devices list management row exposed an unmasked management address in its accessible button description while visible text was masked. Classification: sensitive operational identity. Reproduce under aiview and fix the accessibility-label projection; no raw value retained here.

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Masking leak audit: compare aiview responses against the raw tables (hostnames, addresses, serials) and report any raw value that survives masking; consider a masked read-model/schema with a separate DB role for aiview

Target (full): PO 2026-09-22 proposal (separate masked table read by aiview)

2026-10-20: a first leak was found and fixed by the in-cluster e2e canary, not by this audit: the masked system-log view returned the Palo Alto serial field unmasked (fixed 2026-10-18; the masked view now runs serial, estate-dictionary and heuristic passes, rule set logmask-v3, fail closed with 503 SYSTEM_LOG_MASKING_UNAVAILABLE). The systematic audit of every aiview response against raw tables is still open.

2026-11-14 backlog truth: still open (P1, size M). Today: No proof that masked views leak nothing; leaks were found by chance. Evidence: Only incident fixes (V53 pseudonyms, logmask-v3/v4); no systematic raw-vs-masked audit in tools/ or tests

2026-11-15 10:00 backlog refresh: In the ship queue (batches b4..b9, not yet on main). Partial. Commits 'Masking leak audit: aiview route/body-type audit, identity-key sweep, cold-process free-text masking fix' (AiviewMaskingLeakAuditTest), 'aiview free-text masking: serials and administrator names from the estate dictionary' and 'aiview free-text masking fails closed on a cold process'. Still open: the original ask, a comparison of real aiview responses against the raw tables with a count of surviving raw values, and the separate masked read-model/role idea (design: aiview masked admin DRAFT, DB roles DRAFT).

2026-11-23 raw-versus-masked sweep on synthetic data: AiviewRawVersusMaskedSweepTest (ui2 integration-tests, real PostgreSQL) seeds 4 devices, a cluster, IPv4/IPv6 addresses, serials, an administrator, a policy snapshot, rule history, a backup row, a stored system-log line and a static-route preview, logs in as aiview and GETs all 164 aiview-reachable GET routes (shared enumeration AiviewReachableRoutes, service testFixtures) plus every other GET route; counts surviving raw values per route and value class, never values. Found and fixed one leak: Operations > Static routes (GET /api/v2/operations/static-route/preview) returned the raw cluster name as targets[].cluster_id and as a cluster preview's unit_id (3 occurrences); now pseudonymised (StaticRoutePreviewService, two one-line projection changes, unit regression). Result: 0 surviving raw occurrences across 206 GET routes, coverage asserted on 19 key views. Still open: the PO-approved real-data run and the masked read-model/role idea.

HTTP raw-versus-masked sweep on a seeded synthetic estate shipped 1588f36f; one leak (static-route preview cluster name) fixed.
