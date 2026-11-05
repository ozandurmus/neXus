# ui2-analysis container — architecture design

**Status: DRAFT — DO NOT IMPLEMENT.** This is an investigation and design proposal. It authorizes no code, manifest,
schema or deploy change. Implementation needs this design's security and concurrency review, the PO go-ahead, and the
measurements in §11. Until then, every memory figure here is a working estimate, and every UNKNOWN stays UNKNOWN.

- **Direction:** [PO_DECISION_RECORD_2026_11_05_ANALYSIS_CONTAINER_DIRECTION.md](PO_DECISION_RECORD_2026_11_05_ANALYSIS_CONTAINER_DIRECTION.md)
  (RATIFIED, direction only).
- **Frozen constraints this design must respect:**
  - [MODULE_POD_RUNTIME_CONTRACT.md](MODULE_POD_RUNTIME_CONTRACT.md) (FROZEN), §§1, 3 and 5: one image, drain and
    recovery, snapshot and rollback.
  - The AGENTS.md AIView masking law.
- **Source baseline:** `origin/cloud-base/1105` at `1d2bbf3`. No access to the live system: this was written from the
  repository alone.
- **Process note:** the decision record's order of work names Argon for step 2 (the architecture design). This draft
  was written by a Claude engineering session from the 2026-11-05 cloud brief. Whether it replaces the Argon step or is
  an input to it is the PO's call; this document does not decide that.
- **Order note:** the decision record puts memory measurement (step 1) before design (step 2). This draft comes first
  on paper. Every part of it that depends on memory is gated on §11 M1–M3.

## 0. Summary

1. **One new Deployment, `ui2-analysis`.**
   - It uses the same image with a new launcher role, `analysis`.
   - It owns the parsed policy snapshot cache, the policy read and analysis computation, and the topology graph with
     path queries.
   - It has no public route. The browser keeps talking only to ui2-service.
2. **Recommended API: ui2-service stays the gateway (option B, §2).**
   - The public URLs, `SecurityWebMvcConfig` route→action map, `GateChainInterceptor`, `authz_decisions`, rate limits
     and `PrivacyMaskingResponseBodyAdvice` all stay in ui2-service.
   - ui2-service calls `ui2-analysis` over a private, signed, versioned internal API.
3. **Single masking authority (§3).** ui2-service *decides* the view (masked or raw) and is the *final enforcement
   point* for every byte that reaches a browser. `ui2-analysis` *computes* in the view it is told to use, through the
   same masking library, the same key and the same database-held pseudonym state. It never decides the view.
   - **Precondition:** masking output must not depend on process-local history (§3.2, P0). Today it does.
4. **No drain barrier (§6).**
   - `ui2-analysis` owns no job type, no endpoint admission and no runtime task lease, so it is outside `ROLES` in
     `module_deploy.py`.
   - The drain-deadlock class of 2026-11-04 (a barrier condition that only clears once the old pod is gone) cannot
     arise, because there is no barrier. A fence test keeps it that way.
5. **Migration behind two restart-time flags (§8):** `UI2_ANALYSIS_POLICY_MODE` and `UI2_ANALYSIS_PATH_MODE`, each
   `local | shadow | remote`.
   - Rollback is a flag flip and a ui2-service rollout. No data is restored, and no schema change is needed for the
     cutover itself.
6. **Path validation (A6) stays in ui2-service.** Its submit and read paths admit jobs and write
   `path_validation*` rows. The analysis pod is compute-only except for its own derived topology snapshot rows (§1.3).

## 1. Class moves

### 1.1 Classification (from source)

`policy/` and `pathanalysis/` below are under `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/`.

| Class | Today | Class kind | Destination |
|---|---|---|---|
| `policy/PolicyHygiene`, `PolicyNormalization`, `PolicyRuleQuery`, `PolicyRuleMetrics`, `PolicyDomainIndex`, `PolicyLookup` | static or package-private, pure | ANALYSIS | `analysis.policy` (moved together; they share package-private visibility) |
| `policy/HopPolicyEvaluator`, `HopNatEvaluator` | static, pure; used by the path engine | ANALYSIS | `analysis.policy` |
| `policy/OfflineAnalysis`, `OfflineCsv` | static, pure | ANALYSIS | `analysis.policy` |
| `policy/VersionedWeightedCache` | generic weighted LRU | shared utility | `analysis.support` (ui2-service may keep using it) |
| `policy/PolicyQueryService` (1,091 lines) | read facade and **owner of the parsed-snapshot cache** (`snapshotCache`, `domainCache`, `canonicalViews`, `hygieneCache`) | ANALYSIS, shared in practice | split, see §1.2 |
| `policy/LocalFirewallPolicyService` | parses local PAN configs from encrypted artefacts; own cache | ANALYSIS input | `analysis.policy` (needs the artefact store, §4.4 and Q2) |
| `policy/PolicyBulkhead` | in-process semaphore (4 permits, 1.5 s wait), shared by policy and path | shared | one instance in each pod, with different meaning (§5.3) |
| `policy/OfflineAnalysisService` | persists offline results | OPERATIONAL | **stays** in ui2-service |
| `policy/OfflineMultipartConfiguration` | the global `multipartResolver`; also exempts packet-analysis and sanitizer | shared | **stays** in ui2-service |
| `policy/PolicyChangesService` | change log read; used by the Operations change feed | shared read | **stays** in ui2-service (light, and SQL-bound) |
| `policy/PolicyCollectionService` | sources, status, `collect()` enqueue | OPERATIONAL | **stays** |
| `policy/PolicyPrivacy`, `PolicyFirewallNames`, `PolicySources`, `PolicyResponse` | masking rules, name index, marker | shared | masking library (§3); both roles use it |
| `pathanalysis/engine/*` | pure (fenced: no jOOQ, no repositories) | ANALYSIS | `analysis.path.engine` |
| `pathanalysis/topology/TopologyBuilder`, `TopologyGraph`, `TopologyInput`, `Ipv4Prefix`, `NextHopResolver`, `TopologyView` | pure graph build and view | ANALYSIS | `analysis.path.topology` |
| `pathanalysis/topology/TopologyService` | graph holder, rebuild, overrides | mixed | split: graph holder and rebuild move; override create and revoke **stay** (§1.3) |
| `pathanalysis/topology/TopologyAutoRebuild`, `JooqTopologyRepository` | read-triggered rebuild; writes `topology_snapshot` and `topology_conflict` | ANALYSIS (derived writes) | `analysis.path.topology` |
| `pathanalysis/query/*` | `PathQueryService`, `PathPolicyBinder`, `StoredPathPolicyCatalog`, `PathPseudonymMap` and `PathPseudonymResolver` | ANALYSIS | `analysis.path.query` |
| `pathanalysis/validation/*` | `PathValidationService` (job admission, writes) and `Planner` | OPERATIONAL | **stays** in ui2-service and calls the path port for the path result |
| `api/PolicyController`, `PolicyLookupController`, `OfflineAnalysisController`, `TopologyController`, `PathAnalysisController`, `PathValidationController` | public controllers | gateway | **stay**, rewritten as adapters over ports (§1.2) |

