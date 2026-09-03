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

- Date: 2026-09-03. Branch `claude/vendor-semantics-confirmation-pt2iiq`.
- Product baseline `0.7.7`; engineering baseline unchanged.
- This session: **`op0b_s2_pan_parse_scope_extension`** — second
  `IMPLEMENTATION` slice against the FROZEN `OP.0b.0` contract. Extends PAN
  parse scope of an already-fetched response and projects the result into
  S1's fact model. Zero new command, zero new device contact.

## 2. What changed this session

`configuration/panorama_config_collector.py`: new `_PAN_HA_PREFLIGHT_FIELD_MAP`
(35-entry field map) + `_parse_pan_ha_preflight_fields()` — reads
`conn-status` leaves (`conn-status`, `conn-ha1/conn-status`,
`conn-ha1-backup/conn-status`, `conn-ha2/conn-status`),
`running-sync`/`running-sync-enabled`, election/preemption
(`preemptive`/`priority`/`preempt-hold`/`promotion-hold`), flap/duration
counters, `last-error-reason`/`last-error-state`, and `*-version`/`*-compat`
parity fields (local + peer) out of the **same** already-fetched
`show high-availability state` response `get_target_ha_runtime_state`
already holds — zero new command, zero new API operation. New
`include_preflight_fields=False` opt-in parameter on
`get_target_ha_runtime_state` (mirrors the existing
`capture_field_diagnostics` precedent); default behavior and the one
existing production call site (`_collect_device_row`) are both unchanged —
the new capability is deliberately dormant/unwired this session, and not
added to `pan_config_telemetry.json`'s schema. Identity leaves
(`local-info/serial-num`, `peer-info/serial-num`) are tokenized with the
same `Tokenizer`/`"pan_ha_identity_value"` pattern the existing diagnostic
sweep already uses — one extraction authority preserved, no third
independent parser of the same XML fields, no leading-zero normalization.

New `panorama/pan_preflight_projection.py` (pure, no I/O, no lxml, no
network — imports only `utils.failover.preflight_model` types):
`project_pan_preflight_facts()` turns that field dict into S1
`PreflightFact`/`PreflightMemberEvidence` instances. Every present field
becomes `KNOWN` carrying the raw value read; every absent field `UNKNOWN`;
a malformed numeric counter degrades to `UNKNOWN`, never a crash;
`fields=None` represents a collection failure as all-`COLLECTION_FAILED`.
Peer-claim fields (`peer_state_claim`, `peer_conn_*`, `peer_serial_claim`,
`peer_*_version`) route to `peer_claim_facts`, never `own_facts` — a
member's report about its peer is that member's claim, never an
independent observation (contract domain invariant). No readiness/health
interpretation of any value; no `D-F3` threshold applied to any flap
counter.

Two new test files: `tests/test_op0b_s2_pan_extraction.py` (20 tests, lxml —
existing leaves unchanged, every new field parses correctly against a
full synthetic fixture, unknown `conn-status` values stored verbatim not
upgraded to "healthy", malformed-numeric passthrough at extraction, no
leading-zero normalization, missing `peer-info` degrades safely, plus 3
network-regression-guard tests proving exactly one `api_post` call either
way and no new command/thread-pool/retry token introduced — **`NOT
EXECUTED`** in this container, `ModuleNotFoundError: No module named
'lxml'`, the same pre-existing gap every other collector-touching test
file in this arc has hit; `py_compile` confirms both changed/new files are
syntactically valid) and `tests/test_op0b_s2_pan_projection.py` (14 tests,
pure — run and passing here: run-id propagation, identity/entity
separation, `source_plane=device_runtime`, no target identity in
`source_command`, peer-claim vs own-fact routing, single-member-in
single-member-out, `UNKNOWN` for absent fields, `COLLECTION_FAILED`
representation with no `KNOWN_BAD` state, S1 `PreflightSnapshot`/
`evaluate_coherence` acceptance including a mixed-run case that S1's
coherence check catches rather than the projection layer silently fixing
up, malformed/well-formed numeric conversion, no `D-F3` threshold
applied).

The frozen contract's own §25 field-trace table was updated (not its
`## Status` line — still `FROZEN WITH REAL-ENV VALIDATION GATES`) to mark
the now-parsed rows (`conn_status`/`conn_ha1`/`conn_ha2`, `running_sync`,
software/content parity, preemption/priority/hold, flap counters, failure
state) `COLLECTED_AND_PARSED`, narrowing each row's "required correction"
to only what S2 didn't close (vocabulary exhaustiveness — `D-V1`/`D-V2` —
and real-env path-presence confirmation — `S8`).

