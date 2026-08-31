# AI_HANDOVER

Overwrite this file at every session close. Prior versions live in git history;
a dated snapshot is also copied to `docs/history/handover/AI_HANDOVER_<date>_<build>.md`
at close time.

If a section does not apply, write `n/a` — do not delete the heading.

---

## 1. Snapshot

- Date: 2026-08-31.
- Product baseline: `0.7.7 — Compliance trend retro-fill` — AUTOMATED_VALIDATED.
  Unchanged this session.
- Engineering baseline: `DEV.1` complete; `DEV.2.1`, `DEV.2.2`, `DEV.3.1`,
  `DEV.3.2`, `DEV.3.3` all AUTOMATED_VALIDATED. Unchanged this session.
- Recovery track: `RB.0` / `RB.1` / `RB.4` AUTOMATED_VALIDATED; `RB.2`
  IMPLEMENTED (real-env owed). **`RB.3a` — AUTOMATED_VALIDATED this session
  (§2).** `RB.3b` blocked (D4 + gate write-ups + ledger + version-refusal);
  `RB.3c` blocked (`D5` + `E1`).
- **`origin/main` was at `201da3d` when this session started; local `main`
  even with it, no pull needed.** This session committed two commits on
  `feature/rb-3a-attestation` and merged `--no-ff` into `main`, then pushed
  (§6). New `origin/main` = the merge commit.
- **Full suite run this session (Windows box, no live PostgreSQL):
  `py -m pytest -q` → 804 passed / 20 skipped / 2 failed.** The 2 failures are
  the documented pre-existing/unrelated pair
  (`tests/test_phase0_6_1c_discovery_capability_ui.py::test_run_html_export_embeds_discovery_payload_without_leftover_placeholder`,
  `tests/test_phase0_7_5_compliance_trend.py::test_checkpoint_render_appends_one_record`)
  — both pass in isolation (test-order pollution), zero new failures. `+33`
  RB.3a tests. Evidence in `pytest_result.log`.
- Repository privacy gate: **no finding in tracked source.** The gate reports
  FAIL locally *only* because the test run leaves gitignored `data/` + `logs/`
  in the tree; deleting `rm/git clean` of those was blocked by this box's
  command classifier, so the clean PASS/0 re-run is owed —
  `git clean -xdf -- data logs && py -m main --repository-privacy-check`.
- **Toolchain:** ran on the user's Windows box. `py` works directly
  (Python 3.14 default — note `json.load(open(...))` needs
  `encoding="utf-8"` for the `project/*.json` files, they contain `§`/`—`).
  Full suite ~138 s.

## 2. What this session did

**RB.3a — CP Gaia backup/snapshot attestation. IMPLEMENTATION against the
frozen contract (`docs/history/phase/RB_3A_CP_GAIA_BACKUP_ATTESTATION.md`,
§7.5 gate signed off 2026-08-31). AUTOMATED_VALIDATED.**

New / changed:

- **`checkpoint/checkpoint_recovery_attestation.py`** (new) — one SSH session
  per physical endpoint, reusing
  `configuration/checkpoint_config_probe._connect` / `_run_exec` /
  `ProbeTarget` and the strict-host-key preflight verbatim (no new credential,
  no new transport). Runs exactly the frozen tuple
  `_ATTESTATION_COMMANDS = ("show backups", "show snapshots")` behind a
  pre-wire `_wire_forms()` guard that raises on anything else (A4 / AC-6).
  `parse_gaia_listing()` is a bounded, fail-closed parser: emits
  `{class, age_days, source}` records, **discards the artifact name** (A5 /
  AC-5), `age_days` is `None` when no unambiguous UTC calendar date parses
  and the record is still kept (A6 / AC-1), a CLI error / permission-denied
  yields zero records. `classify_target()` is the A8 platform gate — local,
  no device contact — returning `"unsupported"` for `gaia_embedded` (Spark),
  `"supported"` for everything else incl. unknown platform.
