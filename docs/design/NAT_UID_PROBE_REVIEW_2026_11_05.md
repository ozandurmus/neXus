# Review — one-off Check Point NAT rulebase probe by package UID (2026-11-05)

**Status: REVIEW RECORD (not a contract).** Reviewed commit: `d5030c3` on
`origin/cloud-src/natprobe-1105` (one commit on top of `ed068bb`; `origin/main`
has since moved but carries no `V190` and no conflicting migration). Authority
reviewed against: `docs/design/PO_DECISION_RECORD_2026_11_05_CP_NAT_UID_PROBE.md`
(RATIFIED). Fixes applied on `cloud/natprobe-review-1105`. No live-system access:
everything below comes from reading the code and running automated tests. The
real-environment probe outcome is still `UNKNOWN`.

## Approved literal

`mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PACKAGE_UID>' limit 1 offset 0 details-level standard use-object-dictionary true`

## Verdict summary

| Area | Verdict | Fix applied |
|---|---|---|
| 1. Exact-literal fidelity | PASS | — |
| 2. Gate migration and fixture consistency | PASS (2 observations) | — |
| 3. UID validation and quoting | PASS (1 observation) | — |
| 4. Runs only on `VENDOR_NAME_NOT_UNIQUE`, at most once per package per run, never retried | PASS on trigger/once/no-retry; **FAIL on failure handling and timeout** | F1, F2 |
| 5. Probe content discarded (raw-evidence law) | PASS | — |
| 6. Outcome recorded as an enum only | PASS, hardened | F3 |
| 7. Tests | Good; gaps filled | 2 tests added |

## 1. Exact-literal fidelity — PASS

- `CpPolicyGates.NAT_UID_PROBE_COMMAND`, the `gate_registry_fixture.yaml` row,
  `V191__cp_nat_uid_probe_gate.sql` (with `''` escaping) and the decision record
  all hold the same literal byte for byte. `tools/tests/test_cp_policy_gate_contract.py`
  checks all four against one expected string.
- `MgmtCliCommands.showNatRulebaseByUidProbe(domain, uid)` produces that literal:
  domain and UID are single-quoted, and `limit 1 offset 0` is unquoted, as in the
  approved text. This differs on purpose from the by-name read, which uses
  `limit 500 offset '<N>'`. `CheckPointPolicyCollectorTest` checks that the
  placeholder render equals the gate constant.
- The probe never sends any other limit or offset. Nothing pages by UID.

## 2. Gate migration and fixture consistency — PASS

- `V190` is the next free version on both the source branch and current `main`
  (main ends at `V189`). It has one `INSERT … ON CONFLICT (gate_id) DO NOTHING`,
  no `UPDATE`/`DELETE`, and sets the migration actor fingerprint and the
  `gate_registry_update_by_migration` action id.
- The row matches the fixture on every field: `check_point` /
  `cp_multi_domain_server` / `expert` / `SSH_EXEC` / `read` / `SIGNED_OFF` /
  `timeout_s 60` / `retry_rule none`, and the `max_frequency`,
  `session_reuse_rule`, `unsupported_behavior_ref`, `secret_output_risk`,
  `safe_telemetry_fields ["outcome"]` and `source_document_pointer` text.
- The probe is not offered as a `DiagnosticRead` command (`CpPolicyGatesTest`).
- *Observation O1.* `CpPolicyGates.requireAll` and `capability()` now include
  the probe gate. Admitting any CP policy collection therefore needs the V191
  row. This fails closed and is fine while both ship together. But if the probe
  gate is later retired (for example after the PO decides on a paged UID
  read), the gate must be removed from `requireAll`/`capability()` in the same
  change, or every CP policy collection will be refused.
- *Observation O2 (pre-existing, out of scope).* The by-name NAT gate
  `cp_policy_nat_rulebase` also declares `timeout_s 60`, but its read waits for
  the configured `UI2_POLICY_CP_READ_TIMEOUT`, which defaults to 300 s. That
  mismatch predates this commit and is not changed here. It should be raised
  with the PO as its own item.

## 3. UID validation and quoting — PASS

- The UID is the package `uid` from `show-packages`, the same value already used
  for the NAT ref. `MgmtCliCommands.quote` rejects null, blank and any
  ISO-control character (including newlines) and escapes `'` as `'\''`. This is
  the same shell-quoting every gated policy command uses.
- A null or blank UID returns before anything is issued. The outcome stays
  `null` and no command is sent.
- The UID is treated as opaque. There is no case folding, trimming or format
  normalization, as the identity law requires.
- *Observation O3.* There is no allowlist for UID format (for example
  UUID-shaped). Adding one would rely on a vendor-format semantic that no FROZEN
  contract proves, so it is left `UNKNOWN`. Quoting is enough for injection
  safety.

## 4. Trigger, frequency, retry — PASS on gating; two defects fixed

**Gating is correct.**
- Sequential path: `natFailure` probes only when `nameNotUnique(error) != null`.
  Every other NAT failure returns the old gap before any probe code runs.
- Parallel path: the worker probes only when the failed work item is the
  package's NAT layer and the error is name-not-unique. It probes on the same
  session, then closes that session as before.
- `NatUidProbes.begin` runs `putIfAbsent` on the NAT ref (container + package
  UID) in a `ConcurrentHashMap`, so there is at most one probe per package per
  run. A repeat (page retry, domain restart) reuses the recorded outcome and
  issues nothing. The probe has no retry loop.

**F1 (fixed) — a transport failure in the probe broke the contract.**
`transport.execInteractive` can throw a non-fatal `RuntimeException`, such as a
closed channel. The commit did not catch it:
- sequential: the exception left the NAT `catch` block and failed the whole
  domain or run, all because of a diagnostic probe;