### 1.2 Ports, so one code base runs both ways

1. Introduce `PolicyAnalysisPort` and `PathAnalysisPort` (in ui2-service, package `service.analysis.port`). Each has
   two implementations:
   - `Local*Analysis` wraps today's in-process beans, so behaviour is unchanged.
   - `Remote*AnalysisClient` calls the internal API (§2).
2. The six controllers and the three outside callers depend only on the ports:
   - `search/GlobalSearchService` uses only `searchAddresses`. That method is SQL over stored snapshots, with no parse
     cache. **Split it out** as `PolicyAddressSearch` and keep it in ui2-service, so global search does not cross pods.
   - `packetanalysis/PacketAnalysisCorrelationService` calls `catalog()` and then `find()` on **every** policy. Remote,
     that would be N round trips and the same cache thrash in another pod. Add one coarse port operation,
     `correlate(request)`, executed inside `ui2-analysis`.
   - `pathanalysis/query/StoredPathPolicyCatalog` moves with path into the same pod, so it stays in-process.
3. Phase 1 keeps everything in the `ui2/service` Gradle module and fences the packages with ArchUnit (§7.1). Splitting
   out a Gradle module (`ui2/analysis-core`) is a later, optional step. It does not block the container split, and it
   would enlarge the first diff.

### 1.3 What the analysis pod may write

The principle: **every user-attributed write stays in ui2-service; `ui2-analysis` writes only derived data.**

| Write | Today | After |
|---|---|---|
| `authz_decisions` (policy lookup, path query and validate, E4 decisions) | controllers and `GateChain` in ui2-service | unchanged |
| `offline_analysis` result rows | `OfflineAnalysisService` | unchanged. ui2-service streams the upload to `ui2-analysis`, receives the result, and stores it under its own audited boundary |
| `topology_link_override` create and revoke (audited, SECURITY_ADMIN) | `TopologyService` | **stays** in ui2-service (`TopologyOverrideService`). The graph reads overrides at its next build, as today (`PENDING_REBUILD`) |
| `topology_snapshot`, `topology_conflict`, prune of the newest 20 | `TopologyService.rebuild` (plain transaction) | `ui2-analysis` only, with a single-writer guard (§6.4) |
| `path_validation*` rows and `path_validation` job admission | `PathValidationService` | unchanged (ui2-service) |

A manual `POST /api/v2/topology/rebuild` is authorized and recorded in `authz_decisions` by ui2-service, then
forwarded. `ui2-analysis` performs the rebuild with the signed actor fingerprint (§4.2) set as `app.actor_fingerprint`,
so audit attribution is unchanged.

## 2. Inter-container API: options and recommendation

| | A — Ingress path split | B — ui2-service as gateway (**recommended**) | C — DB job queue (Packet Analysis pattern) |
|---|---|---|---|
| Browser reaches | `ui2-analysis` directly for `/api/v2/policies/**`, `/topology/**`, `/path-analysis/**` | ui2-service only | ui2-service only |
| Session, CSRF, RBAC, `authz_decisions` | **duplicated** in a second pod (`sessions` table reads, CSRF, `RbacEvaluator`, role-binding key) | one place (unchanged) | one place |
| Masking authority | **two** advices: violates §3 | one decision point and one final gate | one |
| Service-to-service auth | none needed, but two public surfaces | needed (§4.2) | none: the DB is the channel |
| Load isolation | full | full for CPU and heap. ui2-service still buffers response bodies (§5.2) | full |
| Interactive latency | best | +1 in-cluster hop and one JSON re-parse | poor: claim-loop and poll round trip for every page view |
| Failure isolation | analysis down means those pages fail | analysis down means `503 ANALYSIS_UNAVAILABLE` on those routes, and the rest of the API is unaffected | queued work waits |
| Fits | — | interactive reads, lookups, path queries | heavy batch: offline CSV, packet correlation |

**Recommendation: B for every moved route.**

- **Why not A:** it creates a second authentication, RBAC and masking authority, which the PO decision names as a cost
  and §3 forbids.
- **Why not C as the default:** most moved routes are interactive (rule pages, object pages, lookups at 30 per minute,
  path queries at 30 per minute).
- **Where C may come later:** for offline analysis and packet correlation, once §11 M4 shows that interactive B calls
  are fast enough and the batch ones are not.

### 2.1 Internal API shape

- **Transport and port:** plain HTTP/1.1 JSON on container port **8087**, ClusterIP Service `ui2-analysis`, path prefix
  `/internal/analysis/v1/`. No ingress rule, and no route on 8080 of any pod.
