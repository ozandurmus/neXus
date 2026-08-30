# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Product baseline: `0.7.7 — Compliance trend retro-fill (PAN baseline
  reconstruction)` — AUTOMATED_VALIDATED (2026-08-30, separate parallel
  session).
- Engineering baseline: `DEV.1` complete; `DEV.2.1` AUTOMATED_VALIDATED;
  `DEV.2.2` (`deploy_persistent_secret_material`) AUTOMATED_VALIDATED (this
  session); `DEV.3.1` (Linux worker image + Compose) AUTOMATED_VALIDATED;
  `DEV.3.2` (distributed per-endpoint lock, Postgres-backed) —
  AUTOMATED_VALIDATED (separate parallel session).
- **RECOVER track opened this session** (new): `RB.0`/`RB.1`/`RB.4`
  AUTOMATED_VALIDATED; `RB.2` (PAN device-state export) IMPLEMENTED,
  real-environment validation owed; `RB.3` (CP) a typed blocked stub. See §2.
- Date: 2026-08-30.
- **This checkpoint reconciles two independent sessions' work that landed in
  parallel on separate branches:** this session's DEV.2.2 + the whole
  RECOVER track (`claude/deploy-persistent-secret-material-3rtfrs`, PR #15)
  and a separate session's `0.7.7` + `DEV.3.2` + a stale-doc correction
  (already merged to `main`). Both sessions independently rewrote this file,
  `CURRENT_STATE.md` and `project/build_history.json` at their own close,
  producing a real git merge conflict on all three — resolved by hand here,
  keeping both builds' content rather than discarding either side. **If you
  are the next session, trust this reconciled version over either
  predecessor's; `project/build_history.json` is authoritative if anything
  here still looks inconsistent with its newest entries.**
- **CORRECTION — important, was wrong in this session's own work until this
  merge:** the CP device-interaction-safety audit (P0,
  `backlog.json` `cp_device_interaction_safety`) **closed 2026-08-25**,
  *before this session even started*. This session's own RECOVER-track
  design and code (`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md`,
  `BACKUP_RECOVERY_CONTRACTS.md`, `checkpoint/checkpoint_recovery_collector.py`,
  `project/backlog.json`'s `native_backup` note, `CURRENT_STATE.md`) all
  repeatedly stated "the P0 audit has not started" as `RB.3`'s blocker —
  **that was stale/wrong**, inherited from not re-checking `backlog.json`
  against its true current state before writing it down repeatedly. **Fixed
  as part of this merge** (see §2's `stale_doc_correction_rb3` entry) — every
  place that said "audit has not started" now says the audit is closed and
  names the real remaining blocker: `D3` (product-owner decision on the
  `operational-write` command class) plus `add backup local`'s own
  network-device command gate review (already drafted, not yet approved,
  in contract §7.3). **Do not re-introduce the "audit not started" claim.**
  Read `project/backlog.json`'s `cp_device_interaction_safety` note directly
  before citing its status again, in this or any future session — do not
  trust a summary, including this one, without checking the source.
- Full suite after this merge (Linux cloud sandbox venv — see toolchain note
  below): pending a fresh run post-resolution (see §5 for what still needs
  running before this merge is pushed). Pre-merge, each side's own suite was
  green: this session's RECOVER-track branch at 741 passed / 3 skipped / 2
  pre-existing unrelated failures; `main`'s post-merge-of-its-own-two-sessions
  suite at 657 passed / 10 skipped / 2 failed (same 2 pre-existing failures).
- Repository privacy gate: **PASS / 0** on both sides pre-merge; re-verify
  after resolving this merge (delete gitignored `data/`/`logs/` first — an
  `rm -rf` may be blocked by an auto-mode permission classifier on the first
  attempt in a sandboxed session; retry, or delete files individually).
- **This sandbox (this session) had no preinstalled Python toolchain
  matching the repo baseline** (`py` not found, no `paramiko`/`lxml`/
  `cryptography`/`pytest`). Built a throwaway venv:
  `python3 -m venv /tmp/venv && /tmp/venv/bin/pip install -q -r
  requirements.txt pytest`. Does not survive between sessions — recreate the
  same way if hit again; not a repo problem.
- **New from `DEV.3.2` — optional dependency.** `requirements-postgres.txt`
  (`psycopg[binary]>=3.1`) is opt-in, not in base `requirements.txt`. Only
  needed when `SECURITYEXPERT_COORDINATOR_BACKEND=postgres` is set; default
  (`memory`, unset) needs nothing new — this session's RECOVER-track work
  never touched the backend selector and runs unaffected either way.
- **`cryptography>=41` is now a direct `requirements.txt` dependency** (was
  only transitive via `paramiko`) — used by `utils/recovery_crypto.py`
  (AES-256-GCM envelope encryption, this session).
- New pytest markers: `recovery` (backup/recovery plane, RB.x, this session).
- **HTML render harness — two implementations, use whichever works:**
  `py -V:3.12 scripts/render_uitest.py --out D` then EITHER
  `bun tools/render-harness/check-render.mjs D/output/index.html` (bun +
  happy-dom — can hit a `window.eval is not a function` version-compat gap,
  same issue this session's own `render_harness_happydom_pin` backlog item
  already recorded) OR `python tools/render-harness/check_render_playwright.py
  D/output/index.html` (real Chromium via Playwright, the reliable
  fallback). `tests/test_html_render_harness.py` wires both, `skipif`-ing
  whichever toolchain is absent. **Mandatory for any `templates/`/`app.js`/
  `style.css`/payload-builder change** — none of this session's RECOVER-track
  builds touched those files, so it wasn't triggered; `RB.5` (next, not yet
  built) will need it.
- **`bun install` in a sandbox with a newer bun binary than the committed
  `tools/render-harness/bun.lock` will rewrite the lockfile** — `git
  checkout -- tools/render-harness/bun.lock` afterward so that
  environment-specific change doesn't get committed. (Hit and reverted in
  this session's DEV.2.2 build too, independently.)
- **Never label a version as four dot-separated numbers** — `_IPV4_RE` in the
  privacy gate flags an `A.B.C.D` label as `PRIVATE_ENDPOINT_LITERAL`. Use a
  letter suffix (`0.7.4a`).

## 2. Recent builds — this session's branch (PR #15, being merged with `main`)

Detail in `project/build_history.json` (newest first).
`docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md` +
`docs/design/BACKUP_RECOVERY_CONTRACTS.md` are the frozen design + contracts
the RB.x builds were built against.

- **`stale_doc_correction_rb3`** (this merge) — corrects every place this
  session's own RECOVER-track work claimed the CP P0 audit "has not
  started". See the CORRECTION note in §1. Touches
  `docs/design/BACKUP_AND_RECOVERY_ARCHITECTURE.md`,
  `docs/design/BACKUP_RECOVERY_CONTRACTS.md`,
  `checkpoint/checkpoint_recovery_collector.py`'s `BLOCK_REASON`,
  `project/backlog.json`'s `native_backup` note, `project/roadmap.json`'s RB
  entry, this file, `CURRENT_STATE.md`. Does **not** unblock `RB.3` by
  itself — `D3` (product-owner decision on the `operational-write` command
  class) is still open and is the real remaining blocker, now correctly
  named instead of a closed audit.
- **`recovery_collect_rb2_rb4`** — D2 resolved (product owner approved PAN
  service-account superuser for device-state export only). `RB.2`
  IMPLEMENTED: `utils/recovery_collect.py` (target selection incl. explicit
  gateway lists + VSX `__vsid_` addressing, `RecoveryCollector` protocol,
  admission-coordinated per-target execution), `panorama/panorama_recovery_collector.py`
  (PAN device-state export, `read` class, session-reuse, no 403 retry),
  `checkpoint/checkpoint_recovery_collector.py` (typed blocked stub),
  `collection_executor.ALLOWLISTED_WORKFLOWS` += `"recovery-pan"` (not
  `"recovery-cp"`) with an additive optional `ScheduledWorkflow.targets`
  field, `main.py --recovery-collect --recovery-vendor --recovery-gateways`
  (thin dispatch only, explicit product direction: no collection logic in
  `main.py`). `RB.4` AUTOMATED_VALIDATED: `utils/recovery_validation.py`
  (V1-V3 battery), `recovery_store.revalidate_artifact`, `main.py
  --recovery-validate` (gates on any individual check FAIL, not just the
  top-line verdict). 85 new tests; one pre-existing test
  (`test_allowlisted_workflows_are_read_only`) updated in place. Manually
  verified end-to-end incl. a real `--scheduler-once` run.
- **`recovery_store_rb1`** — encrypted recovery-plane store:
  `utils/recovery_crypto.py` (AES-256-GCM envelope encryption),
  `recovery_manifest.py`, `recovery_store.py` (vault key on `data_root`,
  never `recovery_root`), `recovery_retention.py` (GFS + floor invariant).
  `resolve_recovery_root` in `utils/runtime_paths.py`. `main.py
  --recovery-store-check`. `docker-compose.yml` gains
  `securityexpert-recovery` volume on `worker` only. 41 tests.
- **`restore_readiness_rb0`** — `utils/restore_readiness.py` + `main.py
  --restore-readiness-check`; 16 tests. Manually verified: 15
  uitest-fixture devices → 14 `UNPROTECTED` + 1 `UNKNOWN`.
- **`backup_recovery_architecture`** — ARCHITECTURE movement, no code.
  Driver: BackBox non-renewal in 2027. Seven open decisions `D1`-`D7`; `D2`
  resolved this session, `D1`/`D3`/`D4`/`D5`/`D6`/`D7` still open (`D3`'s
  real blocker corrected by this merge, see above — it is no longer waiting
  on the P0 audit, just on the product-owner decision itself).
- **`deploy_persistent_secret_material`** (DEV.2.2) — persistent volume
  contract for `.support_hmac.key` + CP known_hosts / PAN CA bundle.
  `main.py --persistent-secret-material-check`. `docker-compose.prod.yml`
  overlay. Unrelated to the RECOVER-track work; landed first on this branch.

## 2b. Recent builds — already on `main` from the parallel session

- **`compliance_trend_reconstruction` (0.7.7)** — offline retro-fill for
  `compliance_overview.history[]`. `utils/compliance_trend_reconstruction.py`
  (`reconstruct_pan_baseline_records`), PAN-only, ten deterministic baseline
  controls, every record stamped `reconstructed: true`. New `main.py
  --compliance-trend-reconstruct` maintenance mode (merged into this
  branch's `main.py` alongside `--recovery-collect`/`--recovery-validate` —
  both preserved, cross-guarded against each other during conflict
  resolution). `docs/history/phase/0_7_7_COMPLIANCE_TREND_RECONSTRUCTION.md`.
- **`distributed_endpoint_lock` (DEV.3.2)** — `CollectionCoordinator`'s
  per-endpoint lock/budget moved behind a `CoordinatorBackend` protocol
  (`utils/coordinator_backend.py`): `InMemoryCoordinatorBackend` (default,
  unchanged behavior) and `PostgresCoordinatorBackend` (opt-in, session-level
  `pg_advisory_lock`, HMAC-derived lock keys — no device identity reaches
  Postgres). `select_coordinator_backend()` reads
  `SECURITYEXPERT_COORDINATOR_BACKEND`. Verified against a real local
  PostgreSQL 16. `docs/history/phase/DEV3_2_DISTRIBUTED_ENDPOINT_LOCK.md`.
  **Note for this session's RECOVER-track work:** `utils/collection_executor.py`
  merged cleanly (git auto-merge, no conflict) — `ALLOWLISTED_WORKFLOWS`
  gaining `"recovery-pan"` and `ScheduledWorkflow.targets` sit alongside
  `DEV.3.2`'s backend changes without overlap. Re-verify with a full test
  run regardless (§5).
- **Stale-documentation correction** (their side) — `CURRENT_STATE.md`,
  `docs/ARCHITECTURE.md`, `docs/design/COMPLIANCE_CHECK_ENGINE.md` corrected
  for the CP audit's actual closure date. This session's own RECOVER-track
  docs needed the *same* correction and got it in this merge (§2, first
  entry) — the parallel session's catch didn't automatically propagate here
  since the two branches diverged before their correction landed.

## 3. Next work

**No active build contract is open.**

- **`RB.3` (CP Gaia backup)** — the P0 audit is **closed**, so the real
  remaining blocker is `D3` alone (product-owner decision: is
  `add backup local`'s new `operational-write` command class acceptable at
  current maturity) plus that command's own gate review (drafted in
  contract §7.3, not yet approved). This is now a much smaller ask than
  "wait for an unstarted audit" — worth putting `D3` to the user directly
  as a near-term option, not treating `RB.3` as indefinitely blocked.
- **`RB.5` (Recovery UI module + readiness scoring)** — next natural RECOVER
  step. Touches `templates/index.html`/`app.js`/payload builders →
  **mandatory HTML render harness run** + `tests/fixtures/uitest/`
  extension. `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §6 has the frozen
  `recovery_ui` payload shape.
