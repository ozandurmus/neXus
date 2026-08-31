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
  and signed off. **Steps 2–4 implemented this session; steps 5–7 owed.**
  Status `in_progress` — not IMPLEMENTED.
- `main` at `f9dbf91`, pushed to `origin/main`. Two commits this session:
  `9ce5c29` (prior-session prep sign-off docs), `f9dbf91` (RB.3b steps 2–4).

## 2. What changed this session

- `utils/recovery_operational_ledger.py` (new) + a fifth `utils/evidence_backend.py`
  concern (`OperationalWriteLedgerBackend`, filesystem + Postgres, INSERT-only,
  `select_operational_write_ledger_backend`, preflight line). Fail-closed:
  absent ledger → proceed; unreadable → `OperationalLedgerUnreadableError`,
  no device command.
- `checkpoint/checkpoint_recovery_collector.py` — D3-blocked stub replaced with
  the offline gate layer: pilot allowlist `SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES`
  (B10), D4 backup-credential guard `SECURITYEXPERT_CP_BACKUP_SSH_USERNAME` +
  `_PASSWORD_FILE`/`_PASSWORD`, fail-closed, **no fallback** to
  `SECURITYEXPERT_CP_CONFIG_SSH_*` (B11), platform / VSX / `software_version`
  gates (B7/B8/§3 rule 5), §7.7 `/var/log` free-space parser + 3× threshold
  (`SECURITYEXPERT_CP_BACKUP_MIN_FREE_MB`, default 3072, hard floor 1024).
  `collect()` runs the gate then raises pending step 5.
- `main.py` — CP collector construction moved inside the `try` so the D4
  fail-closed surfaces as a clean `parser.error`. No CLI/UI change.
- Tests: `tests/test_rb3b_operational_ledger.py`,
  `tests/test_rb3b_cp_backup_collector.py` (new); `tests/test_rb2_recovery_collect.py`
  — 2 stale "always blocked" tests rewritten to the D4-guard behaviour.

## 3. Exact next action

**RB.3b step 5** (`docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md`
§"Implementation plan"), at **`Sonnet 5, extended thinking`** — the device
core: `add backup local` (§7.3, no retry, 900 s, not in a VSX context), SCP
fetch streaming into `recovery_store.write_artifact` (no plaintext temp file,
B6), digest verify vs device-reported size, deletion (§7.8, exact name held in
memory from §7.3 output only), all inside one SSH session inside the
`run_under_admission` callable, in the order: `ledger.within_window` →
allowlist → platform → free-space → `add backup local` → fetch → verify →
version resolve → store → delete → `ledger.record_execution` (fires iff
`add backup local` was sent). Reuse `checkpoint_config_probe._connect` /
`_run_exec` / `ProbeTarget` (as `checkpoint_recovery_attestation.py` does).

Then **step 6** (`Sonnet 5, normal`): full `main.py` wiring —
`cp_config_telemetry.json` platform map, prior-backup-size lookup from the
recovery store, pre-admission VSX reject hook — and **C6** (contracts §9.12
CP-path test: endpoint lock acquired + name absent from `ALLOWLISTED_WORKFLOWS`).
Then **step 7**: project metadata + `CURRENT_STATE.md` trim.

AC exercised so far: AC-1, AC-7…AC-11 (offline paths); §9.13 (b)(c)(d)(e).
All device interaction stays on a fixture SSH/SCP transport — never a live
device in CI.

## 4. Test delta

Full suite `py -m pytest -q`: **851 passed / 25 skipped / 2 failed** (was
804/20/2). The 2 failures are the documented pre-existing test-order pollution
(`test_phase0_6_1c_discovery_capability_ui` leftover-placeholder,
`test_phase0_7_5_compliance_trend::test_checkpoint_render_appends_one_record`)
— both pass in isolation, unrelated to RB.3b. +5 skips are the ledger's
Postgres-tier tests (no local PostgreSQL in this env; they run against a real
local PostgreSQL 16 as DEV.3.3's suite does). Privacy gate not re-run — only
env-var *names* added, no secret-bearing patterns. Render harness not run — no
`templates/` / `static/` / payload-builder change.

## 5. New risks / debt

- The operational-write ledger is new correctness-critical state; an unreadable
  ledger blocks the backup (false refusal chosen over a double disk write).
- Step 5 owes the mandatory watched real R81.10/R81.20 run before RB.3b advances
  past IMPLEMENTED — it also resolves the §7.7/§7.8 command-string
  confirm-on-hardware question. On `project/backlog.json`
  `on_hardware_real_env_validation`.
- Carried unchanged: RB.2 PAN credential follow-up (distinct PAN service
  account, `panorama/panorama_recovery_collector.py`, D4 doc §8) before RB.2
  leaves IMPLEMENTED; `D5`–`D7` open (`D6` = adopt `operational-write` into
  `AI_DEVELOPMENT_PROTOCOL.md`).

## 6. Continue or fresh chat

**Fresh chat.** Read `AI_START_HERE.md` → `CURRENT_STATE.md` (hot section) →
this file → the phase doc. Nothing in this chat's context is needed.

## 7. main.py / UI effect

**None.** No CLI flag added, no runtime behaviour changed. A normal
`py .\main.py` run is unaffected. `SECURITYEXPERT_CP_BACKUP_*` and
`utils/recovery_operational_ledger.py` do nothing until RB.3b step 5–6 wire the
collector in.