- **`utils/recovery_collect.py`** — `RecoveryAttester` protocol
  (`classify_target` + `attest`), `RecoveryAttestationOutcome` /
  `RecoveryAttestationResult` (`.as_attestation_map()` →
  `entity_id -> records`), and `run_recovery_attestation()` — a **sibling** of
  `run_recovery_collection`, deliberately NOT a `RecoveryCollector` (A2: an
  attestation has no plaintext, so `collect() -> (bytes, meta)` +
  unconditional `write_artifact` does not fit). Reuses
  `select_recovery_targets` (an unresolvable explicit `--recovery-gateways`
  entry is a request-time `RecoveryCollectionError` before any contact), the
  `run_under_admission` hook, and one-endpoint-failure-does-not-abort-the-batch
  semantics. VSX `<device>__vsid_<id>` entities are never contacted and never
  credited (A3); explicit VS targets are reported `skipped_virtual_system`.
- **`main.py`** — `--recovery-attest` (thin dispatch mirroring
  `--recovery-collect`, CP-only, reuses `--recovery-gateways`; A8 platform map
  read from `cp_config_telemetry.json` when a prior `--cp-config-collect`
  produced it, else empty → everything attested normally). Writes
  `data/state/recovery_attestations.json`
  (`securityexpert-recovery-attestations-v1`). `--restore-readiness-check` now
  reads that file via `_load_recovery_attestations()` and passes it to
  `compute_restore_readiness(attestations=)`; a corrupt/absent file degrades
  to "no attestations", never an error (AC-9, same posture as
  `compliance_history`). Stale `--recovery-vendor` help string corrected (A10).
- **`utils/collection_executor.py`** — comment only: `"recovery-attest-cp"` is
  deliberately **not** in `ALLOWLISTED_WORKFLOWS` (A9). A scheduler policy
  naming it is refused at load (AC-10) — no code change, the allowlist already
  fails closed on unknown names.
- **`docs/design/BACKUP_RECOVERY_CONTRACTS.md`** — amendment **C1** (§5
  `attested_not_held[].age_days` is nullable) and **C2** (§10.3 gains the
  attester row + the RecoveryAttester-vs-RecoveryCollector reasoning). C3 was
  already §7.5 points 5-7.
- **`tests/test_rb3a_recovery_attestation.py`** (new) — 33 tests, AC-1…AC-10
  plus CLI-surface guards.
- Metadata: `CURRENT_STATE.md`, `project/backlog.json` (`native_backup`,
  `on_hardware_real_env_validation`), `project/roadmap.json` (`now_next`),
  `project/build_history.json` (RB.3a entry). **These four files also carry
  the prior session's uncommitted "server productization / modularization
  review" metadata** — it could not be split from the RB.3a hunks, so it
  landed in the RB.3a commit; the review's own doc + feature-registry +
  AI_HANDOVER §3a landed in the commit before it (§6).

**`utils/restore_readiness.py` is unchanged** — the `attestations=` parameter
already existed; wiring a producer to it was the whole point.

### Implementation choices worth knowing (not in the contract)

- **`management_ip` source.** `run_recovery_attestation` reads
  `target.row["management_ip"]` (the `unified.json` row), mirroring the PAN
  collector. Real CP `unified.json` rows may not always carry one (the config
  collector resolves IPs from `cp_telemetry.json` instead). If a row has no
  address the endpoint is recorded `failed` / `management_ip_unavailable` and
  the batch continues — real-env validation will show whether this needs a
  `cp_telemetry.json` fallback.
- **Shell mode.** `attest()` tries the bare `show ...` form first, falls back
  to `clish -c '...'` once (the §7.5 "1 retry"), and settles the whole session
  on whichever worked. No `show hostname` / shell-probe command is sent — only
  the two frozen verbs ever reach the wire.
- **A8 platform map** is `{entity_id: family}` injected at attester
  construction. Today its only populated source is `cp_config_telemetry.json`;
  there is still no live capability-store→profile wiring anywhere in the repo
  (see the `cp_unknown_platform` backlog note), so on a fleet that never ran
  `--cp-config-collect`, Spark gating is inert and every endpoint is attested.
  That is A8-compliant ("unknown platform is attested normally") but means the
  Spark-skip only bites once config telemetry exists.

## 3. Next work