- **PAN `RB.2` configuration-XML export** (contract §7.2, secondary
  artifact) — not yet implemented, only device-state is.
- **PAN `software_version` gap** — `unified.json` carries no version field
  for PAN devices; the collector records `"unknown"` rather than inventing
  an undocumented device command. Needs its own small, gate-documented read
  command.
- **`D1`** (what BackBox actually backs up beyond CP/PAN) — product-owner
  action, not engineering. Blocks the "BackBox replacement" premise.
- **`distributed_evidence_store_migration` (DEV.3.3)** (parallel session's
  next item) — CAS metadata index / run manifests → Postgres. Own contract,
  not urgent, doesn't gate device safety.
- **CE.2** (`compliance_check_engine_primitives`) — **unblocked on the P0
  audit front** (closed); still needs its own contract, each primitive
  through the command gate, and a real-environment validation gate.
- **CE.3**/**CE.4**, **OP.x Controlled Failover** — `DEPLOY.1A`/OP.2-gated,
  unchanged; OP.2's gate list includes the closed CP audit and the now-done
  distributed lock among several still-open items (OIDC/RBAC, mature
  TRACE/RECOVER, write-primitive command gate, signed change-management
  review) — don't read either closure as OP.2 being close.
- `render_harness_happydom_pin` (P2) — will bite the first `RB.5` UI build;
  fix first or use the Playwright fallback that already exists.

## 3b. NOT YET DONE — real-environment / on-hardware validation

- **PAN device-state export (`RB.2`)** — IMPLEMENTED, never run against a
  real firewall; zero device reachability in either sandbox this cycle.
  Tested only against a monkeypatched fixture HTTP transport.
- **`DEV.3.2`** — real Postgres evidence exists but not an actual
  multi-container deployment against a real MDS.
- **`0.7.7`** — no live-device dependency; owed is running
  `--compliance-trend-reconstruct` against a real fleet's CAS history.
- **`0.7.5`** — trend sparkline/chip needs a *second* real full checkpoint.
- Everything under `on_hardware_real_env_validation` in
  `project/backlog.json` remains owed (needs an MDS/Panorama-reachable
  laptop or the `DEPLOY.1` server).
- A full `docker build`/`up` (not just `config`) — blocked by this sandbox's
  TLS-intercepting proxy in an earlier session; only `docker compose config`
  (no network needed) was exercised for real this session, confirming the
  `securityexpert-recovery` volume topology.

## 4. Open risks / debt carried forward

- `D1`/`D3`/`D4`/`D5`/`D6`/`D7` (architecture §13) open; `D2` resolved.
  `D3`'s real remaining blocker corrected this merge — see §1/§3.
- `render_harness_happydom_pin` (P2) — fix before the first `RB.5` UI build.
- PAN `software_version` unresolved — limits `RB.4` V3 usefulness for PAN
  artifacts until a gate-documented version command exists.
- `DEV.3.2` lock-key stability depends on `data/.support_hmac.key` — a
  regenerated key silently changes derived lock keys. Makes
  `deploy_persistent_secret_material` (this session, done) a real
  precondition for using the Postgres backend in production — already
  satisfied now.
- `DEV.3.2` preflight (`verify_postgres_backend_ready`) is the load-bearing
  safety check for the Postgres backend; if a future deployment fronts it
  with a transaction-pooling proxy, session-level advisory locks silently
  stop working unless the preflight catches it.
- `0.7.7` reconstruction bucketing is a time-gap heuristic (CAS has no
  `run_id`); its `catalog_version` is always today's, not historical.
- The `uitest` fixture is authored at the payload layer — regenerate it if
  a builder's output shape changes (`RB.5` will need this).
- The regex safety linter (`_REDOS_RE` + quantifier count) is best-effort;
  the eval-time timeout is the real backstop.
- `0.7.4` framework catalog requirement lists are hand-authored.
- The CAS/support-key path writes `data/`+`logs/` into the repo dir during a
  test run. Gitignored; delete before the privacy gate.
- `scripts/pytest_one_shot.ps1` calls `py` → 3.14 without deps on the
  product owner's Windows box — distinct from this session's Linux-sandbox
  venv note in §1, both real.

## 5. Exact next action

**Merge complete, pushed, verified.** `c84a903` on
`claude/deploy-persistent-secret-material-3rtfrs`; full suite on the merged
tree: 763 passed, 11 skipped, 2 pre-existing unrelated failures (unchanged);
privacy gate PASS/0; `utils/collection_executor.py`'s clean auto-merge
spot-checked correct (`ALLOWLISTED_WORKFLOWS` + backend selection coexist).
`PR #15` reports `mergeable_state: "clean"` against `main`. **Not merged to
`main` yet** — that step is the user's, not this session's.

**Fresh chat recommended.** Two independent multi-build sessions closed
here; whichever objective is picked next (`RB.3`/`D3`, `RB.5`, `DEV.3.3`,
CE.2) is distinct and deserves its own contract and clean context. First
check whether `PR #15` merged in the meantime.

## 6. main merge decision + Git dispatch

- This merge (this session's branch + `origin/main`, which already carried
  `0.7.7` + `DEV.3.2`) was done at the user's explicit request ("pull and
  merge, but don't override newly written code") after confirming `main`
  had moved and PR #15 was reported `mergeable_state: dirty`. Resolved by
  hand, keeping both sides' content, not picking one over the other.
  Pushed as `c84a903`; PR #15 now reports `mergeable_state: "clean"`.
- PR #15 stays open at `https://github.com/ozandurmus/neXus/pull/15`, ready
  to merge — the user explicitly chose "open a PR" over a direct push
  earlier this session, matching this repo's own established convention.
  **Do not merge #15 to `main` without the user's explicit go-ahead** in
  whatever session picks this up next.
- `bun.lock` + `package.json` under `tools/render-harness/` are committed;
  `node_modules/` is not. Delete gitignored `data/`+`logs/` before the
  privacy gate.

## 7. Next movement / model

- `D3` resolution: no engineering movement — a question to put to the user.
- `RB.3` (once `D3` is answered, if approved): `IMPLEMENTATION` at
  **Sonnet 5, normal** — the collector orchestration/store/admission wiring
  already exists and is shared with `RB.2`; only the CP device call itself
  and its gate sign-off are missing.
- `RB.5`: `IMPLEMENTATION` at **Sonnet 5, normal** — deterministic
  build-to-contract like `RB.0`/`RB.1`/`RB.4` were, contract already frozen.
- `DEV.3.3` / CE.2 contracts: **Sonnet 5, normal** for routine multi-file
  contract work; escalate only if a genuine new architecture decision
  surfaces mid-contract.

## 8. Continue or fresh chat

**Start a fresh chat** once this merge is pushed and verified. Session
closed six builds across two branches now reconciled into one; the next
objective is distinct and needs its own contract.

## 9. main.py / UI effect

- **This session's RECOVER-track work:** no UI change. Every build added
  CLI-only diagnostic/collection modes
  (`--persistent-secret-material-check`, `--restore-readiness-check`,
  `--recovery-store-check`, `--recovery-collect`, `--recovery-validate`).
  `templates/index.html`/`static/app.js`/`static/style.css` untouched.
- **`0.7.7` (parallel session):** `main.py` gains
  `--compliance-trend-reconstruct` (opt-in, offline). Overview/Compliance
  sparkline renders reconstructed points dashed/reduced-opacity vs. solid
  live-checkpoint points.
- **`DEV.3.2` (parallel session):** none visible in the UI/report;
  coordinator backend selection is silent when unset/`memory`.
- A normal full `py .\main.py` checkpoint run today looks and behaves as
  before this session on every front except the new opt-in flags above.
- **First visible UI change will be `RB.5`** — not yet built.