- **Granularity:** one operation per port method, not a generic proxy. ui2-service maps a public route to a typed
  call. No URL, query string or header is passed through verbatim (the same principle as "no command originates in
  the browser").
  - `POST /internal/analysis/v1/policy/{policyId}/page` with body `{page, q, hitFilter, days, sort, order}`.
  - `.../hygiene`, `.../hygiene-csv`, `.../rule`, `.../objects`, `.../usage`, `.../duplicates`, `.../installation`,
    `.../unused|gateways|hits`, `.../lookup`, `.../offline` (streamed CSV), `/policy/correlate`.
  - `/path/topology` (GET view), `/path/topology/rebuild`, `/path/query`.
  - `/path/result-for-validation`: the A6 submit path asks for the computed path, then plans and admits in ui2-service.
- **Response:** `{ "view": "MASKED"|"RAW", "body": <the same JSON the controller returns today> }`. ui2-service checks
  that `view` equals what it asked for (§3.3).
- **Versioning:**
  - `v1` is additive-only.
  - A removed or renamed field needs `v2`, and both pods serve both versions for one release.
  - Golden contract tests guard the N−1 pair (§7.4).
- **Errors:** a typed status set that maps 1:1 to today's public refusals (`POLICY_BUSY` 503 + `Retry-After`,
  `TOPOLOGY_REBUILD_IN_PROGRESS` 409, `PSEUDONYM_REFUSED` 422, `TOPOLOGY_INPUT_TOO_LARGE` 422, 404s). Transport
  failure, timeout or a non-2xx outside that set becomes `503 ANALYSIS_UNAVAILABLE` with `Retry-After`.
- **No silent fallback to local.** A fallback would bring the isolated load back into ui2-service exactly when the
  analysis pod is struggling, and it would hide the failure. Rollback is the flag (§8).

## 3. A single masking authority

### 3.1 Why "ui2-service masks the result" alone is not enough

Today masking happens inside the analysis computation, not only at the response edge:

- `PolicyQueryService.searchable` masks **before** matching and sorting (`PolicyQueryService.java:186-190, 301-307,
  411-417, 784-787, 811`). An aiview search matches pseudonyms, and sort order follows the masked value.
- `PolicyDomainIndex` caches a masked view (`:189-192`).
- `PolicyLookupController.searchable` masks the whole snapshot before evaluating (`:72-89`).
- Path queries from aiview arrive with pseudonyms. `PathPseudonymMap` resolves them back to real addresses and
  interfaces over the held graph (`PathQueryService.java:116-139`).

If `ui2-analysis` computed over raw data and only ui2-service masked, aiview search and sort would change meaning.
Sorting by raw names would also leak raw ordering to a masked viewer. Masking therefore has to be *computed* inside
`ui2-analysis`, while the *authority* stays single.

### 3.2 The authority, defined

"Single masking authority" means four things, each owned once:

| Element | Owner | Rule |
|---|---|---|
| **Decision** (masked or raw for this request) | ui2-service: `GateChainInterceptor` sets `IS_REPLAY_VIEWER_ATTRIBUTE` | `ui2-analysis` never evaluates roles. It receives the view as a **signed** claim (§4.2); a missing or invalid claim is refused, never defaulted. |
| **Specification** (what is masked and how) | one masking library: `TopologyNamePseudonymizer`, `SubnetPreservingIpMasker`, `PolicyPrivacy`, `HmacKeyProvider`, `PseudonymRegistry` | identical classes in both roles (same image). No masking code is written in `analysis.*`. |
| **State** (key and pseudonym assignments) | key: Secret `ui2-privacy-hmac-key`; collisions: Postgres `pseudonym_registry` (V53); device directory: `devices` | **P0 prerequisite (below):** no masking output may depend on process-local history. |
| **Final enforcement** (what reaches a browser) | ui2-service: `PrivacyMaskingResponseBodyAdvice` | every proxied body passes through it again (§3.3). `ui2-analysis` has no browser-facing surface. |

**P0, the masking-determinism prerequisite.** Masking output depends on process-local history today:

- `SubnetPreservingIpMasker` keeps `registeredSubnets` (an LPM table) and a first-wins `cache`
  (`SubnetPreservingIpMasker.java:26-27`). The advice fills them from whatever responses the same JVM has masked
  (`PrivacyMaskingResponseBodyAdvice.java:108-142`). The same bare host can therefore mask differently in two JVMs, or
  in one JVM before and after a restart. Only `maskNetworkWithoutSideEffects` is order-independent.
- `TopologyNamePseudonymizer`'s free-text replacement uses an in-memory `rawNameToPseudonym` map that only earlier mask
  calls fill (`:86-89, 393-411`). A fresh JVM can leave a raw name in free text that a warmed JVM would mask.
- The policy code also has convenience constructors with a zero key (`new SubnetPreservingIpMasker(new byte[32])`,
  `PolicyQueryService.java:24,28`). Spring does not use them, but a remote role wired wrongly could.

Before any `remote` mode, P0 must make masking a pure function of (key, database state, input):

1. **Subnet registrations** derive from stored data, such as the subnets of the latest inventory runs or the held
   topology snapshot, refreshed on a version check like the snapshot cache. They no longer come from response history.
2. **Free-text name replacement** draws its candidates from the device directory and `pseudonym_registry`, not from an
   in-memory map of what has been masked so far.
3. The zero-key constructors are removed from production code, or fenced to test scope.

P0 is a correctness fix that ui2-service needs anyway: restart-dependent masked forms are a latent aiview defect. Its
real-environment size is unknown (§11 M9). P0 changes masking semantics, so it needs its own contract and the
masking test set (§7.2) before it lands.

### 3.3 Enforcement at the ui2-service edge (fail closed)

1. **Body shape.** The remote client deserializes `body` into `Map`/`List`, which the advice walks. It never returns a
   `JsonNode`, `byte[]`, `String` of JSON or `StreamingResponseBody`: the advice passes any type other than Map, List,
   String, `JobEvent` or `JobPage` through unchanged (`PrivacyMaskingResponseBodyAdvice.java:202`), and a streaming body
   never reaches a ResponseBodyAdvice at all. CSV (`hygiene.csv`) stays a `String`, which the advice masks via
   `maskText`, exactly as today.
2. **View echo check.** If ui2-service asked for `MASKED` and the response says otherwise, or has no `view`, the
   request fails with 500 before any byte is written. This follows the pattern of the self-masking types that throw
   `IllegalStateException` today (`:62-94`).
3. **Double masking is the norm, not a hazard.** Today a `PolicyResponse` is masked in the service code and then again
   by the advice. Pseudonyms do not match the IP or name patterns, so the second pass is idempotent. `AnalysisParityTest`
   (§7.2) proves this holds for every moved route.
4. **Fence.** An ArchUnit rule says that a controller method serving a moved route may return only
   `ResponseEntity<Map|List|String>` or a `PolicyResponse`. A new remote-client return type cannot silently fail open.

