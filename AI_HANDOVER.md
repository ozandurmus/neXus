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

- Date: 2026-09-05. Branch: `claude/pcp1-device-registry-gqp7s2`, built
  directly on `origin/main` at `c486a1c49d5968ea16ff42de2509bc305ea8362c`
  (merged PR #82, the `PCP.0` freeze) — `main` had not advanced further.
- Build: `pcp_1_device_registry_manual_enrollment_foundation` (`PCP.1`) —
  **AUTOMATED_VALIDATED**. This sandbox has no pytest/lxml/paramiko; every
  AC was hand-verified directly, then confirmed by PR #83's fast PR CI
  `validate` check running the real suite green on commit `a149f5a`.
- Implements `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §21
  exactly as frozen — no reinterpretation, no weakening, no expansion.
- PR #83 opened to `main`, CI green, `mergeable_state: clean`, no reviews
  or comments pending. Merging next in this same session.

## 2. What changed this session

- **`utils/device_registry.py`** (new): `DeviceRecord` (closed dataclass),
  `DeviceRegistry.enroll/list/disable`, endpoint normalization (no DNS
  resolution, an explicit port split out and compared as a separate
  literal field), vendor-hint-/lifecycle-independent duplicate detection,
  fail-closed corrupt-data handling (whole-document, not row-by-row), and
  the registry mutation lock (`O_CREAT|O_EXCL`, `owner_token`
  instance-safe release — never a bare unlink).
- **`utils/evidence_backend.py`**: added the eighth concern,
  `DeviceRegistryBackend` (abstract) + `FilesystemDeviceRegistryBackend`
  (dumb `load_raw`/`save_raw`, same split as the other seven) +
  `select_device_registry_backend` (raises on `postgres` — no
  implementation exists; `pcp_storage_engine` stays open).
- **`application/cli.py`**: `--registry-enroll` (`--registry-endpoint`,
  `--registry-vendor-hint`, `--registry-credential-profile`,
  `--registry-tag`), `--registry-list` (`--show-endpoints`),
  `--registry-disable <device_id>` — mode-exclusive with every existing
  mode, dispatched in Phase D (no vendor import, no credential
  resolution, before `services.build_collection_services`).
- **`application/workflows/registry.py`** (new): thin CLI-intent dispatch
  only; calls `utils/device_registry.py` for all business logic.
- **`tests/test_pcp1_device_registry.py`** (new): AC-1a..AC-15, including
  a deterministic lock-contention/instance-safe-release technique (direct
  calls to the module's private lock primitives, not timing/threads) per
  section 21's own validation-ladder note that the exact technique is an
  implementation detail.
- **Docs**: `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §21 status
  line (IMPLEMENTED); `AI_START_HERE.md` CLI table + directory map row +
  the deferred §22 item 4 "What this is" sentence (now applied, since a
  persistent registry actually exists); `docs/ARCHITECTURE.md` new §7A;
  `PRIVACY_AND_DATA_HANDLING.md` CLASS 2 line for the registry + lock
  files.
- **State**: `project/roadmap.json` (`now`/`next` rotated; `current_build`
  updated), `project/feature_registry.json` (all five
  `device_registry_enrollment_foundation` criteria → `done`, feature
  status → `in_progress`), `project/build_history.json` (new head
  record), `docs/history/INDEX.md` (regenerated).

## 3. Exact next action

1. This state-update commit (flipping `build_history`/`roadmap` to
   `automated_validated` with the CI evidence) still needs to be pushed
   and merged into PR #83 alongside the implementation commit.
2. Merge once CI is green on the updated head and conflict-free; sync
   local `main` to `origin/main`; report the exact merge commit.
3. Do **not** start `PCP.2`, any SQLite/local-console storage evolution,
   or an Add Device UI. The actual next movement
   (`pcp_2_local_control_plane_sequencing_po_review`) is a Product Owner
   decision on sequencing, not yet made — see `project/roadmap.json`
   `now_next.next`.

## 4. Test delta

- Prior baseline `1825 passed / 24 skipped / 0 failed` carried forward,
  **not re-run** by this session (no pytest/lxml/paramiko in this
  sandbox, reported per `CLAUDE.md`, not bootstrapped).
- New `tests/test_pcp1_device_registry.py` added and hand-verified via ad
  hoc Python scripts against the real module/CLI code (not a substitute
  for pytest — see `project/build_history.json` head record for the full
  list of what was checked this way).
- `utils.project_plan.build_project_plan_payload()['metadata_warnings'] ==
  []`; `scripts/build_history_index.py --check` clean; `git diff --check`
  clean; repository privacy gate re-run directly: **PASS / 0 findings,
  487 files scanned**.
- Fast PR CI (`validate` check) ran green on commit `a149f5a264ebd44db005ad7f5bffa4012f8b30dd`
  (https://github.com/ozandurmus/neXus/actions/runs/33979500386/job/101342049427);
  `full-regression` skipped as designed (PR-only trigger is main
  push/dispatch).

## 5. New risks

- `pcp_console_registry_write_gate` remains open, untouched by this
  build — still decided for neither manual nor candidate-based
  enrollment intents.
- The next movement (local interactive console + SQLite storage
  evolution) stays explicitly not started, not designed, and not
  pre-authorized by this session; it needs a Product Owner sequencing
  decision first (`project/roadmap.json` `now_next.next`).
