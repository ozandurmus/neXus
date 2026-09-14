# Palo Alto discovery — Panorama XML API route gate entries

## Status

**APPROVED — PRODUCT OWNER, 2026-09-14.** The Product Owner approved the
command literals below for their stated purpose (discovery enumeration) and
class (read-only, class 0) in session on 2026-09-14 ("discovery komutlarını
onaylıyorum"). This is the command-level gate approval `AGENTS.md` requires;
the first live run of the aligned transport is now permitted, on the
Product Owner's own management server, with the field bindings still
`UNVERIFIED` until that run reports its counts. Nothing beyond the literals
listed here is approved.

*Status before approval, kept for provenance:*

(Was: DRAFT — PENDING PRODUCT OWNER GATE APPROVAL.) This document records, for
`docs/AI_DEVELOPMENT_PROTOCOL.md`'s "Network-device command gate", the ten
required items for each of the two routes `PanoramaEnumerationAdapter`
(`ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/pan/`)
can issue, built by `PanoramaApiRoutes`
(`ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/discovery/pan/PanoramaApiRoutes.java`).
**It authorizes nothing until the Product Owner approves it.** Nothing here
may be cited as command approval, and neither route may be run against a
real Panorama before that approval — the collection-type gate lift recorded
in `docs/design/PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md`
covers the two *methods* (key generation, managed-device enumeration); this
document is the separate, still-open command-level gate `AGENTS.md`
requires before either concrete route is issued for real. Mirrors
`CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md`'s shape for Check Point.

Every field name `PanoramaEnumerationAdapter` binds through
`PanoramaApiFieldBinding` (via `PanoramaResponseParser`) is `UNVERIFIED`: a
candidate this movement could not measure, because the only Panorama that
could confirm it belongs to the Product Owner. This document does not
assert vendor semantics; it records what this transport movement would
send, so the Product Owner can confirm or correct it against the real
server in one place.

**T-1 key-disposal limitation.** The vendor's key-generation call has no
corresponding logout/invalidate call in this vendor's authorized method set
(`PO_DECISION_RECORD_2026_09_13B` §2 lifts exactly two methods, neither of
which is a key-invalidation call). "One authenticated session... opened
once per discovery run and closed when the run ends" (contract T-1)
therefore means, concretely: `PanoramaEnumerationAdapter` discards the
in-memory key (zeroes the `char[]`) at the end of every run, on every code
path, and the result reports `KeyDisposalOutcome.DISCARDED` (or
`NOT_OBTAINED` if a key was never obtained). This is a recorded limitation,
not a session left open — no server-side session-termination call exists in
the authorized method set for this transport to make.

## How the ten items map to each entry

1. **Why required** — the contract clause the route discharges.
2. **Taxonomy class** — `utils/action_taxonomy.py`. Both entries below are
   `CLASS_0_READ`; neither entry may become anything else without its own
   new gate review.
3. **Vendor / platform / transport / context** — Palo Alto Networks,
   through Panorama; HTTPS to the XML API (`PanXmlApiTransport`, built on
   `java.net.http.HttpClient`), one POST per call, `/api/` path.
4. **Timeout** — the adapter's fixed request timeout (30 seconds) applies
   to both calls; not configurable per call at this movement.
5. **Retry** — none. A failed call fails the run; this movement introduces
   no retry loop for either route.
6. **Maximum execution frequency per endpoint** — one full discovery run at
   a time, invoked manually by the Product Owner's runner
   (`PanDiscoveryRunnerMain`); this movement adds no scheduler and no
   automatic re-invocation (T-6, `AGENTS.md` "Engineering laws").
7. **Existing-session reuse** — `xmlApiCall` is session-less by the
   transport port's own signature (no `TransportSession` parameter); the
   key obtained from route 1 is held in memory by the adapter and attached
   to route 2's header. Neither route opens or reuses an SSH/shell session.
8. **Unsupported behaviour** — a non-2xx HTTP status, a response missing
   the bound field, or a DOCTYPE/external-entity-bearing response is
   treated as a failed call (`PanoramaQueryFailedException` for parsing;
   a typed `Failed` result for the run) and ends the run, never retried and
   never guessed at.
9. **Secret-bearing output risk** — route 1's request body carries the
   resolved username and password (T-1: form-encoded body, never the URL or
   a header); route 2's request header carries the session key (T-1: header,
   never a query parameter). Neither route's *response* is documented by
   this movement to carry a secret. The key is zeroed from memory at the
   end of every run and never appears in a log line, exception message,
   result object or the runner's report (`PanDiscoveryRunReportTest`).