### 3.4 Pseudonym resolution for path queries

`PathPseudonymResolver` moves with the graph into `ui2-analysis`. That is safe only after P0, because its resolution
depends on the same masker state. Before P0 it would resolve against a different `registeredSubnets` than the one that
rendered the pseudonyms the user typed. `PathPseudonymAdviceConsistencyTest` must be extended to run the resolver and
the advice in two separate masker instances (two JVMs, simulated) and still agree.

## 4. RBAC and audit

### 4.1 Public surface: unchanged

- These stay exactly as they are, with the same URLs, action ids and role tokens:
  - the `SecurityWebMvcConfig.ACTION_ID_BY_ROUTE` entries for `/api/v2/policies/**`, `/api/v2/topology/**` and
    `/api/v2/path-analysis/**`;
  - the `ActionRegistry` descriptors (`topology_read`, `topology_override_write`, `topology_rebuild`,
    `path_analysis_read`, `path_analysis_validate`, `POLICY_READ`, `POLICY_OFFLINE_WRITE`).
- The in-controller checks stay in ui2-service:
  - replay viewers are refused topology writes (`WRONG_ROLE`);
  - offline write requires `SECURITY_ADMIN` or `ONBOARDING_ADMIN` and is not allowed for a replay viewer;
  - `PathAnalysisController` checks `policy_read` before showing rule identity;
  - per-actor rate limits stay: lookup 30/min, path query 30/min, validate 6/min.
- These stay in ui2-service so that there is one counter and one decision per actor.

### 4.2 Internal surface: authenticated and fenced

No service-to-service authentication exists in the repository today:

- `ui2-configuration` and `ui2-compliance` are unauthenticated JDK `HttpServer`s.
- They are acceptable only because they hold no credentials and no data.
- `ui2-analysis` reads every stored policy and topology, so it needs more.

**Layer 1 — NetworkPolicy.**
- *Ingress:* `ui2-analysis` accepts traffic only from pods labelled `app.kubernetes.io/component: service` on TCP 8087.
- *Egress:* DNS plus the database on 5432 only, like `58-packet-analysis-deployment.yaml`. There is no device egress:
  analysis contacts no device.
- **Whether the HOST-A CNI enforces NetworkPolicy is UNKNOWN (§11 M6)**, so this layer is not relied on alone.

**Layer 2 — signed request context.** ui2-service signs every internal call with HMAC-SHA256 under a new key, Secret
`ui2-analysis-internal-key`, mounted only in ui2-service and `ui2-analysis`. The signed string covers:
- method, path and the SHA-256 of the body;
- a timestamp (±30 s window) and a request id (single-use within the window, kept in a bounded in-memory set);
- `actor_fingerprint` (for audit attribution and the rebuild actor only, never for authorization);
- `view` (`MASKED`|`RAW`).

`ui2-analysis` rejects missing, stale, replayed or mismatched requests with 401 and logs a counter, not the request.

Why not mTLS: the repository has no certificate issuance or rotation path for pods. A shared HMAC key follows an
existing pattern (the e2e machine-token SHA-256 compare, the privacy HMAC Secret) and can be rotated with a two-key
window. mTLS can replace it later without changing the API.

**What `ui2-analysis` does not load:**
- `SecurityWebMvcConfig`, `GateChainInterceptor`, `RbacEvaluator` and the `sessions` table reads;
- the role-binding key, the credential-store key and the DB migrate credentials;
- Flyway: ui2-service stays the only migrator.

`ui2-analysis` has its own `InternalRequestVerifier` as its only interceptor. An unmapped internal route returns 404.
`/healthz` is served unauthenticated and returns liveness only, never data.

### 4.3 Audit

- **`authz_decisions`:** written only by ui2-service, unchanged. A path or lookup result whose decision row cannot be
  written still gets 503, before the remote call is made.
- **`audit_log`:** is written by triggers only, and the actor comes from `app.actor_fingerprint`.
  - The topology rebuild in `ui2-analysis` sets `system:topology-rebuild` for automatic runs (as today), or the signed
    actor for a manual run.
  - Overrides stay in ui2-service (§1.3).
- **Correlation:** ui2-service generates `X-Nexus-Request-Id` and forwards it. Both pods log it next to the action id.
  No request body and no masked or raw value is logged.
- **Internal auth failures:** a counter plus the source pod label, written to the pod log. They are not written to
  `audit_log`, because they are not user actions; a burst is an operational alarm (§10 R5).

### 4.4 Secrets each pod holds after the split

| Secret | ui2-service | ui2-analysis |
|---|---|---|
| `ui2-db` app user | yes | yes (Q3: a narrower DB role) |
| `ui2-db` migrate user | yes | **no** |
| `ui2-privacy-hmac-key` | yes | yes, read-only (needed by §3.2) |
| `ui2-analysis-internal-key` (new) | yes | yes |
| `ui2-artefact-store-key` + `ui2-artefact-store` PVC (read-only) | yes | **only if `LocalFirewallPolicyService` moves (Q2)** |
| `ui2-credential-store-key`, `ui2-role-binding-key`, `ui2-hostname-fingerprint-key` | yes | **no** |

## 5. Memory and concurrency limits

### 5.1 What is known, and what is not

**Known:**
- `kubectl top` on 2026-11-05: ui2-service 5.3 GiB, ui2-policy 6.5 GiB, node at 27 % memory and 14 % CPU.
- ui2-service runs with `-XX:MaxRAMPercentage=75.0` under an 8 GiB limit, so its maximum heap is about 6 GiB. No
  `-Xmx` is set.
- **RSS is not the live set.** A JVM keeps heap it once grew into, so 5.3 GiB is a high-water mark, not "what the
  policy cache holds".
- The parse cache is bounded by *weight*, not bytes: `ui2.policy.snapshot-cache.max-weight`, default 200,000 rules plus
  objects. No `ui2.policy.*` property is set anywhere, so code defaults apply.
- Other bounds: hygiene cache 4 × 30 s; domain cache 32; canonical views 64 (weak); local-firewall cache weight
  100,000 for 60 s; topology `MAX_DEVICES` 2,000 and `MAX_INPUT_ROWS` 2,000,000; pseudonym map 200k networks and 100k
  registered.