**Deliberate deviation from the contract's own S2 file list, flagged
rather than silent:** the slice table names
`utils/failover_readiness_ui.py` as a possible S2 file; this session did
not touch it. The task's own explicit constraints for this session ("no
UI change", "Do NOT touch... console/UI") take precedence over the
contract's general file list, so the new projection logic was placed in
its own pure module (`panorama/pan_preflight_projection.py`) instead —
additive, unwired, no UI/readiness-verdict path touched.

Files touched: `configuration/panorama_config_collector.py` (additive
extension), `panorama/pan_preflight_projection.py` (new),
`tests/test_op0b_s2_pan_extraction.py` (new),
`tests/test_op0b_s2_pan_projection.py` (new),
`docs/history/phase/OP_0B_0_VENDOR_FAILOVER_PREFLIGHT_EVIDENCE_SURFACE.md`
(§25 field-trace table only), `project/build_history.json`,
`project/roadmap.json`, `CURRENT_STATE.md`, `docs/history/INDEX.md`
(regenerated), this file. No CP collector/parser, action taxonomy,
console/UI, readiness-verdict engine, execution adapter, or
auth/credential file touched. No CLASS 2 behavior;
`utils.action_taxonomy.CLASS_2_*` unchanged. No pair-identity file
(`_derive_pan_units`, hostname fallback, `HaUnit`) touched.

## 3. Exact next action

Per the contract's own `S0 → S1 → (S2, S3 in parallel)` sequence — S2 is
done and needed no `S3` result:

1. **`OP.0b` S3** (`now_next.next`) — CP parse-scope extension: same
   pattern as S2, Check Point side. Extend
   `configuration/checkpoint_config_collector.py`'s parsing of the
   existing `cphaprob stat` output (peer rows/state, Active Attention
   reason, "Single VS Failover" mode, wire form + `collected_at`) into
   `PreflightFact`/`Provenance`. No new command. Independent of S2 —
   could equally have run first.
2. Independent, unaffected by this session: `D-F3` numeric threshold,
   `D-V3a`/`D-V7b` pre-CLASS-2 closure, PAN serial identity closure
   (hardware-blocked).

## 4. Test delta

+34 (`tests/test_op0b_s2_pan_extraction.py` ×20 lxml — `NOT EXECUTED` here;
`tests/test_op0b_s2_pan_projection.py` ×14 pure — executed, 14/14 passed).
Targeted suite runnable here (S1 + convergence): 42/42. S2 projection
suite: 14/14. Full regression in this container, tolerant of the
pre-existing dependency gap: 524 passed / 17 skipped / 33 failed / 82
errors — failed count unchanged from the prior session's baseline
(510/17/33/81); errors +1 is exactly this build's own lxml-blocked
extraction file (same gap shape, not a new kind of failure); passed +14 is
exactly this build's own projection tests. The full-dependency baseline
(1099/24/0, 2026-09-02) is preserved in `CURRENT_STATE.md`, not
overwritten with this partial number. `git diff --check` clean.

## 5. New risks / debt

None introduced beyond what's already tracked. Explicit: S2's parse
capability is additive/opt-in only — not wired into the production
collection call site or into `pan_config_telemetry.json`, so it stays
dormant/not load-bearing until a future slice (S5/S6, the dedicated
preflight collector) actually invokes it with
`include_preflight_fields=True` against a real device response.
`peer-info/conn-ha1-backup/conn-status`'s nested shape is a structural
inference by analogy with the confirmed `conn-ha1`/`conn-ha2` nesting — no
official source captured an example with a backup HA1 interface
configured; degrades safely to `None` either way, real-env confirmation
still owed (S8). `local-info/last-error-reason`/`last-error-state` path
presence is likewise unconfirmed by an official captured example.
`D-F1`/`D-F2`/`D-F3`, `D-V3a`, `D-V7b`, PAN `B2` all remain exactly as
unresolved as the predecessor build left them — do not let a future
session read "S2 shipped" as progress on any of them.

## 6. Continue or fresh chat

Either is fine.

## 7. main.py / UI effect

None. `include_preflight_fields` defaults `False` and the one production
call site does not pass it; `panorama/pan_preflight_projection.py` is not
imported by `main.py`, any collector call site, or any UI path yet — this
build is invisible in a normal run until S3 lands (CP side) and a later
slice (S5/S6) wires both into an actual collection run.
