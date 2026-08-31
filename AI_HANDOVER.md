# AI_HANDOVER

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase doc.
Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-08-31.
- Product baseline `0.7.7` — AUTOMATED_VALIDATED. Engineering `DEV.3.3` —
  AUTOMATED_VALIDATED. `RB.3b` — `in_progress` (all 7 implementation steps
  landed; only the real-environment run remains). None of these changed
  this session.
- **This session's build: `remove_dormant_remote_cleanup` — DONE.** A small,
  self-contained, server/device-independent security-hardening item, picked
  specifically because it needed neither a live CP/PAN device nor the
  DEPLOY.1 server.
- Branch: `claude/remove-dormant-remote-cleanup`, merged to `main` via PR
  (squash) this session — see step 3 below for the actual PR/commit.

## 2. What changed this session

- Deleted `utils/cleanup.py`. It was unreferenced by any live code path but
  connected over SSH with the CP **collection credential** and issued
  unaudited `rm -f` commands — outside the network-device command gate
  (`docs/AI_DEVELOPMENT_PROTOCOL.md`) and this product's read-only posture.
  Flagged for removal in
  `docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md`
  §3 item 1 and `project/backlog.json` `remove_dormant_remote_cleanup` (P0).
- New `tests/test_remove_dormant_remote_cleanup.py`
  (`pytestmark = pytest.mark.security`, 2 tests): asserts the module stays
  absent, and greps every tracked `.py` file for `cleanup_all` so a
  reintroduction (under this name) is caught automatically.
- `project/backlog.json` — `remove_dormant_remote_cleanup` status
  `planned` → `done`, note appended with the DONE record.
- `project/build_history.json` — new `remove_dormant_remote_cleanup` entry
  (`status: done`).
- `CURRENT_STATE.md` — one-paragraph DONE note added near the RB.3b block
  (this build ran in parallel with RB.3b's now-finished implementation
  steps, not as part of it).
- No change to `main.py`, `templates/`, `static/`, or any collector/
  orchestration module — `utils/cleanup.py` had zero live callers, confirmed
  by a repo-wide grep before deletion (only `project/backlog.json` and the
  productization design doc mentioned it in prose).

## 3. Exact next action

RB.3b's engineering work is exhausted (see prior handover in git history) —
still waiting on the human hardware run, nothing to pick up there.

This session cleared the request to find device/server-independent
background work. Two more items from that same shortlist remain, both
explicitly local-only per `docs/design/
SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md` §3
"Local development work, safe before server arrival":

1. `frontend_rendering_boundary` (P1, `project/backlog.json`) — audit HTML
   sinks, safe JSON script serialization, a restrictive report CSP, hostile
   inventory/configuration label tests. Needs its own scoped contract before
   implementation (touches `templates/index.html` / `static/`, so the render
   harness must stay green).
2. `codebase_modularization` (P1) — split `static/app.js` and `main.py` into
   responsibility-owned modules per the architecture doc, behavior-
   preserving. Larger, multi-session scope; do not start without confirming
   with the product owner which module first.

A third option is design-only, not implementation: draft `D5` (storage
budget) and `E1` (§7.6 `operational-write` classification) decision briefs to
unblock `RB.3c`, mirroring how the `RB.3b` prep unblocked `D4` — offline,
no device, but needs product-owner sign-off before `RB.3c` can start, same
as `D4` did.

Do not start any of these without picking one explicitly with the user —
this handover intentionally leaves the choice open rather than assuming.

## 4. Test delta

New: 2 tests (`tests/test_remove_dormant_remote_cleanup.py`), both green.
Full suite **881 passed / 23 skipped / 2 failed** (up from the prior
879/23/2 baseline by exactly the 2 new tests) — the 2 failures are the same
documented pre-existing test-order pollution
(`test_phase0_6_1c_discovery_capability_ui::test_run_html_export_embeds_discovery_payload_without_leftover_placeholder`,
`test_phase0_7_5_compliance_trend::test_checkpoint_render_appends_one_record`),
both re-confirmed passing in isolation this session. Zero regressions.
Repository privacy gate: **PASS / 0** on a clean checkout (this session's
own gitignored `data/`/`logs/` test-run noise was deleted before the final
gate run).

Note: this sandbox had no Python dependencies installed at session start
(`pip3 install -r requirements.txt -r requirements-dev.txt` was run first,
system-wide, no venv). If a future session hits `ModuleNotFoundError`
(`lxml`, `pytest`, ...), that is a sandbox-provisioning gap, not a code
regression — reinstall from `requirements*.txt` rather than debugging code.

## 5. New risks / debt

None new. `utils/cleanup.py`'s removal has no runtime, CLI, or UI surface —
it was dead code. The regression test only catches a literally-named
`cleanup_all` reappearing in a tracked `.py` file; it does not generally
scan for "any write-capable SSH helper" (no such generic scanner exists in
this repo — the network-device command gate is documentation-enforced, not
tool-enforced). Worth naming so a differently-named equivalent isn't assumed
to be caught.

## 6. Continue or fresh chat

**Either works.** This build is fully closed (implemented, tested, merged);
nothing is mid-thought. A fresh chat is fine for whichever of section 3's
options the user picks next since none of them extend this session's work.
Continuing here is also fine if the user wants to keep discussing which one
to start.

## 7. main.py / UI effect

**No change.** `utils/cleanup.py` was never imported by `main.py` or any
collector; deleting it changes nothing about `main.py`'s CLI surface, any
run's output, or the rendered report. A normal run before and after this
session is byte-for-byte identical in behavior.
