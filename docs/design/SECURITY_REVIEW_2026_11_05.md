# Security and correctness review — 2026-11-05

Status: DRAFT — review evidence (`AGENTS.md` authority level 6). Not a contract; it authorizes nothing beyond the
one HIGH fix described in §2, which was made inside already-frozen masking law.

Movement: `READ_ONLY_AUDIT` + one `IMPLEMENTATION` fix. Brief: `docs/cloud-briefs/security-review.md`.
No live system was reachable; every finding comes from reading the source and from automated tests. Nothing here is
real-environment evidence.

## 1. Scope

`git diff ed068bbb..origin/cloud-base/1105`, which is the UI night batch 4914df9: Collect menu, system-log collect
start, server-side sort allow-lists, job target labels, stuck-loader fixes, path diagram, and the independent
system-log scheduler. It also covers these recent `main` commits before `ed068bbb`:

- 86e3a9c and 72768e5: `PathPseudonymMap`
- 43fbdc6: `CheckPointPolicyMapper` / `CpInventoryObjects`
- 15e75f8: V189 backup size anomaly alerts
- 2c7a08a: `module_deploy` drain barrier

Lenses used: SQL injection, RBAC gaps, aiview masking leaks, raw-evidence retention, races, fail-open logic, resource
leaks and test gaps.

Severity scale:
- **CRITICAL**: exploitable now, with a severe effect.
- **HIGH**: a confirmed masking or security leak, or unsafe fail-open behaviour.
- **MEDIUM**: a real defect with a bounded effect, or a plausible safety gap.
- **LOW**: hardening or correctness at the edges.
- **INFO**: noted, no action needed.

Findings marked *PLAUSIBLE* were read, but their failing state could not be built in tests.

## 2. Summary

| # | Sev | Area | Finding | Status |
|---|-----|------|---------|--------|
| H-1 | HIGH | Job log CSV export | aiview CSV masked as text only; new target labels leak raw | **FIXED** (this branch) |
| M-1 | MEDIUM (PLAUSIBLE) | module_deploy drain barrier | `CANCELLED` quarantine exempt even if a write crossed its mutation boundary | **FIXED** (`cloud/secfix-drain-health-1105`) |
| M-2 | MEDIUM | Job log sort / search | `sort=device` and `q` act on raw hostnames for aiview (ordering / substring oracle) | **FIXED** (§9) |
| M-3 | MEDIUM | System health | unreadable sar/cpwd artefact cached for good; cpwd unreadable counts as 0 restarts | **FIXED** (`cloud/secfix-drain-health-1105`) |
| M-4 | MEDIUM (PLAUSIBLE) | PathPseudonymMap | a step-1 "shown" hit skips the ambiguity check against other hosts | **FIXED** (§9) |
| M-5 | MEDIUM | Job log query | per-row lateral target joins run before sort/limit on non-indexed sorts and on export | **FIXED** (§9) |
| M-6 | MEDIUM | CpInventoryObjects | customer-domain / Global LRU thrash reloads whole domains under the instance lock | **FIXED** (`cloud/secfix-cache-ui-1105`) |
| M-7 | MEDIUM | Path diagram (UI) | neighbours from the current topology drawn on a path from a different snapshot | **FIXED** (`cloud/secfix-cache-ui-1105`) |
| M-8 | MEDIUM | System health (UI) | "scheduled computation is off" shown from a poll count, ignoring `scheduling_enabled` | **FIXED** (`cloud/secfix-cache-ui-1105`) |
| L-1..L-12 | LOW | various | see §4 | open |

There are no CRITICAL findings:
- **SQL injection:** none. Every new `sort`/`order` reaches SQL only through a closed `Map` of constant expressions,
  or through an in-memory comparator switch. Every other value is a bind parameter.
- **RBAC:** every new or changed route maps to an `ActionRegistry` action in `SecurityWebMvcConfig`. Nothing relies on
  the UI hiding a control.
- **Browser → device:** the browser still sends only typed intent (`device_id`, enum `sort`/`order`). No command,
  argv, path or address originates in the browser.
