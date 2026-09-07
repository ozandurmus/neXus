# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal.

---

## 1. Snapshot

- Date: 2026-09-07. `M8.3` — **AUTOMATED_VALIDATED**, branch
  `claude/m8-3-first-contact-producer-nh0260` from `origin/main` at
  `8547af6faecf0566cc7ab6558f7f9939f8b394e4`, PR not yet opened/merged
  (awaiting Product Owner authorization for the real-environment gate first).
- Build: `m8_3_first_contact_producer_real_env_gate` (`M8.3`) — `status:
  automated_validated`.
- Contract: `docs/history/phase/M8_FIRST_CONTACT_TRUST_AND_IDENTITY_EVIDENCE_PRODUCER_ARCHITECTURE.md`
  §3/§4/§5/§6/§8 — FROZEN, unchanged by this session.
- `M8.2` (predecessor) is confirmed **merged** to `main` via PR #98 (commit
  `16c39ab`) — `CURRENT_STATE.md`'s prior "PR pending"/"unmerged" wording was
  stale and is corrected as part of this session's state update.

## 2. What this session did

Implemented `M8.3` per the frozen `M8` contract §3/§4, scope exactly as
named: the read-only first-contact producer, reusing existing per-host
primitives, plus preparing (not executing) the real-environment gate.

1. **New `utils/first_contact_producer.py`**: `run_first_contact_producer`
   — fail-closed Device Registry resolution (no mutation, no lock);
   exact-one `PhysicalTarget` candidate selection via the existing
   `configuration.checkpoint_config_collector._resolve_targets`, matched
   only on `management_ip`; the mandatory trust-before-credential sequence
   (`utils.cp_ssh_trust.lookup_trusted_host_key` first — the caller-supplied
   `resolve_credentials` callback is never invoked before it succeeds);
   the existing, unmodified `_collect_host`/`_identity_gate`/
   `_collector_identity_gate`/`_entity_id` primitives, `strict_host_key`
   always forced `True`; the positive write gate (identity accepted +
   non-empty serial + a resolvable `producing_run_ref`, resolved via
   `ConfigEvidenceStore.backend.list_snapshots`) before calling the
   existing, unmodified `M8.1` `record_first_contact_proof`. Every refusal
   path returns a sanitized `ProducerOutcome` token only.
2. **New `application/workflows/first_contact.py`**: thin CLI orchestration
   — resolves CP credentials via `application.services.make_runtime_config`
   only inside the callback the producer itself invokes, always calls
   `Config.clear_credentials()`, prints only `device_id`/`outcome`/`reason`/
   `relationship_id`.
3. **`application/cli.py`**: new `--identity-first-contact DEVICE_ID` mode,
   its own mutual-exclusion refusal block (mirrors `--cp-ha-preflight-check`/
   `--pan-ha-preflight-check`), one dispatch line. Not console-submittable —
   `console/registry.py`'s `JOB_REGISTRY` has no entry for it.
4. **`application/services.py`**: `_MODE_PREREQUISITES` gained an
   `identity-first-contact` entry (same `cp_telemetry.json`/`cp.json`/
   `vsx.json` prerequisite set as `--cp-config-probe`).
5. **New tests**: `tests/test_m8_3_first_contact_producer.py` (19 tests) and
   `tests/test_m8_3_first_contact_cli.py` (18 tests) — see §4 below.
6. **Project-state update**: `project/roadmap.json` (`now` = `M8.3`
   `automated_validated`; `next` = `m8_3_real_environment_validation`,
   `planned`; `current_build` updated), `project/build_history.json` (new
   head record), `CURRENT_STATE.md` (checkpoint, Active build, Exact next
   build, test baseline, and the stale M8.2 merge-status wording corrected),
   `docs/history/INDEX.md` regenerated via `py scripts/build_history_index.py`.

## 3. Exact next action

**`M8.3` real-environment validation — Product Owner authorization
required, not performed in this session.** Exactly one bounded, read-only
command is proposed:

```
py main.py --identity-first-contact <one enrolled registry device_id>
```

It must be run only against a registry `device_id` the Product Owner has
explicitly approved for real-device contact, exercising the full trust →
credential → `RejectPolicy` → identity gate → serial → governed evidence →
relationship-write sequence, reporting only relationships/statuses (never
raw endpoint/serial/fingerprint/username/credential/host-key values),
confirming the new `producing_run_ref` resolves, and measuring the actually
observable CP config evidence-retention lower bound/window the frozen
contract §6/§9 needs before `CONTRADICTORY_EVIDENCE` detection can be judged
buildable — report `UNKNOWN`/`INSUFFICIENT_EVIDENCE` if it cannot be
established, never inferred.

`M8.3` stays `AUTOMATED_VALIDATED` until that gate runs; `M8.4` (`M6`
resolver consumption) must not begin without it.

## 4. Test delta

New: `tests/test_m8_3_first_contact_producer.py` (19 tests, no real device/
socket/network — `_collect_host` monkeypatched throughout) and
`tests/test_m8_3_first_contact_cli.py` (18 tests, CLI/workflow wiring only).
Targeted run: 37 passed. Affected run (`M8.1`/`M8.2`/`PCP.1`/`M4`/
architecture convergence/`OP.0d` target selection/`OP.0b` S7.5 preflight
entrypoint, plus the two new files): 335 passed. Wide CP-config collector/
probe/trust sweep (24 files) plus `test_phase0_6_1b_1_4_cp_ssh_trust.py`:
441 passed, 1 skipped (pre-existing, unrelated), 0 failed. Privacy gate:
PASS, 0 findings (`data/`/`logs/` cleared before each run). `git diff
--check`: clean. No `full-regression`/`workflow_dispatch` run (risk-based;
two new files plus one additive CLI mode, no shared-core/schema/storage/
console/UI change; `M8.1`/`M8.2` reused entirely unmodified).

## 5. Risks / notes forward

- Real-environment validation is the only thing standing between `M8.3`
  `AUTOMATED_VALIDATED` and `DONE` — no further code is expected for it.
- This build deliberately does not add admission-coordinator
  (`utils.collection_executor`) integration for the device-contact step,
  unlike `OP.0b` S7.5's CP/PAN HA preflight entry points — a narrow,
  manually-invoked, single-device CLI maintenance/bootstrap tool, the same
  category as `--registry-enroll`/`--persistent-secret-material-check`.
  Add it later only as its own bounded change if a real need appears.
- The `_ELIGIBLE_REGISTRY_STATES == {"ENROLLED_UNVERIFIED"}` gate is this
  build's own defensive addition (a `DISABLED`/`RETIRED` device should not
  be first-contacted) — the frozen contract's §2 does not itself name a
  lifecycle-eligibility requirement; this is a conservative interpretation,
  not a restated contract clause.
- `CONTRADICTORY_EVIDENCE` detection stays deliberately unbuilt (§6/§10) —
  the real-environment gate's evidence-retention measurement is what will
  eventually let that gap be judged closeable or not; do not build it as a
  side effect of any other movement.
- Three dissents remain open, unchanged: `operator_assertion`,
  single-sourced identity evidence, deferred contradiction detection.
- Table/column names and the relationship write API are `M8.1`'s, unchanged
  by this session — `M8.4`+ must keep reading them from
  `utils/device_identity_relationships.py`, not re-derive them.
