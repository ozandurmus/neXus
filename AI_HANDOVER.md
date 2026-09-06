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

- Date: 2026-09-06. Branch `claude/m4-local-control-plane-metadata-store`
  from `main` at `33b6798` (PR #92). PR open; **not merged**.
- Build: `local_control_plane_metadata_store` (`M4`) — **COMPLETE**,
  storage infrastructure only.
- Local SQLite control-plane metadata store at
  `<data_root>/state/control_plane.db`, schema version 1, seven `STRICT`
  tables. Additive: nothing existing changed behaviour.
- Contract: `docs/history/phase/M4_LOCAL_CONTROL_PLANE_METADATA_STORE.md`.

## 2. What this session did

- **Corrected the baseline first.** Local `main` was 142 commits behind and
  had none of the `M4` contract documents; fast-forwarded to `33b6798` under
  Product Owner approval before any edit.
- **Added `utils/control_plane_store.py`** — placement validation, WAL +
  `synchronous=FULL` + `foreign_keys=ON` + explicit 5 s `busy_timeout`,
  `STRICT` tables, explicit monotonic one-transaction-per-version migrations,
  deterministic connection ownership, separate read-only reader connections,
  and seven typed fail-closed errors.
- **Added a ninth `evidence_backend` concern selector** that refuses
  `postgres` explicitly, naming `pcp_storage_engine` as still open.
- **Registered the store LOCAL-SENSITIVE (CLASS 2)** in
  `PRIVACY_AND_DATA_HANDLING.md`. No `support_bundle.py` or
  `repository_privacy.py` change was needed: `data/state/*` is already outside
  the only subtree the bundle enumerates, and `.db`/`.db-wal`/`.db-shm` were
  already `DATABASE_ARTIFACT` and already gitignored.
- **74 focused tests**, including ownership-boundary proofs that walk the real
  schema rather than a maintained list.
- **PO correction round** (one bounded round on the same branch, PR #93):
  the migration ledger is validated as an **exact prefix** of `MIGRATIONS`,
  not by `max(version)` — a foreign migration name at the supported version,
  an unknown/gapped/non-prefix entry or an unreadable foreign ledger is
  refused, naming both the encountered and supported ledgers. Open order was
  corrected so **every refusal precedes every persistent mutation** (WAL
  activation and ledger creation moved after validation), and connection
  cleanup is deterministic on every failure path.

## 3. Exact next action

**Product Owner review of the open PR, then merge decision.** Merge is not
authorized by the session that opened it.

`M5` (`collector_target_selection_seam`) is **next, not started, not
authorized** — it needs its own go-ahead. It is the critical path: every
collection job type today is `target_mode="none"`, so nothing device-targeted
is honest before it.

**Still outstanding from `M3`:** the Claude-side `nexus-decision-council` was
never stood up. It was explicitly not required for `M4` (deterministic
implementation against a frozen contract) but remains a prerequisite for the
next *architecture* movement.

## 4. Test delta

- **New:** `tests/test_m4_control_plane_metadata_store.py` — **74 passed**
  (57 at first review, +17 from the correction round).
- Affected suites: 161 passed / 9 skipped (architecture convergence, privacy
  gate, `PCP.1` registry, support bundle, `CON.2` console jobs, evidence
  backend, runtime paths). One run showed
  `test_ac9_two_jobs_both_reach_a_terminal_state` failing and green on
  re-run — a pre-existing timing-sensitive `CON.2` test; no console module
  references the store.
- **Full parallel suite, run once:** 1991 passed, 37 skipped, **3 failed,
  1 collection error — all pre-existing on the clean baseline** and verified
  as such by re-running them at `33b6798` with the working tree stashed:
  `test_binary_garbage_fails_closed`,
  `test_safe_gw_strips_spurious_trailing_underscore...` (both environment/
  `bash`-dependent), and `test_ci_workflow_fast_pr_regression.py`
  (`ModuleNotFoundError: yaml` — PyYAML not installed locally).
- One genuine failure of mine — `CURRENT_STATE.md` exceeding its 200-line cap —
  was fixed and re-verified targeted; the full suite was not re-run, since the
  only changed file is covered by that one test.
- `metadata_warnings == []`; build-history index `--check` clean;
  `git diff --check` clean.
- **Privacy gate:** `PASS`, 0 findings, against the exact tree being committed
  (497 files, exported to a temp dir). Running it in the working directory
  reports `FAIL` on `data/`, `logs/` and `data/.support_hmac.key` — untracked,
  gitignored runtime residue written by the pre-existing Panorama alignment
  tests during the full-suite run, not repository content and not from `M4`.
- **No device contact, no GitHub full regression, no `workflow_dispatch`,
  no merge.**

## 5. Risks / notes forward

- **Automated tests do not prove production readiness or real-environment
  validation.** Nothing here ran against a device.
- **The store has no caller.** It is infrastructure: no job behaviour, target
  resolution, runner, scheduling, enrollment or HTTP/UI integration exists yet,
  so `main.py` and the UI are unchanged and the database is never created
  during a normal run. `M5`…`M12` own that wiring.
- **`synchronous=FULL` is a deliberate departure** from WAL's usual `NORMAL`,
  justified by `CON.0` §7.9's durable-before-runner requirement. If a later
  movement finds the fsync cost real under load, that is a contract
  conversation, not a silent flip.
- **Pre-existing repo hygiene issue, unowned:** the Panorama alignment tests
  write into a repository-relative `data/` and `logs/` instead of a temp
  RuntimeRoot, which makes the working-directory privacy gate fail after any
  full-suite run. Worth a backlog row; not fixed here (out of `M4` scope).
- **`pcp_storage_engine` stays open.** SQLite is not the production engine and
  nothing in this movement may be read as selecting one.
