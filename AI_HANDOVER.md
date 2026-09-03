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
  reset fresh off `main` at `7143e21` (PR #42 merged — `OP.0b` S5). This
  build, `op0b_s6_pan_preflight_collector`, is `OP.0b` S6 — the dedicated
  Palo Alto failover-preflight collector, `S5`'s parallel sibling slice.

## 2. What changed this session

Implemented `panorama/preflight_collector.py` — strictly within the
PO-frozen `OP.0b.1` command gate (`docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md`,
"Approval record", PR #41). For one caller-selected, bounded (≤2 member)
PAN HA pair: one direct API key per member, reused for `P1` (`show system
info`, identity gate — exact serial string comparison only, no
normalization), `P2` (`show high-availability state`, reusing the
existing `S2` `_parse_pan_ha_preflight_fields`/`project_pan_preflight_facts`
unchanged), `P4` (`show high-availability path-monitoring`, new); one
`preflight_run_id`; no application-level retry. `D-T1` (direct vs.
Panorama-proxy transport) resolved as **direct for every row** inside this
collector only — `P1`'s frozen plane is unconditionally direct and the
gate's PAN preamble forbids splitting `P2`/`P4` onto a different plane, so
all three reuse one direct API key/host rather than mixing direct (`P1`)
with Panorama-proxied (`P2`/`P4`).

New files: `panorama/pan_preflight_battery.py` (fixed typed
`PANPreflightRead` enum, `COMMAND_TEXT` literal map, a deterministic
`assert_battery_excludes_forbidden_commands` guard — run at import time —
proving `P3`/`P5` and every rejected mutating PAN operation are absent by
construction); `panorama/pan_preflight_extraction.py` (one pure,
fail-closed parser for `P4`, `parse_pan_path_monitoring`).
`panorama/pan_preflight_projection.py` (existing S2 seam) gains
`project_pan_identity_fact` (`P1`) and `project_pan_path_monitoring_facts`
(`P4`); `P2`'s existing `project_pan_preflight_facts` is unchanged.

New `tests/test_op0b_s6_pan_preflight_collector.py` (41 tests) covers all
36 numbered requirements from the build task (§21–§23) plus extraction
fixtures: battery invariants (P3/P5/mutation absence, no retry, ≤6
calls/pair), synthetic-session collection (identity-gate-stop, per-command
failure isolation, symbolic `source_command`, raw-value absence from
serialization), and `B2` non-establishment (peer claim never promoted to
own fact or a synthesized member, no serial normalization, leading-zero
identifiers stay distinct). No readiness verdict; no new API session
shape/credential path/TLS policy; no raw response persisted. Real device
contact: **none** this session.

Updated `project/roadmap.json` (`now_next.now` → `op0b_s6...`
`automated_validated`; `now_next.next` → `op0b_s7_readiness_v2_integration`
`planned`), `project/build_history.json` (new S6 record, newest-first),
`CURRENT_STATE.md` (checkpoint, Active build, Predecessors, Exact next
build, test baseline), and regenerated `docs/history/INDEX.md`.

## 3. Exact next action

`OP.0b` S7 — readiness v2 integration. First slice to consume
`PreflightSnapshot`/`evaluate_coherence` (S1) and the real per-member
evidence `S5`/`S6` now produce, and to define/wire the readiness-v2
verdict path — still `CLASS 0` read-only, still no `SAFE_TO_FAILOVER` by
construction (existing `utils.failover.assessment` invariant, test-enforced).
New session, fresh `origin/main`, branch `feature/op0b-s7-readiness-v2`,
`Sonnet 5, extended thinking (high)` — new architecture/readiness-verdict
design, per this repo's routing table.

## 4. Test delta

+41 (`tests/test_op0b_s6_pan_preflight_collector.py`, new file). No
existing test changed or removed. This session's local full suite: 1318
passed / 26 skipped / 0 failed (serial) — up from S5's 1277/26/0 in the
same sandbox.

## 5. New risks / debt

- Real-env validation (`S8`) still owed for `P1`/`P2`/`P4` — exact real
  PAN-OS `show high-availability path-monitoring` response shape/
  vocabulary is `UNKNOWN` pending a real device read; the extraction
  parser is fail-closed on anything unrecognized by design.
- `D-T1` (direct vs. Panorama-proxy transport) is resolved as "direct for
  every row" **inside this collector only** — a non-blocking, revisitable
  implementation choice, not a new frozen contract decision.
- `D-V3a` (PAN HA serial identity) and `B2` (bidirectional pair-identity
  corroboration) both remain unresolved/`NOT ESTABLISHED` — this build
  touches neither the pair-identity model nor serial normalization.
- No PR opened yet this session.

## 6. Continue or fresh chat

Fresh session for `S7` — the build task itself specifies "NEW SESSION
REQUIRED" for each `OP.0b` slice, and state is fully recorded here,
`CURRENT_STATE.md`, and `build_history.json`.

## 7. main.py / UI effect

None. `panorama/preflight_collector.py` is new, dormant code — nothing in
`main.py`'s existing CLI modes calls it yet. No UI payload, template, or
`static/` file changed.
