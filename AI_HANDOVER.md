# AI_HANDOVER

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase doc.
Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-08-31.
- Product baseline `0.7.7` — AUTOMATED_VALIDATED. Engineering `DEV.3.3` —
  AUTOMATED_VALIDATED. Both unchanged this session.
- **Active build: `RB.3b` — CP Gaia system backup collection.** Contract frozen
  and signed off. **Steps 2–5 implemented + C6 test written this session.
  Steps 6–7 owed.** Status `in_progress` — not IMPLEMENTED (step 6 wiring +
  the mandatory watched real R81.10/R81.20 run are still owed).
- `main` at `7e19549` (unchanged — this session's work is **uncommitted** in the
  working tree; branch + commit owed, human-controlled).

## 2. What changed this session (step 5 — the device core)

- `checkpoint/checkpoint_recovery_collector.py` — the offline-gate stub's
  `collect()` now runs the full one-SSH-session device sequence:
  `precheck` (offline refusals) → **ledger read** (inside the admitted section:
  `cleanup_failed` newest entry ⇒ INELIGIBLE; entry `< 24 h` ⇒
  `RecoveryCollectionSkipped`, zero device contact; unreadable ⇒ blocked) →
  `_probe_target` → open session → **§7.7 free-space read** (frozen forms
  `clish -c 'show diskspace'` / `df -P /var/log`; `UNKNOWN` or `< 3×` ⇒ abort,
  no command) → **§7.3 `add backup local`** (frozen forms, 900 s, no retry;
  bare form tried only after a clean CLI rejection) → parse the archive name
  **from that command's own output only** (`_SAFE_ARCHIVE_NAME_RE`,
  `register_sensitive_value`) → SFTP `stat` (device-reported size) → SFTP
  `getfo` **into memory** (no temp file, B6) → size verify → **`write_artifact`
  here, before the delete** (correctness rule 1) → **§7.8 delete** exactly that
  name (`rm -f -- /var/log/CPbackup/backups/<name>` primary, `clish -c 'delete
  backup <name>'` secondary; confirmed absent by SFTP `stat`; 1 retry) →
  `finally`: `ledger.record_execution` **iff `add backup local` was sent**
  (`completed` / `failed` / `cleanup_failed`), then `session.close()`. Any
  failure after `add backup local` still runs the delete (`_cleanup_tail`); an
  unconfirmable delete ⇒ `cleanup_failed` ⇒ endpoint INELIGIBLE next run.
  New: `BackupSshSession` transport wrapper (wraps
  `checkpoint_config_probe._connect` / `_run_exec` + paramiko SFTP;
  `session_factory` is injectable for fixtures), `_DeviceOutcome`, frozen
  command tuples, constructor gains `ledger` / `recovery_paths` / `vault_key`
  / `vault_key_id` / `run_id` / `session_factory` / `clock` (all optional;
  absent ledger or store ⇒ `collect()` fails closed before any device contact).