**UNKNOWN until §11 M1–M3:**
- bytes per weight unit;
- how much of ui2-service's live heap is analysis;
- the largest graph;
- the largest response.

**HOST-A budget:** 16 cores and 60 GB (per `58-packet-analysis-deployment.yaml`). The committed memory limits already
add up to 60 GiB: service 8, worker 8, policy 8, database 6, packet-analysis 24, configuration 2, compliance 4. Limits
can overcommit and requests are small, so scheduling is not the problem. The worst-case simultaneous peak is.

### 5.2 Proposed starting values (to be replaced by measurements)

| Setting | ui2-analysis | ui2-service after cutover |
|---|---|---|
| requests | cpu 250m, memory 1Gi | unchanged until M1 |
| limits | memory **6Gi**, cpu `"4"` | unchanged (8Gi) until §8 phase 6 |
| JVM | `-Xms512m -Xmx4g -XX:MaxMetaspaceSize=384m -XX:MaxDirectMemorySize=512m -Dui2.db.pool.maximum-pool-size=6` | — |
| priority | `ui2-low` (−100, never preempts) | `system-cluster-critical` (unchanged) |
| parse cache | `ui2.policy.snapshot-cache.max-weight` set explicitly in the manifest, from M2 | cache unused while mode is `remote` |

- **Explicit `-Xmx`, not a percentage:** heap, metaspace and direct memory must have a fixed sum below the limit, so
  that an analysis spike becomes a 503, not an OOM kill. This follows the packet-analysis memory plan.
- **Why `ui2-low`:** under node pressure the analysis pod should be evicted before the operational API. Failure
  isolation means the API survives the analysis pod, never the reverse.
- **Body buffering in ui2-service:** option B makes ui2-service hold each proxied body as a `Map` while the advice
  walks it. Concurrent big bodies therefore still cost ui2-service heap.
  - Bound: a per-route maximum response size enforced by the remote client. Past it, return
    `ANALYSIS_RESPONSE_TOO_LARGE` 502 and do not buffer.
  - Bound: a gateway concurrency limit (§5.3).
  - Both values come from M5.

### 5.3 Concurrency

- **ui2-analysis:** `PolicyBulkhead` keeps its meaning: 4 heavy permits and a 1.5 s wait, shared by policy and path
  (as today), plus the hygiene semaphore of 2. These permits now protect only the analysis pod.
- **ui2-service gateway:** a new outbound limiter, `ui2.analysis.client.max-in-flight` (start: 8, set from M4), with a
  1 s connect timeout and per-operation read timeouts:
  - pages and lookups 30 s;
  - CSV and hygiene 120 s;
  - offline upload 15 min, matching the upload size limits.

  When it is full, it answers `503 ANALYSIS_BUSY` with `Retry-After: 5`. This limiter is what turns load isolation
  into a property: a slow analysis pod can occupy at most 8 of ui2-service's request threads.
- **Offline analysis:** keeps its single-upload semaphore in ui2-service (429 when busy). The stream goes through
  without spooling (`OfflineMultipartConfiguration` stays). It remains bounded at 100 MB, 2,000,000 rows and 256
  columns.

## 6. Deploy, drain and rollback

### 6.1 Manifest `deploy/ui2/59-analysis-deployment.yaml` (proposed)

- **Workload:** a `ServiceAccount` with `automountServiceAccountToken: false`, and a `Deployment` `ui2-analysis` with
  label `app.kubernetes.io/component: analysis`, `replicas: 1`, `strategy: Recreate` and `args: ["analysis"]`. The
  same image digest as the service.
- **Security context:** the same as every other pod (non-root, read-only root filesystem, drop ALL, RuntimeDefault
  seccomp), with `tmp` and `home` emptyDirs.
- **Probes:** a startup probe, plus readiness and liveness on `/healthz:8087`. Readiness becomes true only after the
  internal key and the HMAC key are loaded and the DB is reachable.
- **Service and policy:** ClusterIP Service `ui2-analysis:8087`, and the NetworkPolicy of §4.2.
- **Grace:** `terminationGracePeriodSeconds: 120` with `server.shutdown=graceful` and a 90 s shutdown phase.
  In-flight requests finish or are cut. A cut request has no partial effect: `ui2-analysis` performs no
  user-attributed write, and the topology rebuild is one transaction.
- **Launcher:** `Ui2Launcher` gains `"analysis" → AnalysisApplication`. That is a Spring context that loads only
  analysis configuration: no Flyway, no `MigrationStartupRunner`, no `SchedulingConfiguration`, no claim loop and no
  identity seeding runners.

### 6.2 Why there is no drain barrier, and why the deadlock class cannot arise

The 2026-11-04 deadlock (commit `2c7a08a`) is an instance of a general class: **a barrier condition whose only release
is the old pod's exit, while the old pod's exit waits for the barrier.** There, a QUARANTINED `endpoint_admission` was
released only by the OWNER_GONE sweep after the pod stopped.

`ui2-analysis` is designed to have no barrier state at all:

| Barrier input in `module_deploy.barrier_conditions` | ui2-analysis |
|---|---|
| `module_runtime_control` row and drain ack | none (it is not a job module) |
| `jobs` CLAIMED/EXECUTING | it claims no job type |
| `endpoint_admission` LEASED/QUARANTINED | it contacts no device and admits nothing |
| `runtime_task_lease` | it holds none (§6.4 uses a non-blocking advisory lock instead) |

`module_deploy.py` therefore treats it like `configuration` and `compliance`:
- `TARGETS["analysis"] = "analysis"`;
- **not** in `ROLES`, `DEDICATED` or `NEVER_GENERAL`;
- added to `SYNCED_MANIFESTS` (the committed manifest is applied with the candidate image after the diff, keeping the
  live replica count);
- added to `CONCURRENT_SAFE`.

The SIGTERM grace bounds its "drain": HTTP requests finish within the grace window (MODULE_POD_RUNTIME_CONTRACT §3:
"HTTP owners drain requests/background work").