- **XSS:** no new XSS sink.

## 3. Findings: HIGH

### H-1 — aiview job-log CSV export leaked raw target labels — FIXED

- **Where:**
  - before the fix: `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/JobLogController.java:99-103`;
  - fixed: `JobLogController.java:102-115`;
  - `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/audit/JobLogQueryService.java:354-371`.
- **Background:** the night batch widened `device_name` from `devices.observed_hostname` to a resolved *target label*
  (`JobLogQueryService.SELECT`/`TARGET_JOINS`, `toEvent`). The label can now be any of:
  - a discovery-candidate display name;
  - the first endpoint address;
  - a cluster reference or parent-candidate name;
  - a `VSID n` failover unit;
  - a policy container/domain name;
  - a policy source name, falling back to the discovery run's management address;
  - a packet-analysis case title.

  The JSON page masks each `JobEvent` record through `PrivacyMaskingResponseBodyAdvice.maskJobTargetName`. That path is
  correct.
- **The defect:** the CSV export rendered raw rows first, then masked the whole CSV as *text*
  (`masking.maskObject(csv)`). Text masking (`TopologyNamePseudonymizer.maskText`) replaces only names already in this
  JVM's in-memory `rawNameToPseudonym` map. The IP masker covers addresses, but no name is covered unless some earlier
  masked response in the same process happened to register it.
- **Scenario:**
  1. The service pod restarts.
  2. aiview opens Operations > Jobs and presses *Export CSV* without first loading the JSON page.
  3. Every new label kind — cluster name, domain/container name, policy source name, packet case title, candidate
     display name — reaches aiview in plain text.

  This breaks the mandatory AIView masking law. Even after a warm-up, any label not yet registered leaks. The defect
  existed before for hostnames; the night batch made it much wider.
- **Fix:** for a replay viewer, the controller fetches the rows (`JobLogQueryService.exportRows`). It masks each one
  through the same record path as the JSON page (`maskObject(JobEvent)` → `maskJobTargetName` plus the terminal-reason
  masking), renders the masked rows (`toCsv`, now public), and then text-masks the result once more as defence in
  depth. The unmasked path is unchanged.
- **Tests:** `JobLogControllerTest`
  - `replayViewerCsvExportMasksEveryTargetLabelEvenOnesTextMaskingNeverSaw` uses a real advice with a fresh
    pseudonymizer. It covers cluster, policy_container, policy_source, packet_analysis_case and device labels, plus an
    IP in the terminal reason. It asserts that no raw value appears in the CSV and that `CLS-`/`DOM-` pseudonyms do.
    With the record-masking step removed, this test fails; I checked that before restoring the fix.
  - `nonMaskedCsvExportKeepsTheUnmaskedRendering` checks the non-aiview path.

## 4. Findings: MEDIUM

### M-1 — drain barrier exempts a `CANCELLED` job's quarantine even after a mutation (PLAUSIBLE)

- **Where:** `tools/delivery/module_deploy.py:123-127` (2c7a08a), together with
  `ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/executor/JobReconciler.java:37-40`.
- **What changed:** quarantines whose job is `COMPLETED`/`FAILED`/`CANCELLED` no longer hold the barrier. This part
  is safe: a null `job_id`, a missing job, `OUTCOME_UNKNOWN` and every other state still hold it.
- **The gap:** the reconciler's first branch moves an `EXECUTING` job with a cancel request and an expired lease to
  `CANCELLED` without checking `mutation_boundary_crossed`.
- **Scenario:**
  1. A CLASS 1 recovery write crosses its boundary.
  2. The operator requests cancel.
  3. The worker thread stalls: its lease expires while the pod heartbeat stays fresh.
  4. The job becomes `CANCELLED` and its admission becomes `QUARANTINED`.
  5. The barrier now holds, and the deploy stops the pod in the middle of the device session.

  Before 2c7a08a the quarantine held the deploy and reported it.
