# Java test speed — 2026-11-05

Status: DRAFT (engineering record, not a contract). Scope: `:service:test :persistence:test :worker:test :architecture-tests:test`.
Environment: 4-core cloud container, JDK 21, cold-ish Gradle daemon, `--no-build-cache`, `-PfrontendPrebuilt=true`
(no npm in this container, no live system, no device access).

## Timings (wall clock of the four-task command; summed class time in brackets)

| Run | Wall | Summed class time |
|---|---|---|
| Before (baseline, 1 fork/module) | 3m30s | 415 s (service 178, worker 196, arch 28, persistence 13) |
| After: 1 fork/module + changes below | 3m29s | 482 s |
| After: 2 forks/module | 3m36s | 671 s |
| After: 2 forks/module + SerialGC (committed default) | 3m22s | 600 s |

Honest reading: on a 4-core box the suite is CPU-bound, so forks and GC choice move wall time by only ~4%
(run-to-run noise is of the same size). The 10+ minute (up to 58) figure in the brief comes from a slower or
contended host; this container never reproduced it, so the large effect the brief hopes for was **not demonstrated here**.
Summed class time grows with forks because classes share cores while they run; it is not extra work.
Re-measure on the slow host with `-Pui2.test.forks=1` versus the default.

## Slowest 30 classes (baseline, uncontended) and why

| s | Class | Reason |
|---|---|---|
| 63.2 | service PolicyHygieneDifferentialTest | 4120 seeded snapshots × brute-force oracle; pure CPU. Coverage must stay; methods now `@Execution(CONCURRENT)` |
| 45.5 | worker CheckPointPolicyCollectorTest | 56 tests, many looping collect() over Mockito mocks (reset/verify heavy), sessions=1 and 4 |
| 33.4 | worker CpPolicyParallelCollectionTest | multi-thread collection with latches; one test 13.7 s (layer restarts) |
| 17.1 | service PacketAnalysisPayloadMaskingApiTest | Spring MockMvc context + DuckDB case builds |
| 14.9 | worker CpObjectInventoryCollectionTest | parameterised, per-case collector runs |
| 14.7 | architecture-tests PathValidationFenceTest | ArchUnit classpath import |
| 14.6 | worker CpChangedDomainsTest | repeated full collections |
| 14.4 | service PacketAnalysisApiTest | MockMvc + DuckDB |
| 13.7 | worker InteractiveShellSessionReadTest | real-time streaming-quiet windows (≥1.1 s each) |
| 10.7 | service PolicyHygieneScaleTest | large synthetic policies |
| 9.7 | service PacketAnalysisCorrelationServiceTest | DuckDB |
| 9.2 | worker PanoramaPolicyCollectorTest | 22 collections |
| 7.6 | service PolicyHygieneAccessRoleTest | large snapshots |
| 7.5 | worker CpFailoverJobExecutorTest | 70 tests |
| 7.4 | service ComplianceServiceCacheParityTest | cache parity loops |
| 7.1 | worker CpCollectionGapFixesTest | layer-restart backoff (now 0) |
| 7.1 | architecture-tests FailoverMutationFenceTest | ArchUnit import |
| 6.9 | service PolicyHygieneZoneSupersetTest | CPU |
| 6.3 | persistence PolicyChunkStoreTest | chunk encode/decode |
| 4.6 | worker CpPolicyDomainCompletenessTest | restarts (backoff now 0) |
| 4.4 | worker CpSystemLogCollectJobExecutorTest | 54 tests |
| 3.2 | service ComplianceControllerTest | Spring context |
| 3.1 | service PolicyDomainQueryTest | CPU |
| 2.8 | worker JobCancellationScopeTest | thread/cancel latches |
| 2.5 | worker CpSpikeLayoutReviewJobExecutorTest | — |
| 2.5 | worker PanoramaCompletenessTest | — |
| 2.4 | worker PanFailoverJobExecutorTest | — |
| 2.3 | architecture-tests Ui2ArchitectureTest | ArchUnit import |
| 2.3 | worker CpInventoryResolutionTest | — |
| 2.1 | worker PanoramaConfigLogReaderTest | — |

Static scan: only ~5 `Thread.sleep` calls exist in the four modules' tests (≤200 ms each), so sleeps are not the cost.
The "—" rows were not investigated (<3 s each).

## Changes

1. **Clock injection (FailoverScheduleServiceTest determinism).** `ClockHealthCheck` gained `(Clock, LongSupplier)`
   and `now()`; the no-arg constructor keeps the process-start anchors and system clock, so production behaviour is
   unchanged. `FailoverBookingAdmissionControl` gained a `Clock` constructor (no-arg = system UTC).
   `FailoverScheduleService` reads time through the health check for freshness, `scheduledAt` and cancellation.
   The test now freezes one instant for the evidence timestamps, the clock-health check and admission control.
   Likely cause of the one-off `BASELINE_EVIDENCE_UNAVAILABLE`: `ClockHealthCheck` compares wall-clock elapsed to
   monotonic elapsed since JVM start (tolerance 60 s) and the test's snapshots were stamped from `Instant.now()`;
   a host clock step/pause or a stall under contention fails that check. That cause is inferred from the code and
   was **not reproduced**. Assertions are unchanged.
2. **Retry backoff.** Test JVMs get `ui2.policy.cp.layer-restart-backoff-ms=0` (production default 3000 ms;
   existing tests already set it per class — their `@AfterEach` now restores 0 rather than clearing it).
3. **Parallelism.** `maxParallelForks = min(4, cores/2)` (override `-Pui2.test.forks=N`; 1 = serial), 768 MB heap,
   `-XX:+UseSerialGC`. JUnit parallel execution is enabled but default `same_thread`; only
   `PolicyHygieneDifferentialTest` opts in (`@Execution(CONCURRENT)`, no shared mutable state).

## Not done / caveats

- `FrontendBundleFreshnessTest.packagedFrontendCarriesAStringFromCurrentSource` fails in this container in every run,
  before and after: the frontend was not built (`-PfrontendPrebuilt=true`, no npm). Environment, not a regression.
- No class was made faster by reducing its work: the heavy ones are CPU-bound oracles/mocks whose coverage must stay.
  The real-time waits in `InteractiveShellSessionReadTest` would need clock injection into production streaming
  code; left out as a larger, riskier change.
- Not verified: the effect on the slow host; flakiness of other tests under 2 forks beyond one clean full run each.
