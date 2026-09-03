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
- This session: **`op0b_s1_preflight_fact_provenance_model`** — first
  `IMPLEMENTATION` slice against the FROZEN `OP.0b.0` contract. Pure domain
  model, zero I/O, zero network, no device contacted.

## 2. What changed this session

New `utils/failover/preflight_model.py`: frozen dataclasses `Provenance`,
`PreflightFact`, `PreflightMemberEvidence`, `PreflightSnapshot`; enums
`FactCategory` (the contract's 13-category A–M evidence taxonomy),
`SourceOrigin`, `Transport`, `ShellProfile`, `Outcome`, `FactState`,
`ContextKind`; `OpaqueToken` (a distinct `str` subtype marking an
already-tokenized identifier — S1 does not tokenize anything itself);
`evaluate_coherence()` (deterministic same-preflight-run coherence over
categories D/E/F/G/J/K; member-skew computed only when both members'
timestamps parse, `None` — never a fake zero — otherwise; a
`stale_intent_present` flag stated as a plain fact, not a threshold
judgment). `D-F1`/`D-F2`/`D-F3` are deliberately left unresolved — the model
carries the raw timestamps/counters a later, explicitly-thresholded check
can use, and picks no number itself.

One naming collision found and fixed during development: the enum
originally named `SourcePlane` tripped `test_ac9_failover_package_exposes_
no_write_capable_symbol`'s substring check (`"plan"` inside `"sourceplane"`)
— renamed to `SourceOrigin`.

One structural conflict found and resolved, flagged rather than silently
worked around: `utils/failover/__init__.py`'s own docstring and two
independent tests (`tests/test_architecture_convergence.py::test_the_
failover_package_still_contains_no_executor`,
`tests/test_op0a_ha_readiness.py::test_ac9_failover_package_contains_only_
assessment`) hard-coded the package to `{__init__.py, assessment.py}` only
(OP.0a decision P5). The FROZEN `OP.0b.0` contract's own slice table names
`utils/failover/preflight_model.py (new)` explicitly, at this exact path —
P5's actual intent (no write-capable/executor surface) is unaffected by a
pure evidence model, so both tests were updated, narrowly, to allow exactly
the one contract-named addition; the write-capable-symbol test still passes
unmodified (confirms nothing "plan"/"executor"/"action"/"rollback"/
"execute"/"failover_now"-shaped is exported).

23 new targeted tests (`tests/test_op0b_s1_preflight_model.py`) cover the
task's full required matrix: identity separation, same/mixed-run coherence,
configuration-intent independent provenance, no fake skew, deterministic
skew, no implicit freshness threshold (asserted by scanning the module
source for plausible magic-number literals), explicit `UNKNOWN` distinct
from `False`/`0`/empty and from a state that doesn't exist here
(`KNOWN_BAD`), peer-claim-without-observation, no phantom-member synthesis,
no raw identity required (+ an overlong-label rejection test), safe
serialization, dataclass immutability, distinct source planes for category
C vs D, presentation-only category's exclusion from the coherence set,
explicit `UNSUPPORTED`, and a privacy test with synthetic identifiers only.

Files touched: `utils/failover/preflight_model.py` (new),
`tests/test_op0b_s1_preflight_model.py` (new), `utils/failover/__init__.py`
(exports + docstring), `tests/test_architecture_convergence.py` +
`tests/test_op0a_ha_readiness.py` (P5 structural check widened by one named
file), `project/build_history.json`, `project/roadmap.json`,
`CURRENT_STATE.md`, `docs/history/INDEX.md` (regenerated), this file. No
collector, parser, readiness-verdict, schema, UI, or transport file touched.
No CLASS 2 behavior; `utils.action_taxonomy.CLASS_2_*` unchanged.

## 3. Exact next action

Per the contract's own `S0 → S1 → (S2, S3 in parallel)` sequence — S1 is
done and needed no `S0` result, resolving that session's flagged question:

1. **`OP.0b` S2** (`now_next.next`) — PAN parse-scope extension: wire
   `conn-*`/`running-sync`/sync/compat/election/flap parsing into
   `PreflightFact`/`Provenance` instances from the *already-collected*
   `show high-availability state` response. No new command.
2. **`OP.0b` S3** (`now_next.upcoming`) — same pattern, CP side. Independent
   of S2, can run in parallel.
3. Independent, unaffected by this session: `D-F3` numeric threshold,
   `D-V3a`/`D-V7b` pre-CLASS-2 closure, PAN serial identity closure
   (hardware-blocked).

## 4. Test delta

+23 (`tests/test_op0b_s1_preflight_model.py`), 2 pre-existing tests edited
(structural allow-list, not weakened elsewhere). Targeted suite (convergence
+ S1): 42/42 passed. Full regression in this container: 510 passed / 17
skipped / 33 failed / 81 errors — failed/error counts unchanged from the
prior session's baseline (missing `lxml`/`cryptography`/`paramiko`/
`fastapi`, pre-existing, unrelated to this change); the full-dependency
baseline (1099/24/0, 2026-09-02) is preserved in `CURRENT_STATE.md`, not
overwritten with this partial number.

## 5. New risks / debt

None introduced beyond what's already tracked. Explicit: S1 has no collector
wired to it yet — it is not load-bearing until S2/S3 land. `D-F1`/`D-F2`/
`D-F3` remain open. `D-V3a`/`D-V7b`/PAN `B2` are unaffected by this build —
do not let a future session read "S1 shipped" as progress on any of them.

## 6. Continue or fresh chat

Either is fine.

## 7. main.py / UI effect

None. `utils/failover/preflight_model.py` is not imported by `main.py` or
any collector/UI path yet — this build is invisible in a normal run until
S2/S3 wire it in.