- **Fix:** exempt only when no attempt of that job crossed its mutation boundary without a recorded outcome:
  `... and not exists(select 1 from job_step_attempt a where a.job_id=t.job_id and a.mutation_boundary_crossed and
  a.outcome is null)`. Alternatively, limit the exemption to read-only `purpose_class` values. Check the column names
  against the schema first.
- **Tests:** `tools/tests/test_policy_module_runtime.py:244` asserts SQL substrings only. Add a database-backed case for
  each holding state, including the case of a job cancelled after it crossed the boundary.
- **Resolution (`cloud/secfix-drain-health-1105`), fail-closed:** `COMPLETED`/`FAILED` still release. `CANCELLED`
  releases only when no `job_step_attempt` of that job, in any lease epoch, has `mutation_boundary_crossed` or a
  non-`read` `action_class` (`module_deploy.ENDED_WITHOUT_WRITE_SQL`). Write-class attempts are counted even when not
  crossed, because the backup path records `recovery-write` without ever flipping the flag. The proof rests on every
  attempt insert being fenced to an owner-valid `EXECUTING` job (`JooqJobStepAttemptDao.insertPreContact`), so no new
  attempt can appear after `CANCELLED`. A recorded `outcome` does not release. Tests: real-PostgreSQL cases per state in
  `tools/tests/test_policy_module_runtime.py` (they skip with a reason where no `initdb` exists). They fail against the
  2c7a08a SQL.

### M-2 — job log `sort=device` and `q` evaluate raw hostnames for aiview

- **Where:**
  - `JobLogQueryService.java:288-289`: `SORT_COLUMNS` `device`/`device_name` → `lower(d.observed_hostname)`;
  - `JobLogQueryService.java:410`: free-text `q` matches `d.observed_hostname`; this part was already there.
- **Scenario:** aiview sorts by Target. The pseudonyms come back in the alphabetical order of the *real* hostnames,
  which gives away relative order. Separately, `q=<guess>` returns rows only when a real hostname contains the guessed
  substring: a membership oracle.

  The sort is also wrong for every role. The displayed label is a coalesce of several sources (see H-1), but the sort
  uses `observed_hostname` only.
- **Fix:**
  - For a masked caller, map `device`/`device_name` to `j.target_device_id` (opaque), or reject it.
  - For a masked caller, drop `d.observed_hostname` from the `q` clause.
  - Pass the masked flag from `JobLogController` into `JobQuery`.
- The policy object sort has a similar, smaller version of this: `PolicyQueryService.java:273` `value`/`values` and
  `:330` usage `policy`/`layer` compare raw values even when masked. The same fix applies: use the masked `views`
  projection for a masked caller.

### M-3 — system-health parse failures are cached forever; unreadable cpwd counts as "no restarts"

- **Where:** `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/systemlog/SystemHealthService.java`
  - `:121-122` and `:406-415`: `UNREADABLE_SAR`;
  - `:429-438`: cpwd returns `Summary.EMPTY` when it cannot read a chunk.
- **What changed:** the night batch replaced the `null` return, which `computeIfAbsent` never stores, with a sentinel,
  which it stores. As a result:
  1. A transient store or decrypt failure stays in `sarByFile`/`cpwdByChunk` until the file leaves the window or the
     process restarts.
  2. `UNREADABLE_SAR` has `cpuSamples=-1`, so the `cpuSamples()==0 && !headerSeen()` guard at `:399` no longer turns it
     into `UNREADABLE`. The day is then labelled `PARSED` with no data.
  3. An unreadable cpwd chunk adds 0 restarts. A device whose last cpwd run was `COLLECTED` but whose chunks cannot be
     read is reported `NORMAL`, which is fail-open: absence of evidence shown as a known-good state.
- **Fix:**
  - Return `null` on a read failure so it is not cached, and add a short negative-cache TTL if retry cost is the
    concern.
  - Count unreadable chunks, and report `UNKNOWN / CPWD_UNREADABLE` when any chunk in the window could not be read.
  - Make the sar guard treat `cpuSamples() <= 0`.
