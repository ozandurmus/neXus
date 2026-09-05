# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If anything below disagrees with `CURRENT_STATE.md` or `project/roadmap.json`,
> those win — see `AGENTS.md` "Authority hierarchy". This file exists only so
> a cold chat can learn the previous session's exact next action in one read;
> it is never the record of what shipped (that's `project/build_history.json`).

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase
doc. Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-05. Branch: `claude/nexus-control-plane-arch-mutgr9`
  (from `main` `ff700e38`).
- Build: `product_control_plane_architecture_draft` (`PCP.0`) — **still IN
  PROGRESS, freeze withheld pending a further Product Owner review.** This
  is the *second* bounded correction pass on
  `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`. The PO accepted
  round 1's corrections 1, 2, 4, 5 and most of correction 3, but rejected
  round 1's acceptance of a duplicate-`device_id` outcome under concurrent
  CLI writers as a legitimate `PCP.1` limitation. That contradiction is now
  closed. Status stays **DRAFT**.
- Docs/state only. No product code, test, taxonomy, console route, device
  command, schema or UI change.
- **Merge to `main` is still blocked** pending the next Product Owner
  review. No PR merged by this session.

## 2. What changed this session (round 2 only — see git history for round 1
and the original `PCP.0` build)

**The core fix — a cross-process registry mutation lock (§21).** The PO's
objection: the registry is the durable product-identity source, and atomic
tmp-then-`replace()` prevents a torn *file*, not a torn *decision* — two
concurrent `--registry-enroll` calls could each pass the duplicate check
against the same pre-write state and both commit, producing two records
for one normalized endpoint. Round 1 had accepted that outcome as a known
limitation; the PO said this cannot stand for the identity source of
record. Fixed by requiring `--registry-enroll`/`--registry-disable` to
acquire a single, narrow, file-based lock —
`data/state/device_registry.lock`, atomic `O_CREAT | O_EXCL` (portable
POSIX/Windows, no third-party library) — held across the *complete*
load → validate → duplicate-check-or-transition → atomic-replace sequence.
`--registry-list` stays lock-free (already race-safe, read-only).

- **Contention fails closed immediately**: no wait, no retry, no queueing.
  A `DeviceRegistryLockError` fires before any load/validate/duplicate-
  check/write.
- **Crash/stale-lock recovery is explicit and manual, never automatic.**
  The lock file records PID/hostname/timestamp for a *human* to read; no
  code path ever inspects PID liveness or lock age to auto-clear it —
  that would just trade one guess for another. Recovery is a documented
  manual delete after a human externally confirms the holder is dead.
- Replaced AC-10 (which had accepted the duplicate-record race) with a
  corrected AC-10 asserting **at most one record and one `device_id`**
  under any interleaving; added AC-13 (lock-contention fails closed
  pre-mutation) and AC-14 (no automatic staleness recovery).
- Non-goals list rewritten to state precisely what is still *not*
  introduced: no reusable/general locking library, no distributed or
  Postgres advisory lock, no blocking/retry/backoff, no HTTP wiring, no
  admission-coordinator involvement, no deployment work. The lock is
  scoped to `utils/device_registry.py` alone.
- Fixed a stale sentence the new lock design broke: the "Deterministic
  registry contract" intro previously claimed "no new lock primitive,"
  which the new mutation lock directly contradicted.

**Two factual wording fixes:**

1. **AC-2b overclaim removed.** It previously said the `credential_ref`
   format regex proves a value "could not satisfy" being a real secret.
   Rewritten to state only the real guarantees: it is a bounded opaque
   profile identifier, `DeviceRecord` has no separate credential-payload
   field, and `PCP.1` never resolves it to an actual credential anywhere —
   an operator who pastes a real secret into it still has that string
   persisted verbatim; the format check constrains shape, not secrecy.
2. **§18 stale wording fixed.** The closing paragraph still said "the one
   genuine contradiction is isolated to a single console intent" after
   round 1 had already widened the write-gate to cover both enrollment
   intents. Now names "a single console enrollment-write boundary —
   covering both the manual and the candidate-based enrollment intents
   together."

- `project/roadmap.json`: the `PCP.1` `next`-note's §21 description updated
  (mentions the mutation lock; AC-range corrected to `AC-1a..AC-14`).
- `project/build_history.json`: the existing `in_progress` head record
  (`product_control_plane_architecture_draft`) amended in place again —
  summary/evidence/`risks_forward` extended for round 2; still no new
  record (same still-open build). `docs/history/INDEX.md` regenerated.

## 3. Exact next action

1. Open (or update, if already open) a PR from
   `claude/nexus-control-plane-arch-mutgr9` to `main` (fast PR CI is
   sufficient — docs/state only). **Do not merge.** Return to the Product
   Owner for a further review pass of the corrected
   `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`, focused on §21's
   completed mutation-lock design and its AC-10/AC-13/AC-14.
2. On approval: apply §22 amendments, flip the doc status to `FROZEN`, set
   the build record to `done`, keep `next` = `PCP.1`, merge, sync `main`.
   `Sonnet 5, extended thinking (high)`.
3. Then `PCP.1` (`Sonnet 5, normal`): one short prompt pointing at §21 and
   `tests/test_pcp1_device_registry.py`. The mutation lock is now part of
   that contract — implement it exactly as specified (single exclusive-
   create file lock, fail-closed contention, manual stale-lock recovery),
   not as a generic concurrency utility. No device contact, no UI.

Unchanged and independent: `op2_c_cp_clusterxl_adapter_scoping` stays
blocked on `DEPLOY.1`; `op0b_0_close_d_v3a_d_v7b_pre_class2`; PAN serial
identity closure (hardware-blocked); `cp_remote_collection_done_marker_
diagnostics` (needs a recurrence).

## 4. Test delta

- No product code changed; full `pytest` **not run** — this sandbox has no
  `pytest`/`lxml`/`paramiko` (reported, not bootstrapped, per `CLAUDE.md`).
  Verified directly instead: `utils.project_plan.build_project_plan_payload()
  ["metadata_warnings"] == []`; `scripts/build_history_index.py --check`
  clean; `CURRENT_STATE.md` names `now.build` and is ≤ 200 lines; every
  `build_history.json` doc link resolves; `git diff --check` clean; no
  stray unescaped `|` from the new prose lands inside a markdown table
  row (checked directly); no other stale lock/concurrency claim remains
  elsewhere in the document (checked directly).
- Repository privacy gate re-run this session: **PASS / 0 findings**.
- Baseline 1825 passed / 24 skipped / 0 failed carried forward, not re-run
  (no product code touched).

## 5. New risks

- None to any reachable capability — no code changed.
- Process: the head `build_history.json` record stays `in_progress`. Do
  not flip it to `done` before the PO's next review actually freezes the
  document.
- Product: a future `PCP.1` implementation must not quietly drop the
  mutation lock or reintroduce the "duplicate-record race is an accepted
  limitation" framing round 1 had — that framing is exactly what this
  round closed as a genuine architecture contradiction, not a style
  preference. `pcp_console_registry_write_gate` remains undecided for both
  enrollment intents, unaffected by this round.
