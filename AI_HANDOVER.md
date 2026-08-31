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
  and signed off. **Steps 2–6 implemented this session's line of work
  (step 6 landed this session). Step 7 owed.** Status stays `in_progress` —
  step 7 (project metadata / `CURRENT_STATE.md` trim) **and** the mandatory
  watched real R81.10/R81.20 run are both still owed before `IMPLEMENTED`.
- Branch: `claude/rb3b-step6-main-wiring-iao3m8`. Not yet merged to `main`
  (push is human-controlled).

## 2. What changed this session (step 6 — `main.py` wiring)

- `main.py`'s `--recovery-collect --recovery-vendor checkpoint` branch no
  longer constructs `CheckpointGaiaBackupCollector()` bare (which failed
  closed at the D4 credential guard with the ledger/store unwired). It now:
  - builds `ledger = RecoveryOperationalLedger.from_data_root(runtime_paths.data_root)`
    and passes `recovery_paths` / `vault_key` / `vault_key_id` (already
    resolved earlier in that branch) straight into the collector;
  - passes `run_id=admission_run_context.run_id if admission_run_context else None`;
  - builds `platform_by_entity` from `cp_config_telemetry.json` — the exact
    block `--recovery-attest` already used, duplicated (not shared) since the
    two branches build it from the same file but at different points in
    `main()`;
  - builds `prior_backup_sizes_by_entity` by walking
    `recovery_store.list_artifact_dirs(recovery_paths, vendor="checkpoint")`,
    reading each manifest, keeping only `artifact.class ==
    "cp_gaia_backup"`, and collecting `artifact.plaintext_bytes` per
    `device.entity_id`; an unreadable/corrupt manifest is skipped (best
    effort — it only affects the §7.7 free-space floor, never a correctness
    gate);
  - rejects a `__vsid_` entity named in `--recovery-gateways` with a clean
    `parser.error` **before** any of the above runs (B7) — the collector's
    own `precheck()` still refuses it too; this is belt-and-suspenders, not a
    replacement.
  - CLI summary gained an explicit `Skipped (already fresh):` line
    (`result.skipped_count`); `Gate:`/exit-code logic was already
    skip-aware (`failed_count` already excluded `"skipped"`) and is
    unchanged.
- `tests/test_rb2_recovery_collect.py` — two new CLI-integration tests:
  `test_cli_recovery_collect_checkpoint_rejects_vsx_target_before_admission`
  (SystemExit 2, no ledger/admission/device path reached) and
  `test_cli_recovery_collect_checkpoint_wires_ledger_platform_and_prior_sizes`
  (monkeypatches `CheckpointGaiaBackupCollector` to capture constructor
  kwargs; asserts `platform_by_entity`, `prior_backup_sizes_by_entity`,
  `vault_key(_id)`, `recovery_paths`, `run_id=None` and a real
  `RecoveryOperationalLedger` instance all land correctly).
- No change to `checkpoint/checkpoint_recovery_collector.py`,
  `utils/recovery_collect.py`, `utils/recovery_operational_ledger.py`, or
  `utils/recovery_store.py` — step 6 is wiring only, against the already-frozen
  step-5 collector contract.
- No `templates/`, `static/`, or payload-builder change — no UI effect.

## 3. Exact next action