**`RB.3b` is the next recovery build but is BLOCKED.** Do not open it as an
implementation build. What it needs first (all design/contract work, none of
it device-touching, none of it blocked):

1. **`D4` — backup credential identity.** A decision doc: the Gaia
   `add backup local` path must NOT fall back to the collection credential.
   Where does a distinct backup credential come from, how is it stored
   (DEV.2.2 secret-material pattern), how does admission route it.
2. **§7.7 gate entry** — `/var/log` free-space read (class `read`), with its
   literal Gaia command string. §7.3 point 12 requires it; §7 never wrote it.
3. **§7.8 gate entry** — backup-file deletion (class `operational-write`),
   literal command string. **Deletes only the exact name this run created —
   never a pattern, never a name taken from a `show backups` listing.** §7.3
   point 13 requires it.
4. **Durable per-endpoint operational-write ledger.** §7.3 point 6's "1 per
   24 h hard-enforced by the admission coordinator" is not currently possible
   — `CollectionCoordinator` is process-local and in-memory. Design a durable
   per-endpoint ledger on the DEV.3.3 evidence backend, fail-closed on an
   unreadable ledger.
5. **Refuse to store a version-unknown CP artifact.** PAN `software_version:
   "unknown"` is honest; for a version-locked Gaia backup it is a latent
   unrestorable-artifact bug (sits at V2 forever). Tightens contracts §3
   frozen rule 5.

The RB.3b contract (`docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md`)
already records 1–5 as the open items; this session did not touch it.

**Parallel local option (not blocked):** `codebase_modularization` +
`browser_render_boundary` from the productization review
(`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`) —
behavior-preserving module extraction with render / privacy / regression gates
green per extraction. No server dependency.

## 3a. Architecture planning carried forward

A local-only productization and modularization review is recorded in
`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`, with
tracked backlog, feature-registry, roadmap and history entries. It preserves
the current one-worker/static-report model; it does **not** authorize a
premature server/API/platform rewrite.

Doable locally, independently of RB.3b: remove the dormant remote-cleanup
helper, harden the browser rendering boundary, and extract responsibility-owned
frontend/workflow/collector modules while preserving behavior and passing
render, privacy and regression gates. Server-only gates are OIDC/RBAC, strict
CP/PAN trust, report-only publication storage, non-root restricted containers,
reviewed migrations and roles, release assurance, and off-host recovery key
custody plus a restore drill.

## 3b. NOT YET DONE — real-environment / on-hardware validation

- **`RB.3a` (this session) — owed.** No live CP device is reachable from this
  workspace; automated tests exercise fixtures only. Per `AGENTS.md` this
  build is `AUTOMATED_VALIDATED`, never `DONE`. Added to
  `on_hardware_real_env_validation` in `project/backlog.json`: run
  `py .\main.py --recovery-attest` against a real Gaia gateway and confirm
  (a) both commands run under Clish in one reused session, (b) the parser
  handles the real listing format on that release — a new format is an
  expected additive outcome, not a failure — (c) a Spark endpoint gets zero
  commands, (d) `recovery_attestations.json` then flips the right devices to
  `PARTIAL` in `--restore-readiness-check`. The contract names parser format
  drift as the top risk.
- Carried forward unchanged: `DEV.3.3` / `DEV.3.2` multi-container runs;
  `RB.2` (PAN device-state export) never run against a real firewall;
  everything else under `on_hardware_real_env_validation`.

## 4. Open risks / debt carried forward

- **RB.3a `management_ip` source** and **A8 platform-map population** — see
  §2 "Implementation choices". Both are honest-degradation, not bugs, but
  real-env is where they get exercised.