- **Resolution (`cloud/secfix-drain-health-1105`):**
  - A failed read (store, decrypt, parse, missing ref) returns `null` and is cached only as a failure with a retry time:
    1 min, doubling per consecutive failure, capped at 1 h (`SystemHealthService.cached`/`retryAfter`). A good read is
    still kept for as long as the file stays in the window.
  - An unreadable sar day stays `UNREADABLE`; with no readable CPU day the fact is `UNKNOWN / SAR_TEXT_UNREADABLE`.
    The sar guard is `<= 0`.
  - Unreadable cpwd chunks are counted. Any such chunk makes restarts `UNKNOWN / CPWD_UNREADABLE`, with no count shown,
    unless the readable chunks already reach the `ATTENTION` threshold, which is a lower bound.
  - Tests: `SystemHealthServiceTest` covers a transient failure and recovery, no re-read inside the wait, unreadable
    cpwd, the lower-bound case and the backoff cap.

### M-4 — PathPseudonymMap: a topology-shown hit skips the cross-host ambiguity check (PLAUSIBLE)

- **Where:** `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/pathanalysis/query/PathPseudonymMap.java:219-226`.
- **What happens:** if the topology showed exactly one raw host for masked value M, the any-prefix step never runs.
- **Scenario:** another raw host, R2, which aiview sees bare elsewhere (a next hop, a policy object or an IP search),
  can also mask to M when the masked network bits collide. Under a /28 attachment only 4 host bits are kept. The query
  then resolves silently to the shown host and computes a path for what may be the wrong host. The D-3 rule ("two raw
  values → `AMBIGUOUS_PSEUDONYM`") is broken across the two steps.

  This is a correctness fault in what is evaluated. It is not a raw-value leak: the oracle checks hold, raw input is
  refused, refusals are uniform, and the audit stores `[REFUSED]`.
- **Fix:** in step 1, also add the bare-`peek` matches from `acceptedUnderAnyPrefix`. Exclude only the `h/len`-under-a-summary
  false positives that 72768e5 fixed, and refuse when the union has more than one value. Add a collision test.
- **Related, LOW:** index construction stops silently at `MAX_NETWORKS`/`MAX_REGISTERED` (`:72-74`, `:166-190`). The
  registered subnets come from a hash keySet, so which ones survive is arbitrary, and the result can drop a competitor
  that would have made a value ambiguous. Keep a `truncated` flag, and refuse step-2 resolutions while it is set.

### M-5 — job-log target joins run for every filtered row on some sorts and on export

- **Where:** `JobLogQueryService.java:199-260` (`TARGET_JOINS`, five `LEFT JOIN LATERAL`) together with `orderByOf`.
- **What happens:** the laterals sit in `FROM`, not in the select list, so Postgres cannot postpone them past
  `Sort`/`Limit`. Several match on a concatenation (`vendor||'|'||owning_domain||'|'||stable_identifier`) that cannot
  use an index.
- **Effect:** with the default `submitted_at` order an index nested loop can stop at the limit. With `sort=device`,
  `result`, `duration` or `outcome`, the database evaluates every lateral for every job matching the filter before it
  sorts. Export does the same for up to `MAX_EXPORT_ROWS`+1 rows. Any JOB_LOG_READ holder can repeat this, so it is a
  cheap way to load the database.
- **Fix:** select the page of `job_id`s first, using only `j`/`d` columns for filtering and sorting, then resolve the
  target labels for that page alone in an outer query. Add an `EXPLAIN` guard test or a row-count bound.

### M-6 — CpInventoryObjects domain cache thrashes between a customer domain and Global

- **Where:**
  - `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/policy/CpInventoryObjects.java:83-88` and `:107-122`;
  - `CheckPointPolicyCollector.java:284`.
- **What happens:** `find` looks in the customer domain, then falls back to Global, and each lookup moves that domain
  to most-recently-used. When the customer domain plus Global exceed the 400k object bound, every miss that ends in
  Global swaps the two. Each swap is a full decode of a domain from the database and its chunks, done under the
  instance monitor, which serialises all `cp-policy-page` threads.
- **Effect:** a collection can run past its deadline and flood the transcript.
- **Fix:**
  - Pin Global and the current domain so they are never evicted.
  - Cap reloads per run: once the cap is hit, further misses stay UNRESOLVED and the cap is recorded.
  - Load outside the lock.

- **Fixed (`cloud/secfix-cache-ui-1105`):** the Global domain is pinned once read, and a Global read made for a
  customer domain's fallback never evicts that customer domain. Only other domains are dropped by the LRU, so the
  footprint is at most the larger of the bound and Global plus one domain. Test:
  `CpInventoryResolutionTest.aCustomerDomainAndGlobalOverTheBoundDoNotEvictEachOtherAndGlobalStaysPinned` (50
  alternating misses, one read per domain). Not done: the per-run reload cap and loading outside the lock; thrash between
  two large *customer* domains is still possible.

The rest of 43fbdc6 is sound:
- UIDs are matched exactly, without trimming or case folding.
- Rows are scoped by source and domain and must be `RESOLVED`.
- A corrupt chunk or DB failure discards that data instead of guessing.
- SQL is bound.

### M-7 — path diagram mixes topology snapshots (UI)

- **Where:** `ui2/frontend/src/screens/pathanalysis/PathDiagram.tsx:~799` and `:~941-946`.
- **What happens:** "Show neighbours" fetches the *current* `/api/v2/topology` and joins its attachments to the path's
  nodes by `node_id`, without comparing `topology.snapshot_id` with `result.snapshot_id`.
- **Scenario:** if the topology is rebuilt after the path query, neighbour segments from the newer snapshot are drawn
  as if they belonged to the computed path. That conflates evidence from two collection passes. A failed fetch
  silently shows no neighbours.
- **Fix:** draw neighbours only when the snapshot ids match. Otherwise show "topology snapshot differs / unavailable —
  neighbours not shown".
- **Fixed (`cloud/secfix-cache-ui-1105`):** `buildPathDiagramModel` uses the held topology only when its `snapshot_id`
  equals the result's; a missing id on either side counts as a mismatch. This covers neighbours and segment prefixes. A
  mismatch or a failed read shows a note (`diagram-neighbours-withheld`) instead of neighbours. Ingress candidates come
  from the result itself and are still drawn. Tests: `tests/PathDiagram.test.tsx` "neighbours come only from the path's
  own topology snapshot".

### M-8 — System health panel invents "scheduled computation is off" (UI)

- **Where:** `ui2/frontend/src/screens/SystemHealthPanel.tsx:37`, `:160`, and the copy around `:226-291`.
- **What happens:** after `MAX_COMPUTING_POLLS` (4 polls, about 20 s) the panel says the scheduled computation is off
  and stops polling. The server now sends `computation.scheduling_enabled`, but the UI never reads it.
- **Effect:** a first computation that simply takes longer than 20 s is presented as a known state it is not, which
  goes against the UNKNOWN law.
- **Fix:** build the wording from `scheduling_enabled`. When it is true, say "still computing" and keep a slower poll.
- **Fixed (`cloud/secfix-cache-ui-1105`):** the "scheduled computation is off" wording appears only when the API reports
  `scheduling_enabled: false`. Otherwise, past the bounded wait, the panel says "Still computing" and polls every 30 s
  (`SLOW_COMPUTING_POLL_MS`). A failure shows its reason, and a load timeout says only that it timed out. Test:
  `tests/SystemHealthPanel.test.tsx` "past the bounded wait it says 'still computing'…" (with the field true, and with it
  absent).

## 5. Findings: LOW

| # | Where | Finding | Fix |
|---|-------|---------|-----|
| L-1 | `JooqPseudonymRegistry.java:47-80` | `byRaw`/`taken` updated inside the transaction lambda, before commit; a rolled-back claim stays cached and the same pseudonym could later be claimed in the DB for another raw key (one replica today, so the effect is bounded). The `return candidates.get(0)` fallback was already there and returns a pseudonym owned by another raw key. | Update the caches only after `inTransaction` returns; refuse instead of falling back to a taken candidate. |
| L-2 | `TopologyNamePseudonymizer.java:279` | `domainCache.computeIfAbsent` runs a DB transaction inside a `ConcurrentHashMap` bin lock; the cache is unbounded. Case folding matches `rawKey` (already upper-case), so this adds no identity merge. | Check `get` first and `putIfAbsent` after the claim. |
| L-3 | `SystemLogCollectionScheduler.java:58-67`, `:85-95` | With `independent=true` and global scheduling back on, ticks run on two threads; admission dedupes on `syslog-collect:<device>:<slot>`, so it is harmless today. An `Error` thrown in the executor tick silently ends `scheduleAtFixedRate`. | Return early from the `@Scheduled` tick when `independent`; catch `Throwable` in the executor tick. |
| L-4 | `NotificationMailRouter.java:161`, `JooqBackupSizeAnomalyRepository.java:121` | The digest watermark uses `detected_at > from` with `now()` (transaction start); a `size_anomaly` insert that waited on the advisory lock can commit behind the watermark and never be mailed. | Use `clock_timestamp()` after the lock, or an overlap window deduplicated by `deviation_id`. |
| L-5 | `JobLogsPanel.tsx:274` | No request sequence: a slow answer for an old sort or filter can land last (4 s auto-refresh). | Use a per-call `active` flag or a sequence ref. |
| L-6 | `adminApi.ts:172` | The body-read timeout does not abort the fetch; the total wait can reach twice the timeout. | Use one `AbortController` across headers and body. |
| L-7 | `DeviceLogsPanel.tsx:333` | The Collect-now outcome is not tied to the device it was requested for. | Capture `deviceId` and compare before `setCollectMessage`. |
| L-8 | `PathResultView.tsx:58` | `querySelector` builds a selector from a server branch id (a `"` makes it throw) and searches the whole document. | `CSS.escape` and a container ref. |
| L-9 | `JobLogsPanel.tsx:293` | For non-aiview roles, the management IP becomes the target label and a filter option when the hostname is missing (aiview is masked server-side). | PO call: is that wanted? |
| L-10 | `HaReadinessList.tsx:170` | The chip counts `item.status`, but its filter also keeps clusters where any unit has that status. | Filter on `item.status`. |
| L-11 | `CpInventoryObjects.java:67-75` | Global is recognised by display name (`equalsIgnoreCase("Global")`), a presentation identity used as a gate (PLAUSIBLE; whether Check Point reserves the name is UNKNOWN). | Also require the listing's domain type, after checking the vendor docs. |
| L-12 | `PathPseudonymMap.java:220-222` | Each resolution copies the masker's global subnet registry and runs `peek` over it: O(n) per query, and the result can drift within one snapshot. | Snapshot the registry when the map is built. |

## 6. INFO

- **ChunkTextReader** (`changes/ChunkTextReader.java:66-71`): it now prefers `recovery_volume_path` over
  `artefact_ref`. Both values are written by the server and read from the database, and `BackupArtefactRetrieval`
  already uses the same convention. Nothing reaches it from the request, so there is no path traversal.
- **System-log independent scheduler** (`deploy/ui2/50-service-deployment.yaml:72-75`): scheduled device reads keep
  running while development mode holds every other schedule. This was a deliberate PO choice on 2026-11-05. No new
  mount, privilege, secret or securityContext was added.
- **V189** (15e75f8): the migration keeps every earlier CHECK value, and its route seed watermarks at `now()`, so no
  backlog of old alerts is sent. The alert checks run in their own transaction or savepoint and cannot fail the backup.
  Duplicates are fenced by an advisory lock, a partial unique index and `on conflict do nothing`. RBAC is unchanged
  (security_admin settings).
  - The mail carries `observed_hostname`, like every other notification type. That is operator mail, outside the
    AIView scopes; raise it with the PO only if mail is meant to be masked too.
- **PathDiagram client-side IP redaction** (`PathDiagram.tsx:51`): it applies to every role while the hop list beside
  it shows raw addresses. It is not a security control — the server's masking is — and the two views disagree.
- **`pathPreviewData.ts`:** only pseudonyms (`FW-TANGO-04`, `FW-BRAVO-02`, `FW-JULIET-06`) and documentation
  addresses. `tests/PathDiagram.test.tsx` uses private-range literals only as inputs that the redaction must remove.
- **`adminApi.ts` `jobTargetLabel`:** it reads fields the server never sends (`target_name`, `container_name`,
  `source_name`). Those branches never run.

## 7. Test gaps (beyond the fixes above)

- module_deploy barrier: a database-backed test for each holding state (M-1).
- JobLogQueryService: a test that the masked caller's `sort=device` and `q` do not use raw hostnames (M-2); a plan or
  row-bound test for the lateral joins (M-5).
- SystemHealthService: a transient read failure followed by recovery; unreadable cpwd chunks giving UNKNOWN (M-3).
- PathPseudonymMap: a step-1 versus bare-`peek` collision (M-4); truncation at `MAX_NETWORKS` (M-4).
- CpInventoryObjects: customer/Global alternation under the retention bound, with a reload count (M-6).
- Frontend:
  - snapshot mismatch in the path diagram (M-7);
  - `scheduling_enabled: true` past `MAX_COMPUTING_POLLS` (M-8);
  - an out-of-order sort response (L-5);
  - a body read that hangs (L-6);
  - a device switch during Collect-now (L-7).

## 8. Validation

- `cd ui2 && ./gradlew :service:test :persistence:test :worker:test :architecture-tests:test`: BUILD SUCCESSFUL.
  - service: 1425 tests, 0 failures;
  - persistence: 284 tests, 0 failures;
  - worker: 1419 tests, 0 failures, 2 skipped (already skipped before this branch);
  - architecture-tests: 44 tests, 0 failures.
- Repository privacy gate (`tools/privacy/repository_privacy_check.py`): PASS, 0 findings.
- Frontend: not touched by the fix. `ui2/frontend/node_modules` was not present in the review container, so
  vitest/tsc did not run.
- Real-environment validation: none. H-1 needs an aiview CSV export on the deployed build, with no raw label in the
  file, before it can be called `REAL_ENV_VALIDATED`.

## 9. Follow-up fixes: M-2, M-4, M-5 (branch `cloud/secfix-aiview-sort-1105`)

Brief: `docs/cloud-briefs/secfix-aiview-sort.md`. No live system was reachable; everything below is automated or local
synthetic evidence, not real-environment evidence.

### M-2 — fixed by refusing, not by sorting on the masked label

- Sorting on the masked label would need every filtered row masked in memory before the limit, the cost M-5 removes.
  The masked view is refused instead, and the UI says why.
- `JobQuery` carries `masked`, set by `JobLogController` from `isReplayViewer` for the page and the CSV export.
- `sort=device` / `device_name` for a masked caller throws `MaskedSortRefused`. The controller answers it with a 400
  `{"error": "<sentence>", "code": "SORT_UNAVAILABLE_IN_MASKED_VIEW"}`. Every other sort key is unchanged.
- A masked `q` matches `j.job_id` and `j.target_device_id` only. `d.observed_hostname` is dropped, and so is
  `j.terminal_reason`: reason text carries raw names and addresses that are masked only on output, so it was the same
  substring oracle.
- `JobPage` now says `target_sortable` and `search_scope` (`full` / `ids`). `PrivacyMaskingResponseBodyAdvice` keeps
  them through `withItems`; a masked page still never offers cancellation. `JobLogsPanel` reads these server fields,
  never a role: the Target header is not a sort button, a caption explains both limits, and the search placeholder
  changes.
- Not changed: for unmasked roles `sort=device` still orders by `d.observed_hostname`, not by the coalesced label
  (§4 M-2, last paragraph). Sorting by the full label would put the target joins back before the limit.
- The `PolicyQueryService` value/usage sort (§4 M-2, last bullet) is out of this brief's scope and still open.

### M-4 — fixed, with a narrower competitor set than §4 proposed

- Reproducing test first: `PathPseudonymMapTest.aShownHostIsAmbiguousWhenAnotherRawHostAiviewCanHoldBareRendersTheSameValue`.
  Before the fix it failed: the value resolved to the shown host.
- The §4 proposal (add every any-prefix candidate whose bare `peek` matches) was tried. It refused 12 of 140 shown hosts
  in `PathPseudonymOverlapTest.everyAttachmentHostTheTopologyShowedResolvesToTheHostItWasRenderedFrom`. Under a
  registered `/8`..`/16` summary, the bare form of a host no response ever rendered follows the summary's few masked
  networks, which brings back the 2026-11-04 false refusals.
- Fix: a step-1 hit competes with the raw hosts aiview can actually hold bare as the same value:
  - every bare value the IP masker handed out as it (`SubnetPreservingIpMasker.handedOutAs`, a read of the cache
    with no side effect; next hops, policy objects, IP searches and reason text all pass through it); and
  - every route next hop of the snapshot whose bare form is the value now.

  If the union has more than one raw host, the value is `AMBIGUOUS_PSEUDONYM`. A hypothetical host nobody was shown is
  not a competitor; the test asserts this case too.
- Limit: `handedOutAs` sees this process only. After a service restart, a value aiview saw before the restart is no
  longer in the cache; the next-hop check still covers route tables. The lookup is O(cache) per resolution, like the
  registered-subnet scan (L-12).
- The `MAX_NETWORKS` / `MAX_REGISTERED` truncation (§4 M-4, "Related, LOW") is unchanged and still open.

### M-5 — fixed: the page is chosen before any target join

- `JobLogQueryService.pagedSelect`: the inner query filters, sorts and limits on `jobs`/`devices` only, numbering rows
  with `row_number() over (<same order>)`. The outer query joins the target laterals to those job ids and orders by that
  number. `query`, `exportRows` and `recent` all use it.
- `JobLogQueryServiceTest.thePageIsChosenBeforeAnyTargetJoinRuns` guards the shape: no lateral and no target table in
  the inner query, every binding inside it, and every filter clause and sort expression reading only `j`/`d`.
- Local check (scratch PostgreSQL 16 with all 188 repository migrations, 5,000 synthetic `discovery_run` jobs):
  - for each of the 7 sort keys, with and without `q`, page 3 returned the same job ids in the same order as the old
    statement;
  - `EXPLAIN ANALYZE` for `result`, `duration` and the default order ran the policy lateral 50 times (the page size)
    instead of 5,000.

  The synthetic data is not estate evidence. No `EXPLAIN` guard runs in CI, because the service suite has no database.

### Validation

- `cd ui2 && ./gradlew :service:test :persistence:test :architecture-tests:test`: BUILD SUCCESSFUL.
  - service: 1433 tests, 0 failures (1425 before this branch);
  - persistence: 284 tests, 0 failures;
  - architecture-tests: 44 tests, 0 failures.
- Frontend (`npm ci`):
  - `npx tsc --noEmit`: PASS;
  - `npx vitest run`: 1384 of 1385 pass, including the 2 new `JobLogsPanel` cases.
  - The one failure is `BackupFleetTable.test.tsx` "orders Latest archive and Latest attempt by time…": a 5 s
    timeout under full-suite load. It fails the same way on `origin/cloud/security-review-1105` without this
    branch's frontend changes, and passes when that file runs alone. It was there before this branch and is not
    touched here.
- Repository privacy gate (`tools/privacy/repository_privacy_check.py`): PASS.
- Real-environment validation: none. M-2 needs an aiview session on the deployed build: Jobs screen, no Target sort, the
  caption shown, and `?sort=device` answered 400 with the sentence. M-5 needs the deployed plan on estate data.

