# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Product baseline: `0.7.6a — Render harness + uitest topology matrix` —
  AUTOMATED_VALIDATED (2026-08-30, prior session).
- Engineering baseline: `DEV.1` complete; `DEV.2.1` AUTOMATED_VALIDATED;
  **`DEV.2.2` (`deploy_persistent_secret_material`) AUTOMATED_VALIDATED this
  session.**
- **RECOVER track opened this session** (new, was previously deferred):
  `RB.0`/`RB.1`/`RB.4` AUTOMATED_VALIDATED; `RB.2` (PAN device-state export)
  IMPLEMENTED, real-environment validation owed; `RB.3` (CP) a typed blocked
  stub, unresolved. See §2.
- Date: 2026-08-30.
- **Branch `claude/deploy-persistent-secret-material-3rtfrs` pushed, 5 commits
  ahead of `main`, `main` unmoved (0 behind).** `PR #15` is open
  (https://github.com/ozandurmus/neXus/pull/15), title "Backup & Recovery:
  architecture, RB.0-RB.2 (PAN), RB.4, and DEV.2.2 persistent secret
  material". **Not merged — merge is human-initiated, per standing priority.**
  Next session: check whether #15 merged; if not, decide whether to continue
  on the same branch or wait.
- Full suite this session: `741 passed, 3 skipped, 2 pre-existing unrelated
  failures` (`test_run_html_export_embeds_discovery_payload_without_leftover_placeholder`,
  `test_checkpoint_render_appends_one_record` — confirmed identical on a clean
  `git stash -u` baseline, i.e. present on `main` too, not introduced this
  session).
- Repository privacy gate: **PASS / 0**, confirmed clean after each build this
  session (delete gitignored `data/`/`logs/` before re-running — same standing
  note as always).
- **This sandbox had no preinstalled Python toolchain matching the repo
  baseline** (`py` command not found, no `paramiko`/`lxml`/`cryptography`
  etc.). Built a throwaway venv: `python3 -m venv /tmp/venv && /tmp/venv/bin/pip
  install -q -r requirements.txt pytest`. That venv does not survive between
  sessions — if a future session hits `py: command not found` or missing
  deps, this is expected in a fresh cloud sandbox, not a repo problem;
  recreate the venv the same way. `docker` and `bun` (`~/.bun/bin/bun`) WERE
  available and used for real (not simulated) `docker compose config`
  verification and the render harness.
- **`cryptography>=41` is now a direct `requirements.txt` dependency** (was
  only transitive via `paramiko`) — used by `utils/recovery_crypto.py`
  (AES-256-GCM envelope encryption).
- New pytest marker: `recovery` (backup/recovery plane, RB.x) — used by every
  RB.x test file this session and going forward.

## 2. Recent builds (this session) — all on the open branch/PR, NOT on `main` yet

Detail in `project/build_history.json` (newest first);
`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` +
`docs/design/BACKUP_RECOVERY_CONTRACTS.md` are the frozen design + contracts
everything below was built against.

- **`recovery_collect_rb2_rb4`** (`74267e2`) — D2 resolved (product owner
  approved PAN service-account superuser for device-state export only,
  recorded in architecture §13). `RB.2` IMPLEMENTED: `utils/recovery_collect.py`
  (target selection incl. explicit gateway lists + VSX `__vsid_` addressing,
  `RecoveryCollector` protocol, admission-coordinated per-target execution —
  one gateway's failure never aborts the batch), `panorama/panorama_recovery_collector.py`
  (PAN device-state export, `read` class, session-reuse, no 403 retry),
  `checkpoint/checkpoint_recovery_collector.py` (typed blocked stub — P0 audit
  + `D3` unresolved), `collection_executor.ALLOWLISTED_WORKFLOWS` +=
  `"recovery-pan"` (not `"recovery-cp"`) with an additive optional
  `ScheduledWorkflow.targets` field, `main.py --recovery-collect
  --recovery-vendor --recovery-gateways` (thin dispatch only, **explicit
  product direction: no collection logic in main.py**). `RB.4`
  AUTOMATED_VALIDATED: `utils/recovery_validation.py` (V1-V3 battery),
  `recovery_store.revalidate_artifact`, `main.py --recovery-validate` (gates
  on any individual check FAIL, not just the top-line verdict — a real bug
  was caught and fixed here). 85 new tests
  (`tests/test_rb2_recovery_collect.py`, `tests/test_rb4_recovery_validation.py`);
  one pre-existing test updated in place for an intentional allowlist
  expansion. Manually verified end-to-end incl. a real `--scheduler-once` run.
- **`recovery_store_rb1`** (`6840f91`) — encrypted recovery-plane store:
  `utils/recovery_crypto.py` (AES-256-GCM envelope encryption),
  `recovery_manifest.py` (builder/validator enforcing all five frozen §3
  rules), `recovery_store.py` (vault key lives on `data_root`, never
  `recovery_root` — mirrors DEV.2.2's `.support_hmac.key` precedent),
  `recovery_retention.py` (GFS + floor invariant, dry-run by default).
  `resolve_recovery_root` in `utils/runtime_paths.py` — mandatory,
  no OS-default, validated separate from both repo and runtime roots.
  `main.py --recovery-store-check`. `docker-compose.yml` gains
  `securityexpert-recovery` volume on `worker` only. 41 tests.
- **`restore_readiness_rb0`** (`21df418`) — `utils/restore_readiness.py` +
  `main.py --restore-readiness-check`; 16 tests. Manually verified: 15
  uitest-fixture devices → 14 `UNPROTECTED` + 1 `UNKNOWN`.
- **`backup_recovery_architecture`** (`9e42dae`) — ARCHITECTURE movement, no
  code. `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` +
  `docs/design/BACKUP_RECOVERY_CONTRACTS.md`. Driver: BackBox non-renewal in
  2027. Seven open decisions `D1`-`D7` (§13 of the architecture doc); `D2`
  resolved this session, `D1`/`D3`/`D4`/`D5`/`D6`/`D7` still open.
- **`deploy_persistent_secret_material`** (`30b0aca`) — DEV.2.2. Persistent
  volume contract for `.support_hmac.key` + CP known_hosts / PAN CA bundle.
  `main.py --persistent-secret-material-check`. `docker-compose.prod.yml`
  overlay. Unrelated to the RECOVER-track work above; landed first on this
  branch from an earlier request in the same session.

## 3. Next work

**No active build contract is open.** Pick one, write a contract for user
review first (per `AGENTS.md` lifecycle), unless it's the retroactive fix in
§4.

- **`RB.3` (CP Gaia backup)** — hard-blocked. Needs the P0
  `cp_device_interaction_safety` audit to start, and open decision `D3`
  (architecture §13) resolved. Orchestration/store/admission wiring already
  exists and is shared with `RB.2` — only the actual device call
  (`checkpoint/checkpoint_recovery_collector.py`) is missing.
- **`RB.5` (Recovery UI module + readiness scoring)** — next natural RECOVER
  step once `RB.4`'s output is worth surfacing. Touches
  `templates/index.html`/`app.js`/payload builders → **mandatory HTML render
  harness run** (`AGENTS.md` close checklist) + `tests/fixtures/uitest/`
  extension per its README growth rule. `docs/design/BACKUP_RECOVERY_CONTRACTS.md`
  §6 has the frozen `recovery_ui` payload shape.
- **PAN `RB.2` configuration-XML export** (contract §7.2, secondary/companion
  artifact to device-state) — not yet implemented, only device-state is.
- **`D1`** (what BackBox actually backs up beyond CP/PAN, by vendor/device
  count) — **product-owner action, not engineering.** Blocks the entire
  "BackBox replacement" premise; put to the user directly, don't guess.
- Standing pre-existing items (unrelated to this session, still open):
  `render_harness_happydom_pin` (P2, discovered during DEV.2.2 — happy-dom
  ≥20 removed `window.eval`, breaks the render harness silently when
  `node_modules/` is installed fresh), `html_render_performance` follow-up
  optimization.

## 3b. NOT YET DONE — real-environment / on-hardware validation

- **PAN device-state export (`RB.2`)** — IMPLEMENTED, never run against a
  real firewall. This cloud sandbox has zero device reachability, same class
  of gap as every other collector in this repo. Tested only against a
  monkeypatched fixture HTTP transport (contract §10's own rule: never a live
  device in CI).
- **PAN `software_version` gap** — `unified.json` carries no version field for
  PAN devices today (confirmed by inspection, not assumed); the PAN recovery
  collector records the honest `"unknown"` sentinel rather than inventing an
  undocumented device command to fetch one. `utils/recovery_validation.py`'s
  V3 check treats `"unknown"` as `NOT_APPLICABLE`, not a false `FAIL`. **This
  is a real, recorded gap** — closing it properly needs a small, separately
  gate-documented read command (e.g. `<show><system><info></show>` op-command
  against the direct firewall), not a quiet addition inside another build.
- **`docker compose up`** (the actual containers, not just `config`) — not
  exercised this session; only `docker compose config` (no network/build
  needed) was run for real, confirming the recovery volume topology. A full
  `docker build`/`up` was blocked in a prior session by this sandbox's own
  TLS-intercepting proxy (documented in `DEV3_1_LINUX_CONTAINER_IMAGE.md`) —
  likely still true, untested again this session.

## 4. Open risks / debt carried forward

- CP device-interaction-safety audit remains P0 — hard prerequisite for `RB.3`
  and (previously) `CE.2`.
- `D1`/`D3`/`D4`/`D5`/`D6`/`D7` (architecture §13) all still open; only `D2`
  resolved this session.
- `render_harness_happydom_pin` (P2, backlog) — will bite the first `RB.5` UI
  build if not fixed first; pin `happy-dom` explicitly or move to the
  Playwright fallback (`tools/render-harness/check_render_playwright.py`
  already exists).
- PAN `software_version` unresolved (§3b) — affects `RB.4` V3 usefulness for
  every PAN artifact until fixed.
- The `uitest` fixture is authored at the payload layer; a future `RB.5`
  `recovery_ui` payload change needs the fixture regenerated to match (growth
  rule in `tests/fixtures/uitest/README.md`).
- `scripts/pytest_one_shot.ps1` calls `py` → 3.14 without deps on the
  product owner's Windows box (`dev_python_env_tooling_friction`) — unrelated
  to this session's Linux-sandbox venv note above, both are real but distinct.
- Everything carried from before this session (CP HA runtime join precision,
  trend ledger backfill, etc.) — see `project/build_history.json` for the
  full historical list; not restated here to keep this file current, not
  exhaustive.

## 5. Exact next action

**Fresh chat recommended** (this session ran long enough to accumulate
significant context — see the user's own usage-pacing question this close).
Cold-start via `AI_START_HERE.md` → this file → `CURRENT_STATE.md` →
`project/roadmap.json` + `project/backlog.json`. First check `PR #15`'s
merge state on GitHub before starting new work on the same branch.

## 6. main merge decision + Git dispatch

- **`PR #15` open, NOT merged.** `main` is unmoved at `101f75b` (5 commits
  behind the branch). User explicitly chose "open a PR" over a direct push,
  matching this repo's own established convention (every prior `main` commit
  went through a numbered PR).
- Branch: `claude/deploy-persistent-secret-material-3rtfrs`. To continue this
  work: `git fetch origin claude/deploy-persistent-secret-material-3rtfrs &&
  git checkout claude/deploy-persistent-secret-material-3rtfrs` (or check out
  fresh if #15 has merged and a new branch is warranted).
- Do not merge #15 without the user's explicit go-ahead in that session.

## 7. Next movement / model

- `RB.3`: blocked, not actionable until the P0 audit + `D3`.
- `RB.5` (Recovery UI): `IMPLEMENTATION` at **Sonnet 5, normal** once the
  `recovery_ui` payload contract (already frozen, §6 of
  `BACKUP_RECOVERY_CONTRACTS.md`) is the target — no new architecture
  decision needed, it's deterministic build-to-contract like `RB.0`/`RB.1`/`RB.4`
  were.
- PAN `software_version` fix: small, **command-gate documentation first**
  (10-point gate per `docs/AI_DEVELOPMENT_PROTOCOL.md`) before any
  implementation — this is new device-command surface, however small.
- `D1` resolution: no engineering movement type — this is a question to put
  to the user, not a build.

## 8. Continue or fresh chat

**Start a fresh chat.** Session closed five builds across two workstreams
(DEV.2.2 + the entire RECOVER track opening); the next objective (`RB.3`
unblocking, `RB.5`, or the PAN version gap) is distinct and deserves its own
contract and clean context.

## 9. main.py / UI effect

- **This session, overall: no UI change.** Every build added CLI-only
  diagnostic/collection modes (`--persistent-secret-material-check`,
  `--restore-readiness-check`, `--recovery-store-check`, `--recovery-collect`,
  `--recovery-validate`) and backend orchestration. `templates/index.html`,
  `static/app.js`, `static/style.css` and every existing payload builder are
  byte-for-byte unchanged — confirmed by the render harness not being
  triggered as a required check for any of these builds (none touched a
  render-affecting file).
- A normal full `py .\main.py` checkpoint run today looks and behaves
  identically to before this session. The new CLI flags are additive and
  opt-in; nothing in the default pipeline changed.
- **First visible UI change will be `RB.5`** (Recovery UI module) — not yet
  built.