- Unchanged from prior handovers: mixed-backend fleet; the two DEV.3 knobs
  not implying each other; no backfill on backend switch; `D1`, `D4`–`D7`
  open (`D3` resolved 2026-08-31); PAN `software_version` unresolved (now also
  an RB.3b item, #5 above); `DEV.3.2` lock-key persistence dependency;
  regex-linter best-effort posture; test-run repo-dir writes (`data/`,
  `logs/`, `pytest_result.log`).
- **Clean privacy-gate re-run is owed** on a tree without the gitignored
  `data/`/`logs/` (this box blocked the delete; not a finding on tracked
  source).

## 5. Exact next action

**Start a fresh chat.** Read `AI_START_HERE.md` → this file →
`CURRENT_STATE.md`. Then either:

- **RB.3b unblocking prep** (recommended): read
  `docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` and
  `docs/design/BACKUP_RECOVERY_CONTRACTS.md` §7.3 / §7.6, and produce the
  five deliverables in §3 (D4 decision doc, §7.7 + §7.8 gate entries with
  literal command strings, the durable operational-write ledger design, the
  version-unknown refusal). This is `CONTRACT` / `ARCHITECTURE` work — it does
  not touch a device and is not blocked. Movement: see §7.
- **or** the modularization work in §3a — `ARCHITECTURE` + `IMPLEMENTATION`,
  behavior-preserving, gates green per extraction.

## 6. main merge decision + Git dispatch

- **Merged and pushed this session, at the user's explicit request** ("pull
  and merge if no overwrite present"). `git fetch --all` first — `origin/main`
  had not advanced past `201da3d` since the branch point, so no overwrite risk
  and nothing to pull.
- Two commits on `feature/rb-3a-attestation`, then `git checkout main` +
  `git merge --no-ff feature/rb-3a-attestation` + `git push origin main`:
  1. `docs(architecture): server productization & modularization review` —
     the prior session's uncommitted planning deliverable (its own doc,
     `feature_registry.json`, `AI_HANDOVER` §3a). Kept per the user's
     2-commit choice; it was a finished artifact staged for `main`.
  2. `feat(recovery): RB.3a — CP Gaia backup/snapshot attestation` — all
     RB.3a code/tests/contract, plus the four metadata files that carry BOTH
     bodies of work (could not be split cleanly).
- `feature/rb-3a-attestation` and `feature/rb-3-contracts` are fully
  contained in `main`; safe to delete whenever, no action taken.
- Standing priority 4: human-initiated — the merge waited for the explicit
  instruction before it ran.

## 7. Next movement / model

- **RB.3b unblocking prep (§3 items 1–5):** `Sonnet 5, extended thinking
  (high)`. New network-device command-gate entries (§7.7 / §7.8), a
  credential-identity decision, and a durable safety-ledger design are exactly
  the "security boundary + vendor-semantic + new scope" combination CLAUDE.md
  routes to extended thinking. The eventual device-touching RB.3b
  implementation step (once gated) also earns extended thinking — a real
  `/var/log`, not a fixture.
- **Modularization (§3a):** `ARCHITECTURE` at `Sonnet 5, extended thinking`
  for the extraction plan / seam decisions; `Sonnet 5, normal` for each
  mechanical, gate-checked extraction.
- **RB.3c:** do not open. When `E1` / `D5` are answered, `ARCHITECTURE` at
  `Sonnet 5, extended thinking` to re-cut §7.6.
- **RB.3a real-env validation:** whenever hardware is available — no model
  call, it is an operator run producing SAFE-SUMMARY evidence.

## 8. Continue or fresh chat

**Fresh chat.** RB.3a is landed and closed. RB.3b prep and the modularization
work are both self-contained from their own docs; nothing in this session's
context (parser tolerance calls, the SSH-session shape) is needed for either.

## 9. main.py / UI effect

- **New CLI flag `--recovery-attest`** (CP-only; reuses `--recovery-gateways`).
  Writes `data/state/recovery_attestations.json`. Collects nothing, changes no
  device state, writes nothing to the recovery store.
- **`--restore-readiness-check` behaviour change:** it now consumes
  `recovery_attestations.json` when present — a device with a device-reported
  backup/snapshot and no held artifact moves from `UNPROTECTED` to `PARTIAL`
  (`evidence_basis: device_attestation`), never to `READY`. With no
  attestation file (the default until someone runs `--recovery-attest`) the
  output is byte-for-byte what it was before.
- **No payload builder / `templates/` / `static/` change.** The report render
  is unaffected; RB.5 (Recovery UI) is untouched. A normal `py .\main.py`
  checkpoint run looks and behaves exactly as before.
