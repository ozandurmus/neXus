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

- Date: 2026-09-03. Branch `claude/checkpoint-preflight-collector-i1yyz7`,
  base `main` (at `abf0bb4`, PR #41 merged — `OP.0b` S4 command gate,
  PO-approved). This build, `op0b_s5_cp_preflight_collector`, is `OP.0b`
  S5 — the first dedicated Check Point failover-preflight collector.

## 2. What changed this session

Implemented `checkpoint/preflight_collector.py` — the dedicated Check
Point `CLASS-0` preflight collector — strictly within the PO-frozen
`OP.0b.1` command gate (`docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`,
"Approval record", PR #41). For one caller-selected, bounded (≤2 member)
HA operational entity: one SSH session per physical member (reused for
every read including `B1`, never re-opened), one `preflight_run_id`, and
exactly the authorized battery — `A1`–`A3` (existing, reused via
`configuration.checkpoint_config_probe`/`configuration.checkpoint_config_collector`
primitives, not duplicated) + new `A4` (`cphaprob -a if`), `A5`
(`cphaprob -ia list`), `A6` (`cphaprob syncstat` / `fw ctl pstat`,
version-dispatched from `A2`), `A7` (`fw stat`), `A8` (cluster failover
statistics, platform-dispatched, default invocation only) + `B1`
(`vsx stat -v`, VSX battery only, same session). `A6`/`A8` dispatch is
evidence-based, decided before execution, never a failure-driven fallback;
no command carries an application-level retry.

New files: `checkpoint/cp_preflight_battery.py` (fixed typed
`CPPreflightRead` command plan, `COMMAND_TEXT` literal map, `resolve_a6_form`/
`resolve_a8_form` dispatch resolvers, `build_member_schedule`, and a
deterministic `assert_battery_excludes_forbidden_commands` guard — run at
import time — proving `A9`/`A10`/`A11` and every rejected mutating command
are absent by construction); `checkpoint/cp_preflight_extraction.py` (one
pure, fail-closed parser per new command — no raw output retained beyond
the parse call). `checkpoint/cp_preflight_projection.py` (existing S3 seam
module) gains one projection function per new command
(`project_cp_software_version_fact`, `project_cp_link_health_facts`,
`project_cp_pnote_facts`, `project_cp_sync_facts`, `project_cp_policy_facts`,
`project_cp_failover_history_facts`, `project_cp_vsx_enumeration_facts`);
`A1`–`A3`'s existing `project_cp_preflight_facts` is unchanged.

New `tests/test_op0b_s5_cp_preflight_collector.py` (48 tests) covers all
40 numbered requirements from the build task (§27–§29): command-plan
invariants, synthetic-session collection (identity-gate-stop, per-command
failure isolation, invocation-count bounds ≤16 non-VSX / ≤18 VSX, no raw
output in serialization), and the VSX battery. No readiness verdict
anywhere; no new SSH transport/credential path; no raw command output
persisted. Real device contact: **none** this session.

Updated `project/roadmap.json` (`now_next.now` → `op0b_s5...`
`automated_validated`; `now_next.next` → `op0b_s6_pan_preflight_collector`
`planned`), `project/build_history.json` (new S5 record, newest-first),
`CURRENT_STATE.md` (checkpoint, Active build, Predecessors, Exact next
build, test baseline), and regenerated `docs/history/INDEX.md` via
`scripts/build_history_index.py`.

## 3. Exact next action

`OP.0b` S6 — Palo Alto dedicated preflight collector
(`panorama/preflight_collector.py`), same shape as this session's `S5`:
`panorama/pan_preflight_battery.py` (fixed command plan, guard proving
`P3`/`P5` absent from the implementation battery), one pure extraction
helper per new command, additions to the existing PAN S2 projection seam.
Battery: `P1`/`P2` (existing) + `P4` (`show high-availability
path-monitoring`, `NO_RETRY`) = 3 required reads/member, ≤6 required
invocations/pair. New session, fresh `origin/main`, branch
`feature/op0b-s6-pan-preflight-collector`, `Sonnet 5, normal`. Full
detail: `project/roadmap.json` `now_next.next.notes` +
`docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md` "Approval record".

## 4. Test delta

+48 (`tests/test_op0b_s5_cp_preflight_collector.py`, new file). No
existing test changed or removed. This session's local full suite: 1277
passed / 26 skipped / 0 failed (serial) — see `CURRENT_STATE.md`
"Automated test baseline" for why this count differs from the last
CI-recorded baseline (a fresh sandbox needed `requirements.txt`/
`requirements-console.txt` installed; unrelated to this build's code).

## 5. New risks / debt

- Real-env validation (`S8`) still owed for `A4`–`A8`/`B1` — exact vendor
  field vocabulary for `A6`'s `syncstat`/`pstat` status token and `A8`'s
  two failover-history forms is `UNKNOWN` pending a real device read; the
  extraction parsers are fail-closed on anything unrecognized by design.
- `D-F3` (flap/failover threshold) stays unresolved — `A8`'s count is
  collected but no PASS/healthy verdict is ever derived from it.
- `A9` (configured recovery/preemption) remains `DEFERRED_UNKNOWN` — bug
  register `CP-3`, `P0` before `CLASS 2`.
- No PR opened yet this session (task instructions specify a
  `feature/op0b-s5-cp-preflight-collector` branch/PR; this session's
  designated push target per the harness is
  `claude/checkpoint-preflight-collector-i1yyz7` — see commit for the
  reconciliation note).

## 6. Continue or fresh chat

Fresh session for `S6` — the build task itself specifies "NEW SESSION
REQUIRED" for each `OP.0b` slice, and state is fully recorded here,
`CURRENT_STATE.md`, and `build_history.json`.

## 7. main.py / UI effect

None. `checkpoint/preflight_collector.py` is new, dormant code — nothing
in `main.py`'s existing CLI modes calls it yet (wiring a real invocation
path is a future build's job, consistent with how S1/S3's extraction/
projection seams stayed dormant until this session used them). No UI
payload, template, or `static/` file changed.