**Fence:** a test fails if `analysis` ever enters `ROLES`, or if any class reachable from `AnalysisApplication`
depends on `jobs..`, `persistence.runtime..` (`ModuleRuntimeRepository`, `EndpointAdmissionRepository`) or
`JooqJobLeaseDao` (§7.1, §7.5). Adding barrier state to this pod later means a contract change, not a quiet edit.

### 6.3 Order, skew and rollback

- **Forward order:** `analysis` before `service` in `hosta_deploy.sh`'s default `TARGETS`. The internal API is
  additive (§2.1), so the new analysis pod serves the old service. The new service is then released, still with
  `mode=local`. The mode flips in a separate, later release (§8).
- **First release:** create the Deployment from the committed manifest. As with `ensure_packet_analysis` when
  `old is None`, there is nothing to drain.
- **Release snapshot:** `release_snapshot.py` records the `ui2-analysis` digest under `images.ui2`, so
  `rollback_image()` can resolve it.
- **Rollback cases:**
  1. *The cutover misbehaves:* set the mode env back to `local` in `50-service-deployment.yaml` and roll out the
     service. There is no data to restore. The topology graph rebuilds in ui2-service on first read, as today after a
     restart.
  2. *A bad analysis image:* `module_deploy.py --rollback --targets analysis` restores the recorded digest. There is
     no barrier, so this is a plain image restore. ui2-service answers `503 ANALYSIS_UNAVAILABLE` during the Recreate
     gap.
  3. *A full release rollback:* restore the service digest first. An old service that has never heard of
     `ui2-analysis` simply does not call it. Then restore analysis, or leave it; a stranded `ui2-analysis` is
     harmless because it is idle and has no barrier.
- **Recreate gap:** while the analysis pod restarts, moved routes return 503. The size of the gap is pod start plus,
  for path, the first graph build (M3). RollingUpdate is rejected for phase 1: two overlapping pods would build two
  graphs with different random salts (`TopologyService.java:121-126`), so node ids from one would not resolve in the
  other. That is already true across ui2-service restarts today, and `TopologyView.held_is_latest` reports it.

### 6.4 Topology single-writer

- **Today:** rebuild concurrency is JVM-local (`AtomicBoolean` plus `ReentrantLock.tryLock`), with no DB guard. Two
  running copies (ui2-service in `local` mode next to `ui2-analysis`, or two replicas) would both rebuild, both insert
  snapshot rows, and race the prune.
- **Design:**
  1. `ui2.topology.auto-rebuild.enabled` follows `UI2_ANALYSIS_PATH_MODE`. In `local` and `shadow` it is true in
     ui2-service and **false** in `ui2-analysis`. In `remote` the reverse. A startup check refuses to start
     `ui2-analysis` with auto-rebuild on while the mode is not `remote`. The mode is passed to both pods from one
     ConfigMap key so they cannot disagree.
  2. `rebuild` additionally takes `pg_try_advisory_xact_lock(<topology-rebuild key>)` in its write transaction. If it
     does not get the lock, it returns `TOPOLOGY_REBUILD_IN_PROGRESS`. This lock is released by commit, rollback or
     connection death, **never by a deploy step**, so it cannot form a drain-barrier cycle.

## 7. Test changes

### 7.1 Architecture fences (ArchUnit, `ui2/architecture-tests`)

- **`AnalysisRoleFenceTest` (new).** `analysis..` and every class reachable from `AnalysisApplication` must not
  depend on any of:
  - `..ui2.worker..`, `..ui2.jobs..`, `..transport..`, `..capability..`;
  - `..service.security.GateChain*`, `SecurityWebMvcConfig`, `RbacEvaluator`;
  - `PrivacyMaskingResponseBodyAdvice`: the decision stays in the service; the library is allowed;
  - `persistence.runtime..`, `JooqJobLeaseDao`, Flyway.
- **`AnalysisPortFenceTest` (new).** The six controllers and the three outside callers may reach analysis only
  through `service.analysis.port..`. Their moved-route methods return only `ResponseEntity<Map|List|String>` or
  `PolicyResponse` (§3.3 item 4).
- **Existing path fences.** Keep the four tests (`PathAnalysis{Topology,Engine,Query}FenceTest`,
  `PathValidationFenceTest`) and re-point them at the new packages. Add one rule: `analysis.path..` must not call
  `TopologyOverrideService` (overrides stay in ui2-service).

### 7.2 Masking

- **`MaskingDeterminismTest` (new, P0 gate).** Two masker instances share the key and the DB state and receive the
  same inputs in shuffled orders; they must produce byte-identical output. **Expected to fail on today's code**, and
  that is the point of P0.
- **`ProxiedBodyMaskingTest` (new).** The advice masks `Map`/`List` bodies produced by the remote client. A
  `JsonNode`, `byte[]` or streaming body on a moved route fails the build.
- **`AnalysisParityTest` (new).** For every moved route, in both views, `local` and `remote` produce byte-equal
  final responses. Remote is in-process, over a loopback internal server. It reuses `SyntheticPolicies`,
  `PathFixtures`, `api-chain.golden.txt` and the `PolicyHygiene*` fixtures.
- **Extend:** `PathPseudonymAdviceConsistencyTest` (two masker instances, §3.4), `PolicyPrivacyTest`,
  `PrivacyMaskingResponseBodyAdviceTest` (the view-echo mismatch gives 500 before the body is written).

### 7.3 Internal authentication

- **`InternalRequestVerifierTest`.** Each of these returns 401: missing signature, wrong key, timestamp outside
  ±30 s, replayed request id, body-digest mismatch, and `view` altered after signing.
- A `RAW` view is accepted only when signed. A `/healthz` response contains no data.
- Key rotation: requests are accepted under either of two configured keys, and the old key is refused after its
  removal.

### 7.4 Contract

- **Internal API golden schemas** under `ui2/service/src/test/resources/analysis/v1/`.
- **N−1 compatibility test:** the current client against the previous release's server schema, and the reverse.

### 7.5 Route, deploy and manifest

- **Existing MockMvc suites:** `PolicyApiTest`, `PolicyChangesApiTest`, `OfflineAnalysisApiTest`,
  `PathAnalysisRouteTest`, `PathValidationRouteTest` and `TopologySecurityRouteTest` become parameterized over
  `local|remote`, with a stub remote client. Public status codes and refusal bodies are unchanged.
