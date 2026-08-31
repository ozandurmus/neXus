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
- **Active build: `RB.3b` — CP Gaia system backup collection.** All seven
  implementation steps now landed (2026-08-31, across this and prior
  sessions). **Status stays `in_progress` — not `IMPLEMENTED`** — the sole
  remaining item is the mandatory watched real R81.10/R81.20 gateway run
  (hardware-gated, not an engineering task).
- Branch: `claude/docs-handover-sequence-f5sb1g`. Not yet merged to `main`
  (push is human-controlled, standing priority 4).

## 2. What changed this session (step 7 — project metadata / state sync)

Docs-only. No source file touched; no test re-run (no code changed, so the
last evidence — 879 passed / 23 skipped / 2 failed — still holds per the
test-economy rule).

- `project/build_history.json` — added the missing `RB.3b-impl-step5`
  (device core + C6) and `RB.3b-impl-step6` (`main.py` wiring) records, which
  a prior session's step-6 landing never got around to recording, plus a new
  `RB.3b-impl-step7` record for this session's own docs sync. All three carry
  `status: in_progress` — RB.3b as a whole isn't done, so this doesn't fold
  into one closing entry (matching the `RB.3b-prep` /
  `RB.3b-prep-signoff` precedent of one entry per landed movement).
- `docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` — Status header
  updated (steps 2–7 landed, still `in_progress`); Definition of done items 5
  (step 6 landed) and 6 (step 7 landed, new) added; "Next movement / model"
  rewritten — nothing engineering remains until the real-environment run.
- `CURRENT_STATE.md` — Active build section trimmed: the separate steps-2-4
  / step-5 bullets collapsed into one steps-2–7-implemented line (detail now
  lives in `project/build_history.json` + the phase doc, not repeated here);
  "Authoritative checkpoint" line updated to say steps 2–7.
- `project/roadmap.json` — `now_next.next.goal` was stale (still said "Steps
  5-7 owed" from before this session); rewritten to reflect steps 2–7
  implemented and only the real-env run outstanding.
- `project/backlog.json` (`native_backup` item) and
  `project/feature_registry.json` (`native_backup_foundation` item) — each
  had a stale "RB.3b stays blocked pending sign-off" sentence left over from
  before the 2026-08-31 sign-off; appended (not rewritten — `AGENTS.md`
  "do not silently rewrite historical outcomes") an `UPDATE 2026-08-31`
  sentence superseding it.
- `docs/AI_DEVELOPMENT_PROTOCOL.md` — **deliberately untouched.** Step 7 only
  updates it if `D6` (adopt `operational-write` as a permanent protocol
  class) is resolved; it is still open.

## 3. Exact next action

**Nothing is owed on RB.3b from this chat.** The single remaining item —
the watched real R81.10/R81.20 single-gateway `add backup local` run, with
free space observed before/after and the deletion confirmed — is
hardware-gated and needs a human at a console, not another implementation
step. Do not invent further RB.3b engineering work to fill that gap.

For the next AI session, in order of likely product priority:

1. If the real-environment run has happened since this handover was
   written: record its result (phase doc "Risks"/"Definition of done",
   `CURRENT_STATE.md`, `build_history.json`), confirm the §7.7/§7.8 exact
   command-string question it was meant to resolve, and only then move
   RB.3b to `IMPLEMENTED`.
2. Otherwise: `RB.3c` (CP management export + consistency groups) is still
   blocked on `D5` (storage budget) and `E1` (§7.6 `operational-write`
   classification unverified) — both are product-owner decisions, not
   engineering-ready. Do not start `RB.3c` implementation without them.
3. Absent explicit product direction, the `project/roadmap.json`
   `now_next.upcoming` list (e.g. `0.6.6B` compliance rule-pack transition)
   is the next unblocked track.

## 4. Test delta

None. This session made no source, test, or fixture change — pure
`project/*.json` + `docs/history/phase/RB_3B_CP_GAIA_BACKUP_COLLECTION.md` +
`CURRENT_STATE.md` text edits. Last recorded evidence (unchanged, still
authoritative): **879 passed / 23 skipped / 2 failed**, the 2 being the
documented pre-existing test-order pollution
(`test_phase0_6_1c_discovery_capability_ui`,
`test_phase0_7_5_compliance_trend::test_checkpoint_render_appends_one_record`).
Repository privacy gate: **PASS / 0** — this session touched no runtime
artifact, only tracked docs/JSON.

## 5. New risks / debt

None new. Carried unchanged from the step-6 handover (see prior version in
git history for full text): `add backup local` output format and the
§7.7/§7.8 command strings are confirm-on-hardware; "SCP fetch" is paramiko
SFTP, not the `scp` binary; a `CLEANUP_FAILED` endpoint is ineligible until
an operator clears the orphaned archive + ledger entry manually; the
operational-write ledger is new correctness-critical state (unreadable ⇒
false refusal, chosen deliberately); `D5`–`D7` remain open (`D6` = adopt
`operational-write` into `AI_DEVELOPMENT_PROTOCOL.md` permanently — still
not resolved, so no doc there was touched this session either).

## 6. Continue or fresh chat

**Fresh chat.** RB.3b's engineering work is exhausted pending a human
hardware run; there is nothing left in flight for a continuation to pick up
mid-thought. Read `AI_START_HERE.md` → `CURRENT_STATE.md` (hot section) →
this file → the real-environment run's result if one has landed, otherwise
`project/roadmap.json`'s `now_next` for the next product priority.

## 7. main.py / UI effect

**No change.** This session touched no source file — `templates/`,
`static/`, `main.py`, and every collector/orchestration module are byte-
identical to the step-6 handover's state. A normal run behaves exactly as
described in that prior handover: `--recovery-collect --recovery-vendor
checkpoint` requires both `SECURITYEXPERT_CP_BACKUP_SSH_USERNAME` +
`_PASSWORD`/`_PASSWORD_FILE` and a non-empty
`SECURITYEXPERT_CP_BACKUP_ALLOWED_ENTITIES`, both unset by default, so an
unconfigured run still touches no device.