- `utils/recovery_collect.py` — `RecoveryCollectionSkipped` (new; status
  `"skipped"`, not counted in `failed_count`; `skipped_count` added);
  `build_recovery_device_block()` extracted (shared by the orchestrator and the
  CP collector's own `write_artifact` call); `run_recovery_collection` catches
  `RecoveryCollectionSkipped` and honours `meta["stored_artifact_id"]` (a
  collector that persisted its own artifact is not re-stored).
- Tests: `tests/test_rb3b_cp_backup_device_core.py` (new, 24 tests — AC-1…AC-6,
  AC-12, AC-14, C6, §9.13 (a)(b)(c)(f)(g), the `skipped` outcome, fail-closed).
  `tests/test_rb3b_cp_backup_collector.py` — the one stale "step 5 deferred"
  assertion rewritten to the fail-closed-without-ledger behaviour.
- **No `main.py` change.** No CLI flag, no runtime behaviour change (step 6).

## 3. Exact next action

**RB.3b step 6** (`Sonnet 5, normal`) — wire the collector into `main.py`'s
existing `--recovery-collect --recovery-vendor checkpoint` branch (currently
constructs `CheckpointGaiaBackupCollector()` with no args, which fails closed at
the D4 credential guard):

- build the collector with `ledger=RecoveryOperationalLedger.from_data_root(runtime_paths.data_root)`,
  `recovery_paths` / `vault_key` / `vault_key_id` (already resolved in that
  branch), `run_id=admission_run_context.run_id` if available;
- `platform_by_entity` from `cp_config_telemetry.json` (copy the RB.3a
  `--recovery-attest` block, `main.py` ~1216–1232);
- `prior_backup_sizes_by_entity` from `recovery_store` — largest
  `artifact.plaintext_bytes` per `entity_id` from `list_artifact_dirs(...,
  vendor="checkpoint")` manifests;
- pre-admission VSX reject hook so a `__vsid_` target is a clean `parser.error`
  (the collector also rejects it in `precheck`, but before admission is tidier);
- handle the new `skipped` outcome in the CLI summary (not a failure).

Then **step 7** (`Sonnet 5, normal`): `project/build_history.json` entry,
`CURRENT_STATE.md` "Active build" trim, `docs/AI_DEVELOPMENT_PROTOCOL.md` iff
`D6` adopts `operational-write` permanently, phase-doc status line.

Status stays `in_progress` until steps 6–7 land **and** the mandatory watched
real R81.10/R81.20 single-gateway run has happened (that run also resolves the
§7.7/§7.8 command-string confirm-on-hardware question and the exact
`add backup local` completion-line / archive-name format).

## 4. Test delta

Full suite `py -m pytest -q`: **875 passed / 25 skipped / 2 failed** (was
851/25/2). +24 = `tests/test_rb3b_cp_backup_device_core.py`. The 2 failures are
the same documented pre-existing test-order pollution
(`test_phase0_6_1c_discovery_capability_ui` leftover-placeholder,
`test_phase0_7_5_compliance_trend::test_checkpoint_render_appends_one_record`) —
both pass in isolation, unrelated to RB.3b. Repository privacy gate: only the
documented gitignored `data/` + `logs/` + `data/.support_hmac.key` local
test-run noise (PASS/0 on a clean checkout — all three are `.gitignore`'d).
Render harness covered by the full-suite run (`tests/test_html_render_harness.py`
green); no `templates/` / `static/` / payload-builder change.

## 5. New risks / debt

- **`add backup local` output format is confirm-on-hardware.** The collector
  parses the archive name from the command's own stdout (`backup_*.tgz`, or a
  `/var/log/CPbackup/backups/<name>` path). If a real R81.10/R81.20 gateway does
  **not** print the name (some Gaia builds only say "use `show backup status`"),
  every run ends `CLEANUP_FAILED` + INELIGIBLE — loud by design (§7.8 point 12
  forbids a listing-derived name), but it means the first watched run may
  surface a §7.3/§7.8 gate gap rather than a green backup. Same for the exact
  `show diskspace` vs `df -P /var/log` and `delete backup <name>` vs `rm -f --`
  forms — both literals are carried; the Expert form is primary.
- "SCP fetch" is implemented as **paramiko SFTP** (`getfo` into a `BytesIO`) —
  the repo-standard secure-copy-over-SSH mechanism (`cp_runner`, `vsx_runner`);
  no `scp` binary / `scp` package dependency added. `collected_via` stays the
  contract enum `"cp_ssh_scp_fetch"`. Flag for the gate review if the reviewer
  intended the `scp` wire protocol specifically.
- The "endpoint ineligible after CLEANUP_FAILED" state is **the newest ledger
  entry being `cleanup_failed`** (no separate store). An operator clears it by
  removing the orphaned `/var/log/CPbackup/backups` archive and the ledger
  entry. Documented in the collector; not yet surfaced in any UI.
- `run_recovery_collection` gained a `"skipped"` status + `skipped_count`;
  `failed_count` now excludes `skipped`. `main.py`'s CP/PAN CLI summary
  (`failed_count == 0` ⇒ exit 0) already does the right thing, but step 6 should
  print skipped endpoints explicitly.
- Carried unchanged: the operational-write ledger is new correctness-critical
  state (unreadable ⇒ false refusal, chosen); RB.2 PAN credential follow-up
  before RB.2 leaves IMPLEMENTED; `D5`–`D7` open (`D6` = adopt
  `operational-write` into `AI_DEVELOPMENT_PROTOCOL.md`).

## 6. Continue or fresh chat

**Fresh chat.** Read `AI_START_HERE.md` → `CURRENT_STATE.md` (hot section) →
this file → `docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` (§"Implementation
plan" steps 6–7, correctness contract, AC list). Nothing in this chat's context
is needed. Step 6 is deterministic `main.py` wiring against a frozen collector —
`Sonnet 5, normal`.

## 7. main.py / UI effect

**None yet.** No CLI flag, no runtime behaviour change. A normal `py .\main.py`
run is unaffected. `--recovery-collect --recovery-vendor checkpoint` still fails
closed at the D4 credential guard (unchanged) — and now, with backup creds set
but the ledger/store not yet wired, `collect()` fails closed with a clear
"ledger not configured" / "recovery store is not bound" message. Step 6 wires
it live.