**RB.3b step 7** (`Sonnet 5, normal`, per the phase doc's own routing):

- `project/build_history.json` entry for this step-6 landing (or fold into
  one entry when RB.3b as a whole closes — check the doc's own
  `record_contract` for which);
- `CURRENT_STATE.md` "Active build" trim: steps 2–6 implemented, step 7 +
  the real-environment run remain;
- `docs/AI_DEVELOPMENT_PROTOCOL.md` update **iff** `D6` (adopt
  `operational-write` as a permanent protocol class) is resolved — it is
  still open; do not touch that doc until it is;
- `docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` status line.

Status stays `in_progress` — not `IMPLEMENTED` — until step 7 lands **and**
the mandatory watched real R81.10/R81.20 single-gateway run has happened
(unchanged from before this session; that run is what resolves the
§7.7/§7.8 command-string and `add backup local` output-format
confirm-on-hardware questions — see `docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md`
"Risks").

## 4. Test delta

Full suite `py -m pytest -q` (this sandbox has no live PostgreSQL, unlike
some prior sessions — that alone shifts the skip count, not a code change):
**879 passed / 23 skipped / 2 failed** (prior recorded baseline before this
session: 875/25/2). +2 = the new step-6 CLI-integration tests above; the
remaining +2 passed / -2 skipped is this sandbox's dependency availability,
confirmed unrelated by rerunning `tests/test_rb2_recovery_collect.py` alone
(27/27 green) and the two RB.3b-specific suites alone (green). The 2
failures are the same documented pre-existing test-order pollution
(`test_phase0_6_1c_discovery_capability_ui`,
`test_phase0_7_5_compliance_trend::test_checkpoint_render_appends_one_record`)
— both re-confirmed passing in isolation this session, unrelated to RB.3b.
Repository privacy gate: **PASS / 0** on a clean checkout (this session's own
gitignored `data/`+`logs/` test-run noise was created and deleted before the
final gate run). No `templates/`/`static/`/payload-builder change, so the
render harness was not re-run beyond what the full suite already covers.

## 5. New risks / debt

- Carried unchanged from step 5 (see prior handover / git history for full
  text): `add backup local` output format and the §7.7/§7.8 command strings
  are confirm-on-hardware; "SCP fetch" is paramiko SFTP, not the `scp`
  binary; a `CLEANUP_FAILED` endpoint is ineligible until an operator clears
  the orphaned archive + ledger entry manually; the operational-write ledger
  is new correctness-critical state (unreadable ⇒ false refusal, chosen
  deliberately); `D5`–`D7` open (`D6` = adopt `operational-write` into
  `AI_DEVELOPMENT_PROTOCOL.md` permanently — still not resolved, so step 7
  must not touch that doc yet).
- New this session: `platform_by_entity` and `prior_backup_sizes_by_entity`
  are both best-effort reads (missing `cp_config_telemetry.json` ⇒ empty map,
  unreadable manifest ⇒ skipped) — by design, matching AC-9 ("an unknown
  platform is not a reason to skip") and the fact that a missing prior-size
  only affects the §7.7 floor (falls back to `min_free_floor_mb`), never a
  correctness gate. Not a gap, but worth naming so it isn't mistaken for an
  oversight later.
- `main.py`'s `--recovery-attest` branch builds the identical
  `cp_config_telemetry.json → platform_by_entity` block independently
  (pre-existing, now duplicated a second time in `--recovery-collect`). Left
  as duplication rather than extracted — two call sites, both small, and the
  phase doc scoped step 6 to wiring only; flag for a future cleanup pass if a
  third call site appears.

## 6. Continue or fresh chat

**Fresh chat.** Read `AI_START_HERE.md` → `CURRENT_STATE.md` (hot section) →
this file → `docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md`
("Implementation plan" step 7, "Definition of done"). Step 7 is project
metadata + doc trims against a build that is otherwise done — deterministic,
`Sonnet 5, normal`, no extended thinking needed.

## 7. main.py / UI effect

**No UI change** (no `templates/`/`static/` touch). Functional CLI change:
`py -B main.py --recovery-collect --recovery-vendor checkpoint [--recovery-gateways ...]`
now runs live end-to-end against the frozen collector instead of failing
closed at construction — still requires
`SECURITYEXPERT_CP_BACKUP_SSH_USERNAME` + `_PASSWORD`/`_PASSWORD_FILE` (the
distinct backup identity, D4/B11, no fallback) **and** a non-empty
`SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES` pilot allowlist (B10) — both
unset by default, so a normal run with no operator configuration still does
nothing device-side. No live device has been touched by this session (fixture
transports only, per contract §11); the mandatory watched real R81.10/R81.20
run is still owed before this path is used against production hardware.