- **`tools/tests/test_ui2_deployment_manifests.py`** for the analysis container:
  - security context, requests and limits;
  - priority class `ui2-low`;
  - the NetworkPolicy (ingress only from the service on 8087; egress only DNS and DB);
  - secrets exactly as in §4.4;
  - no migrate credentials, no role-binding key, no credential-store key.

  Update `test_policy_snapshot_memory_budget_and_postgresql_config_mount` for the new limit. Rename the existing
  "analysis volume" tests to say "packet-analysis volume" so the two meanings of "analysis" do not collide.
- **`tools/tests/test_policy_module_runtime.py` and `test_manifest_sync.py`:** `analysis` is a valid target, absent
  from `ROLES`, in `SYNCED_MANIFESTS` and `CONCURRENT_SAFE`. Its first release creates the deployment without a drain,
  and rollback resolves its digest from the snapshot.
- **`test_release_snapshot.py`:** the snapshot records the analysis image.

### 7.6 Integration and load

- **Postgres integration** (`integration-tests`): two concurrent `rebuild` calls through the advisory lock leave
  exactly one snapshot, and the prune keeps 20.
- **Module e2e:** as aiview, the moved routes answer 200 with `X-Nexus-Masked: true`, and contain no raw-pattern
  values (the existing masked-evidence checker).
- **Load comparison:** p95 of operational endpoints during a heavy hygiene and path run, `local` versus `remote`.
  This is the evidence that the split delivered its purpose (§11 M4).

## 8. Phased migration with a flag

The flags are `UI2_ANALYSIS_POLICY_MODE` and `UI2_ANALYSIS_PATH_MODE` in `ui2-config`. Values are `local` (default),
`shadow` and `remote`. They take effect at restart, matching the repository's other switches. Each phase is its own
release with its own snapshot.

| Phase | Change | Exit criterion |
|---|---|---|
| **0 — Measure and prerequisites** | §11 M1–M11. P0 masking determinism (own contract). Verify the replay-viewer flag for LDAP actors (§10 R10). No deploy change. | M1 shows the analysis share of service heap. P0 merged and green. PO reviews the go/no-go with the numbers. |
| **1 — Ports and packages** | §1.2 refactor, fences, parity test. Both modes `local`. The `analysis` launcher role exists but is not deployed. | Full suite and render harness green. No behaviour change. |
| **2 — Dark pod** | Deploy `ui2-analysis` (auto-rebuild **off**). Both modes `local`. | Pod healthy. Internal auth refuses unsigned calls. NetworkPolicy enforcement measured (M6). |
| **3 — Shadow (policy only)** | `POLICY_MODE=shadow`: ui2-service serves locally and, for a sampled 2 % of policy reads, calls remote asynchronously and compares masked-response digests. Counts only; no values logged. Path is not shadowed: graphs differ per pod by design (§6.3). | 7 days with zero digest mismatches. Remote p95 within budget (M4). |
| **4 — Policy cutover** | `POLICY_MODE=remote`. | 7 days with zero `ANALYSIS_*` 5xx beyond restarts. Operational p95 improved or unchanged. Rollback rehearsed once. |
| **5 — Path cutover** | `PATH_MODE=remote`: auto-rebuild moves (§6.4). A6 validate keeps working through `result-for-validation`. | Path e2e and A6 validate green. One graph writer observed (snapshot rows per hour). |
| **6 — Reclaim** | Remove the local implementations from the service runtime path (the library stays). Lower the ui2-service memory limit to what M1 and post-cutover `top` justify. | ui2-service memory and limit reduced. Node worst-case sum within budget. |

The order of phases is fixed: policy before path. Path depends on the policy cache (`StoredPathPolicyCatalog` →
`PolicyQueryService`), so with path remote and policy local, path would run a second cache in the other pod.

## 9. What does not change

- No public URL, action id, role token, ingress rule or frontend code changes.
- No schema change is needed for the cutover. The advisory-lock key is code. A future narrower DB role (Q3) would
  be a migration under its own contract.
- No device command, job type, gate entry or module ownership row is added or changed. `ui2-policy` (collection) is
  untouched.
- The architecture fence around `pathanalysis` stays, re-pointed. Path can later split into its own container (PO
  decision §2) by repeating §6 with a second flag, because §1.2 already separates the two ports.

## 10. Risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| R1 | **Masking divergence between pods** (process-local subnet and name state) puts a raw value in a masked response, or makes a pseudonym unresolvable | high | P0 before any `remote`; determinism test; parity test; advice re-masks at the edge; view-echo check |
| R2 | **Fail-open body types** on proxied routes (the advice returns unknown types unchanged) | high | Map/List-only client; ArchUnit return-type fence; `ProxiedBodyMaskingTest` |
| R3 | **The memory premise is unproven.** If M1 shows the policy cache is a small share of the service heap, the split saves little memory | medium | Phase 0 gate. The PO decision's principle requires a measured need; load and failure isolation (M4, M10) must then justify it on their own |
| R4 | **Gateway buffering** moves part of the memory cost back into ui2-service | medium | per-route response caps and an in-flight limit from M5; paginated routes stay paginated |
| R5 | **The internal port as a data exfiltration path** if the NetworkPolicy is not enforced | high | signed requests (§4.2) do not depend on the NetworkPolicy; M6 measures enforcement; counter on 401s |
| R6 | **Version skew** between the service and analysis images | medium | additive v1; N−1 contract tests; analysis-first ordering |
| R7 | **Double topology writer** while modes disagree | medium | mode from one ConfigMap key; startup refusal; advisory lock |
| R8 | **Secret spread:** the HMAC key, and possibly the artefact-store key, now in a second pod | medium | §4.4 minimal set; Q2 decides the artefact key |
| R9 | **DB connections:** a new pool of 6 | low | M7 measures `max_connections` headroom |
| R10 | **Pre-existing, outside this scope, unverified at runtime.** `GateChainInterceptor` sets the replay-viewer flag only through `LocalIdentityResolver` when both local resolvers are wired (`GateChainInterceptor.java:141-160`; wired non-null in `RbacConfiguration.java:96-98`). `GateChain.isReplayViewer`, the LDAP-aware path, is then unreachable. An LDAP actor bound to `replay_viewer` would receive **unmasked** responses. | high, if real | flagged for its own investigation; phase 0 confirms or refutes it. This design does not fix it |
| R11 | **Recreate gap** (503s while the pod restarts and rebuilds the graph) | low | measured by M3/M11; the routes answer honestly with 503 + `Retry-After` |
| R12 | **The path package straddles pods.** A6 validation stays in the service | low | `result-for-validation` port; existing `PathValidationFenceTest` |
| R13 | **Name collision:** "analysis" already means Packet Analysis in tests and comments ("analysis worker", "analysis volume") | low | component label `analysis`; test renames in §7.5; Q1 offers a different name |

