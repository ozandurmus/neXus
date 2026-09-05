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

- Date: 2026-09-05. Branch: `claude/pcp0-freeze-merge-4e6kb4` (built on
  `claude/nexus-control-plane-arch-mutgr9`'s reviewed head `92c9892`, which
  sits directly on `main` `ff700e38` -- `main` had not advanced).
- Build: `product_control_plane_architecture_draft` (`PCP.0`) — **COMPLETE,
  FROZEN 2026-09-05.** Product Owner approved the product direction and
  architecture, conditioned on exactly two mechanical freeze corrections,
  both applied this session (see below). This is a bounded freeze movement,
  not a design review: no product-direction or architecture content beyond
  those two corrections changed.
- Docs/state only. No product code, test, taxonomy, console route, device
  command, schema or UI change.
- PR to `main` opened this session (see "Delivery" below for number/status
  once created); merge only after fast CI is green and conflict-free.

## 2. What changed this session (the freeze)

**Correction 1 — registry mutation lock ownership and privacy (§21).** The
lock's release step is now instance-safe: the exclusive-create call embeds
a fresh random `owner_token` in `data/state/device_registry.lock` alongside
the existing `pid`/`hostname`/`acquired_at_utc` diagnostic fields; release
re-reads the file and deletes it only if its `owner_token` still matches the
one the releasing process itself wrote. A mismatch or missing file — an
externally deleted-and-recreated lock now held by a different writer — is
left untouched, never unlinked. This closes the exact hole where a slow,
non-crashed holder's own normal release could otherwise destroy a different
writer's active lock instance after a human wrongly declared the original
holder dead. AC-5 widened to cover the lock file explicitly; new AC-15
states the instance-safety guarantee; non-goals/validation-ladder updated.
The lock file is now explicitly classified **LOCAL-SENSITIVE**
(`PRIVACY_AND_DATA_HANDLING.md` CLASS 2), same as the registry file, and
both are kept out of the support bundle (`run_support_bundle` enumerates
only `data/runs/*`, never `data/state/*`).

**Correction 2 — §22 amendment timing.** Items 1-3 (amendments to
`OPERATOR_CONSOLE_ARCHITECTURE.md` §12, `COMPLIANCE_ASSIGNMENT_AND_
FRAMEWORKS.md` §4b, `BACKUP_AND_RECOVERY_ARCHITECTURE.md` §9) are applied
by this freezing session — appended verbatim to those three documents.
Item 4 (`AI_START_HERE.md` "What this is" sentence) is explicitly **not**
applied now: it is only true once `PCP.1` actually ships a persistent
registry, so writing it into the canonical cold-start entry point today
would misstate current capability. Moved to the `PCP.1` close scope.

**Status flip.** `docs/design/PRODUCT_CONTROL_PLANE_ARCHITECTURE.md`'s own
`## Status` line: `DRAFT` → `FROZEN` (§23/§24 updated to match; the
document's status-line token is what
`tests/test_architecture_convergence.py::test_a_draft_contract_never_backs_a_terminal_build_history_record`
checks against the build_history record's own status).

**State reconciliation (no duplicate records created):**

- `project/build_history.json` head record (`product_control_plane_
  architecture_draft`): status `in_progress` → `complete`, `completed`
  date added, summary extended with the freeze paragraph, `risks_forward`
  rewritten for the now-terminal record.
- `project/roadmap.json`: `now_next.now` marked `complete`/FROZEN;
  `now_next.next` (`PCP.1`) notes updated (AC-1a..AC-15, ownership-token
  lock, "not started"); `PCP.x` track status `planned` → `in_progress`;
  `pcp_console_registry_write_gate` **corrected** — this entry had drifted
  from the architecture document after round 1's 2026-09-05 widening (it
  still pre-decided "candidate-based enrollment now" in both of its
  options); rewritten to the document's actual no-pre-decision, three-option
  (a/b/c) position, with no new decision content introduced; two
  roadmap-notes/architecture-review-notes entries updated to match.
- `project/feature_registry.json`: `device_registry_enrollment_foundation`'s
  `registry_model` and `privacy_bundle_exclusion` criteria labels extended
  for the ownership-token release and the lock file's own classification —
  both criteria stay `pending` (`PCP.1` not started).
- `CURRENT_STATE.md`: "Active build" / "Exact next build" / checkpoint /
  test-baseline sections updated to FROZEN; stays ≤ 200 lines; still names
  `product_control_plane_architecture_draft`.
- `docs/history/INDEX.md`: regenerated (`scripts/build_history_index.py`).

## 3. Exact next action

1. Open a PR from `claude/pcp0-freeze-merge-4e6kb4` to `main` (fast PR CI is
   sufficient — docs/state only). Confirm it is clean and CI is green, then
   merge, sync local `main` to `origin/main`.
2. Then `PCP.1` (`pcp_1_device_registry_manual_enrollment_foundation`),
   `Sonnet 5, normal`: one short prompt pointing at §21 of the now-frozen
   architecture document and `tests/test_pcp1_device_registry.py`. Implement
   the registry mutation lock exactly as specified — including the
   ownership-token instance-safe release (AC-15) — not as a bare
   unconditional unlink, and not with round 1's since-closed "duplicate-
   record race is an accepted limitation" framing. No device contact, no UI.
   At `PCP.1` close, also land the deferred `AI_START_HERE.md` §22 item 4
   sentence.

Unchanged and independent: `op2_c_cp_clusterxl_adapter_scoping` stays
blocked on `DEPLOY.1`; `op0b_0_close_d_v3a_d_v7b_pre_class2`; PAN serial
identity closure (hardware-blocked); `cp_remote_collection_done_marker_
diagnostics` (needs a recurrence).

## 4. Test delta

- No product code changed; full `pytest` **not run** — this sandbox has no
  `pytest`/`lxml`/`paramiko` (reported, not bootstrapped, per `CLAUDE.md`).
  Verified directly instead: `utils.project_plan.build_project_plan_payload()
  ["metadata_warnings"] == []`; `scripts/build_history_index.py --check`
  clean; every `build_history.json` doc link resolves; `CURRENT_STATE.md`
  names `now.build` and is exactly 200 lines; `git diff --check` clean;
  the draft/frozen build-history gate
  (`test_a_draft_contract_never_backs_a_terminal_build_history_record`)
  hand-verified against the corrected status line and build status.
- Repository privacy gate re-run this session: **PASS / 0 findings** (484
  files scanned).
- Baseline 1825 passed / 24 skipped / 0 failed carried forward, not re-run
  (no product code touched).
- `git fetch origin` confirmed `main` had not advanced past the reviewed
  head (merge-base of `claude/nexus-control-plane-arch-mutgr9` and
  `origin/main` equals `origin/main`'s own head, `ff700e38`) — no
  reconciliation against a moved `main` was needed.

## 5. New risks

- None to any reachable capability — no code changed.
- Process: `product_control_plane_architecture_draft` is now a terminal
  (`complete`) build_history record; do not reopen it for a future
  correction — any further change to the frozen document is its own new
  movement/record.
- Product: a future `PCP.1` session must not quietly drop the ownership-
  token instance-safe release or reintroduce a bare unconditional unlink.
  `pcp_console_registry_write_gate` remains open for both enrollment
  intents, unaffected by this freeze. The `AI_START_HERE.md` §22 item 4
  sentence must land at `PCP.1` close — do not forget it, and do not pull
  it forward early.
