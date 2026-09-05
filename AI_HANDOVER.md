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
  **IMPLEMENTED**, ceiling `AUTOMATED_VALIDATED` pending fast PR CI
  confirming the new test file green (this sandbox has no pytest/lxml/
  paramiko; every AC was hand-verified directly instead).
- Implements `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md` §21
  exactly as frozen — no reinterpretation, no weakening, no expansion.
- PR to `main` opened this session (see "Delivery" for number/status once
  created); merge only after fast CI is green and conflict-free.

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

1. Push this branch, open a PR to `main`, watch fast PR CI. If it fails on
   anything in `tests/test_pcp1_device_registry.py` or elsewhere in the
   suite, fix and re-push — do not merge red.
2. Once CI is green: update `project/build_history.json`'s head record
   status from `in_progress` to `automated_validated` (with the CI run as
   evidence) and `project/roadmap.json`'s `now.status` to match, in the
   same PR, before merging.
3. Merge once green and conflict-free; sync local `main` to `origin/main`;
   report the exact merge commit.
4. Do **not** start `PCP.2`, any SQLite/local-console storage evolution,
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
- Fast PR CI has not yet run at the time of this handover — that is the
  next session's (or this session's continuation's) first action.

## 5. New risks

- `AUTOMATED_VALIDATED` is **not yet claimed** — do not advance
  `project/build_history.json`/`project/roadmap.json` past
  `in_progress` until fast PR CI actually confirms the suite green.
  Hand-verification in a dependency-less sandbox is evidence of intent,
  not a substitute for the real suite (`AGENTS.md` evidence laws).
- `pcp_console_registry_write_gate` remains open, untouched by this
  build — still decided for neither manual nor candidate-based
  enrollment intents.
- The next movement (local interactive console + SQLite storage
  evolution) stays explicitly not started, not designed, and not
  pre-authorized by this session; it needs a Product Owner sequencing
  decision first (`project/roadmap.json` `now_next.next`).