## 11. Live measurements still needed (aiview-safe)

Every measurement reports counts, sizes, durations or booleans. No heap dump, hostname, address or rule content
leaves HOST-A, and no heap dump is written at all: it would contain raw policy data.

| # | Question | How (on HOST-A, under the 2026-09-19/24 authorizations) | Feeds |
|---|---|---|---|
| M1 | What holds ui2-service heap? | `kubectl -n ui2 exec deploy/ui2-service -- jcmd 1 GC.heap_info` and `GC.class_histogram` (class names and byte totals only), taken at idle and right after a heavy hygiene and path run. Live set after a full GC versus RSS. **Whether the runtime image ships `jcmd` is UNKNOWN**; if not, enable `-XX:NativeMemoryTracking=summary` on one restart and use `VM.native_memory`. | §5, R3, phase 0 gate |
| M2 | Bytes per cache weight unit; distribution of snapshot weights; how many snapshots exceed 200k and are never cached | a temporary counter log line from `PolicyQueryService` (weights and entry count), plus the M1 histogram share of the snapshot record classes | cache `max-weight`, analysis `-Xmx` |
| M3 | Largest topology graph (devices, input rows) and rebuild duration; pseudonym-map size | `TopologyView` counters; `TIMING`-style log of rebuild duration | Recreate gap, heap |
| M4 | Does analysis load slow the operational API today? | p50 and p95 of `/api/v2/overview`, `/devices` and `/jobs` while a hygiene CSV, a domain object page and a path query run concurrently, versus idle; Tomcat busy threads | the justification for the split; gateway limits |
| M5 | Largest response body per moved route | response-size histogram (bytes only) for one day | §5.2 caps |
| M6 | Does the HOST-A CNI enforce NetworkPolicy? | from a throwaway pod without the service label, `nc -z` to an existing `ingress: []` pod (`ui2-packet-analysis`); a refused connection means enforced | R5 |
| M7 | Postgres `max_connections` and peak use | `select count(*) from pg_stat_activity` at peak; `show max_connections` | R9 |
| M8 | Node allocatable memory and eviction thresholds; current sum of requests and limits | `kubectl describe node` (allocatable and allocated sections only) | §5.1 |
| M9 | Do masked forms already change across a ui2-service restart? | as aiview: capture masked digests of a fixed set of policy pages, restart, recapture; report the mismatch count only | P0 sizing, R1 |
| M10 | ui2-service restart and OOM history | `kubectl -n ui2 get pod -l app.kubernetes.io/component=service -o jsonpath` restart count and last termination reason | failure-isolation justification |
| M11 | Longest in-flight analysis request (offline CSV at the 2M-row limit, largest hygiene CSV) | duration logs | grace period, client timeouts |

### 11.1 Results so far (2026-11-05 evening, HOST-A, counts only)

| # | Result |
|---|---|
| M1 (partial) | `jcmd` is in the image but cannot attach: the target JVM must create its attach socket in `/tmp`, which is read-only (FS-3 of the FROZEN B1-01C contract declares only `/app/tmp` and `HOME` writable; not amended for a measurement). A `SIGQUIT` heap summary works without attach: ui2-service G1 heap committed 1,192 MiB, used 721 MiB (about 300 MiB of it young), metaspace 91 MiB, RSS 1,586 MiB; ui2-policy during a FULL CP collection: committed 1,144 MiB, used 610 MiB. The earlier 5.3 GiB figure was a high-water mark. Still needed: the same after a heavy hygiene and path run, and a class histogram (needs `-XX:+PrintClassHistogram` on one restart, then `SIGQUIT`, no attach). |
| M6 | NetworkPolicy is enforced: from a pod without the e2e label, the service's port 8080 connects and the machine port 8086 (e2e and security-scan only) is refused; the e2e-labelled pod reaches 8086. |
| M7 | Postgres `max_connections` 100; 16 connections in use, 1 active (idle period). |
| M8 | Node allocatable: 16 CPU, about 60.8 GiB memory. Requests: 3.75 CPU (23 %), 9.1 GiB (15 %). Limits: 24 CPU (150 %), 70.2 GiB (115 %): limits are already overcommitted. |
| M10 | ui2-service and ui2-policy: 0 restarts, no OOM termination since their last rollout; ui2-db: 8 restarts (earlier host reboots). |

## 12. Open PO questions

- **Q1 — Name.** Keep `ui2-analysis` (as directed), or use `ui2-policy-analysis` to avoid confusion with Packet
  Analysis (R13)?
- **Q2 — `LocalFirewallPolicyService`.**
  - (a) Move it with the artefact-store key and a read-only mount of the PVC (single-node RWO, as the service does
    today).
  - (b) Keep it in ui2-service and have it hand parsed snapshots to analysis.
  - (c) Have collection persist local-firewall snapshots like Panorama snapshots: a collection-semantics change, out of
    scope here.

  Recommendation: (a) for phase 4, revisited after (c) is costed.
- **Q3 — Narrower DB role** `ui2_analysis` (SELECT on snapshot, inventory, device and registry tables; INSERT and
  DELETE on `topology_snapshot` and `topology_conflict`; INSERT on `pseudonym_registry`). This is a grant migration, so
  it needs a contract. Do it now, or after phase 5?
- **Q4 — Shadow phase.** Is a 2 % sampled double computation in phase 3 acceptable on the shared node, or should
  phase 3 be skipped in favour of the offline parity test alone?
- **Q5 — Process.** Does this draft stand in for the Argon design step in the decision record, or go to Argon as
  input?