- parallel: `error = probeError` replaced the `VENDOR_NAME_NOT_UNIQUE` failure.
  The NAT section turned from `UNKNOWN` into an ordinary gap with the probe's
  reason, and no outcome was recorded.

The decision record says "transport failure → `NOT_EVALUABLE`; the package's
NAT section remains `UNKNOWN` (`VENDOR_NAME_NOT_UNIQUE`) exactly as before".
Fix: `probeNatByUid` now catches a transport exception. Admission/lease
rethrows (`EndpointRuntime.rethrow`), fatal run states
(`PolicyCollectionTrace.fatal`), interruption and deadline still abort. Any
other exception records `NOT_EVALUABLE`. The parallel `error = probeError`
assignment is now reached only for fatal errors, which abort the run anyway.

**F2 (fixed) — the probe waited longer than its approved timeout.** The gate
row approves `timeout_s 60`. The commit used `readTimeout`, which defaults to
300 s. Fix: the probe now uses `min(60 s, readTimeout, remaining job time)`
(`CheckPointPolicyCollector.NAT_UID_PROBE_TIMEOUT`).

*Observation O4.* The probe still passes the shared streaming extension
(`ExecSpec.extensionMs`, up to 120 s), as every policy read does. Whether that
extension counts against a gate's `timeout_s` is not defined in any FROZEN
document, so it is `UNKNOWN` and left unchanged.

*Observation O5.* In the sequential collector, a probe that ends `TimedOut`
returns `NOT_EVALUABLE` and the same shell goes on to the next package. A
by-name NAT `TIMEOUT` already behaves this way, so the risk is not new, but
the probe adds one more chance of a late reply on a reused shell. The parallel
collector closes the session. Aligning the sequential collector (reconnect
after any NAT/probe timeout) is a separate change.

## 5. Raw-evidence law — PASS

- Execution is wrapped in `JobTranscriptScope.withoutRecording`. The trace keeps
  only the result class and byte count. The step trace keeps the template
  constant and an opaque `ref(...)` (UUIDv3), never the rendered command.
- The reply is parsed into a local `JsonNode`, checked with `pageTotal`, and
  dropped. The probe never calls `rejectedRulebase`/`invalidPreflight`, so no
  vendor field names or messages reach diagnostics.
- Nothing from the probe goes into sections or objects. The test
  `natUidProbeRecordsAcceptedRejectedAndNotEvaluableOncePerPackageAndDiscardsContent`
  checks that rule names, object names, a `192.0.2.x` address, the vendor error
  message and package names/UIDs reach neither the transcript nor the failure
  entries.

## 6. Enum-only outcome — PASS, hardened

- The transcript note is counts only:
  `[CP_NAT_UID_PROBE] packages=<n> accepted=<n> rejected=<n> not_evaluable=<n>`.
  It is written once per run, in both collectors.
- `PolicyPrivacy` turns any `natUidProbe` value outside the enum into `UNKNOWN`.
- **F3 (fixed).** The record itself accepted any string. `CollectionFailure` now
  has a compact constructor that allows only `null` or one of
  `UID_ACCEPTED`/`UID_REJECTED`/`NOT_EVALUABLE`, and only on a
  `VENDOR_NAME_NOT_UNIQUE` failure. Anything else throws
  `POLICY_NAT_UID_PROBE_OUTCOME_INVALID`.
- *Observation O6 (interpretation risk for the PO).* As the decision record
  defines it, `UID_REJECTED` means "any vendor API error object". That also
  covers session-pressure or lock errors, and another not-unique answer, so it
  does not prove the API refuses UIDs. Keep this in mind before using a
  `UID_REJECTED` count to decide anything about a paged UID read.
- *Observation O7.* A domain reused because it is unchanged
  (`CpDomainReuse`) republishes its earlier snapshot, including the earlier
  probe outcome, and does not probe again. This is consistent with "at most once
  per package per run".

## 7. Tests

Present in the commit, and good: literal and placeholder render, quoting
rejection, gate lookup, gate missing → refused, accepted/rejected/not-evaluable
across 1 and 4 sessions, no probe on other NAT failures, malformed probe page →
`NOT_EVALUABLE`, content non-leak, and Python fixture ↔ migration ↔ decision
parity.

Added in `CpPolicyDomainCompletenessTest`:
- `natUidProbeTransportFailureIsNotEvaluableKeepsNatUnknownAndWaitsAtMostTheGateTimeout`
  (1 and 4 sessions): a thrown transport exception gives `NOT_EVALUABLE`; the
  NAT failure stays `VENDOR_NAME_NOT_UNIQUE`/unknown-part; the transcript count
  is right; the probe is issued once with a timeout ≤ 60 s.
- `natUidProbeOutcomeIsAnEnumOnANameNotUniqueFailureOnly`: record validation.

Adjusted: `PolicyQueryServiceTest.partialSnapshotFailureSurvivesPagingAndAIView`
used to build a failure with a raw probe string, which F3 now refuses. It now
checks that the record refuses it, and that `PolicyPrivacy` still masks a stray
`natUidProbe` value to `UNKNOWN`.

## Validation

- `python3 -m unittest tools.tests.test_cp_policy_gate_contract` — OK (3 tests).
- `cd ui2 && ./gradlew :worker:test :architecture-tests:test` — BUILD SUCCESSFUL;
  worker 1423 tests (0 failures, 0 errors, 2 skipped), architecture-tests 44
  tests (0 failures).
- `./gradlew :service:test --tests '*PolicyQueryServiceTest' --tests '*PolicyApiTest'
  :job-engine:test --tests '*CpPolicyGatesTest' :platform-core:test` — green.
- Real-environment validation: not performed (no live access). The probe
  behaviour can be at most `AUTOMATED_VALIDATED`.