10. **Safe telemetry** — the run result and the runner's report carry only
    counts and shapes (request count, key-disposal outcome, device/virtual-
    system row counts, pairing-outcome counts, connection-state-value
    counts); no request body, no response body and no parsed field value is
    retained or printed outside the returned candidate set itself.

## The two entries

| # | Route (built by) | 1. Why required | 2. Class | 3. Vendor/platform/transport/context | 4. Timeout | 5. Retry | 6. Max frequency/endpoint | 7. Session reuse | 8. Unsupported behaviour | 9. Secret risk | 10. Safe telemetry |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `PanoramaApiRoutes.keyGeneration(username, password)` | T-1: the vendor's own key-generation call, opening the one session a run uses for the enumeration call | CLASS_0_READ | Palo Alto Panorama, HTTPS XML API, `PanXmlApiTransport`, POST `/api/`, `type=keygen` | 30s | none | one per discovery run | first call of the run; route 2 reuses the key it returns | non-2xx or missing key element → `Failed`, `KeyDisposalOutcome.NOT_OBTAINED`, run ends | request body carries username/password, form-encoded, never the URL; response is expected to carry only the key, never logged | none (no candidate/count derives from this call) |
| 2 | `PanoramaApiRoutes.managedDeviceEnumeration(key)` | T-2: the one authorized read-only managed-device enumeration | CLASS_0_READ | Palo Alto Panorama, HTTPS XML API, `PanXmlApiTransport`, POST `/api/`, `type=op`, `cmd=<show><devices><all></devices></show>` | 30s | none | one per discovery run, immediately after route 1 | reuses route 1's key, carried in the `X-PAN-KEY` request header (T-1), never a query parameter | non-2xx or malformed/DOCTYPE-bearing response → `Failed`, `KeyDisposalOutcome.DISCARDED`, run ends | request header carries the session key, never the URL; response carries every candidate field this movement reads (§9 secret risk table entry above covers the key itself, not candidate data) | request count, device/virtual-system row counts, pairing-outcome counts, connection-state-value counts — never a raw field value or the raw response body |

## No request outside these two

`PanoramaApiRoutes.isMemberOfClosedSet` is the machine-checked mirror of
the two rows above; `PanoramaEnumerationAdapterTest.everyRequestIssuedIsAMemberOfTheClosedRouteSet`
(`ui2/worker/src/test/java/...`) asserts every request a full fixture run
issues is one of them, and that no request ever carries a `target`
parameter. Adding a third route requires both a new row here and a new
closed-set entry — never one without the other, and never without a new
`PO_DECISION_RECORD` gate lift first (the current lift authorizes exactly
these two methods and nothing else).

## Cross-references

- `docs/design/PAN_DISCOVERY_CONTRACT.md` (FROZEN) §3, §11 check 16 — the
  contract this transport implements.
- `docs/design/PO_DECISION_RECORD_2026_09_13B_PAN_DISCOVERY_COLLECTION_GATE.md`
  (FROZEN) — the collection-type gate lift these two routes stay inside;
  distinct from this document's still-open command-level gate.
- `docs/design/CP_DISCOVERY_MANAGEMENT_COMMAND_GATE_ENTRIES.md` — the shape
  this document follows for Check Point.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` "Network-device command gate" — the
  ten-item structure this document follows.
