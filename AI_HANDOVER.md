# AI_HANDOVER

Overwrite at every session close. Keep it minimal (see `AGENTS.md` "Handover
economy"): snapshot, what changed, exact next action, test delta, new risks.
No decision re-litigation, no doc-editing mechanics, no restating the phase doc.
Prior versions are in git history.

---

## 1. Snapshot

- Date: 2026-09-01.
- Product baseline `0.7.7`; engineering `DEV.3.3` — both AUTOMATED_VALIDATED,
  unchanged. `RB.3b` `in_progress` (hardware-gated), unchanged.
- `CON.1` `DONE`; `CON.2` `AUTOMATED_VALIDATED` (real-environment run still
  owed) — unchanged this session.
- New this session: **`OP.0a` AUTOMATED_VALIDATED** — contract frozen *and*
  implemented in one session. First delivered work on the `OP.x` Controlled
  Failover track.
- Branch: `main`.

## 2. What changed this session

`CON.3` was the requested next `CON.x` but is genuinely blocked — verified
against live files: `C-D4`/`C-D6` are still `"status": "open"` in
`project/roadmap.json`, and `RB.3b` is hardware-gated. `OP.0` was the one
unblocked track but had no frozen contract, so this session ran
`ARCHITECTURE` → `IMPLEMENTATION` end to end.

**The audit finding that shaped everything:** CP `cphaprob stat` (per-endpoint
and per-VS) and PAN `show high-availability state` are **already gated and
already collected** — the latter already parsing `peer_state` and
`state_sync`. The design doc had assumed all ~19 preflight commands were new.
So `OP.0` splits (contract P1): **`OP.0a`** engine over existing evidence,
zero new device commands, nothing blocking it; **`OP.0b`** the ~16-command
preflight battery behind a gate **drafted but not approved**; **`OP.0c`** the
§9 UI module.

- New `docs/history/phase/OP_0A_HA_READINESS_ASSESSMENT.md` — frozen contract
  (P1–P8, AC-1…AC-13) plus the `OP.0b` command-gate draft, plus deviations
  D1/D2 added during implementation.
- New `utils/failover/` — `__init__.py` + `assessment.py` **only**. `plan.py`
  / `executor.py` / `adapters/` are deliberately absent (P5, the
  `remove_dormant_remote_cleanup` precedent); AC-9 enforces the absence.
- New `application/workflows/failover.py` — offline loaders for CP/PAN HA
  evidence (fail-safe to `{}` on missing/corrupt) + the mode entrypoint.
- `main.py --ha-readiness-check` via `application/cli.py` — offline
  maintenance class, cross-guarded against every other mode; prerequisite
  `unified.json` registered in `application/services.py`.
- `configuration/checkpoint_config_collector.py` — P2's additive
  `_parse_clusterxl_cluster_mode` at both `cphaprob stat` call sites, reading
  the mode out of a buffer the collector already had before it discards it.
  **Same command, session, timeout and frequency** — a parse-scope extension,
  not a command addition, so no gate entry was required.
- `utils/collection_executor.py` — P6 allowlist comment only; no set change.
- New `tests/test_op0a_ha_readiness.py` (38 tests).
- `CURRENT_STATE.md`, roadmap, feature registry, build history, this file.

**The safety property to preserve:** `OP.0a` can never emit
`SAFE_TO_FAILOVER` or `DEGRADED_PROCEED_WITH_RISK` (P4). `AC-6` proves it by
exhaustive generated matrix, so a later edit cannot make a green light
reachable without also changing `OP0A_EVALUABLE_CHECKS` and its gate. Do not
"fix" that test by relaxing it.

## 3. Exact next action

Pick one; none depends on the others.

- **`OP.0b` gate review** — a product-owner/security call, not engineering.
  The drafted gate is a section of the `OP.0a` contract. Approving it unblocks
  the preflight battery, which is what makes `SAFE_TO_FAILOVER` reachable at
  all. Until then every unit reports `INSUFFICIENT_EVIDENCE`, by design.
- **`OP.0c`** — the §9 Failover UI module (seventh app module: fleet view,
  readiness light, history). Buildable now against `ha_readiness.json`;
  triggers the render harness and a `tests/fixtures/uitest/` growth step.
- **`CON.2` real-environment run** — corporate laptop, trigger a `read`-class
  job from the console, confirm it reaches a real device. No new code.
- **`RB.3b`** — the watched real-device R81.10/R81.20 run; unblocks `CON.3`
  and `RB.3c` at once.

`CON.3` remains blocked on `C-D4`/`C-D6` **and** `RB.3b`. Check `RB.3b` first;
if it is still hardware-gated, `CON.3` cannot start regardless of decisions.

## 4. Test delta

Full suite **973 passed / 27 skipped / 0 failed** (`pytest_result.log`),
from **933 / 27 / 2** after `CON.2`. `+38` from
`tests/test_op0a_ha_readiness.py`, zero regressions.

**Read the failure count honestly:** the two long-standing order-pollution
failures did not trigger under this run's `-n auto --dist worksteal`
distribution. They are **not fixed** — they are order-dependent and simply
were not provoked. Do not record "0 failed" as evidence they are resolved.

Privacy gate **PASS / 0** after deleting the gitignored `data/` + `logs/` a
test run creates. Render harness not triggered by this build (no
`templates/`, `static/` or payload-builder change) and green in the suite.

## 5. New risks / debt

- **`OP.0a` reads as a broken feature without its framing.** Every unit is
  `INSUFFICIENT_EVIDENCE` or `NOT_A_FAILOVER_UNIT` today. The CLI prints the
  framing itself ("`INSUFFICIENT_EVIDENCE` means 'not asked yet', not
  'unhealthy'"); `OP.0c`'s UI must carry the same line, or an empty-looking
  dashboard will be misread as a defect.
- **D2 is the cautionary tale of this build.** A healthy PAN active/passive
  pair was misreported as **split-brain** — a false alarm on a healthy pair,
  the worst direction to be wrong in. The unit tests missed it because they
  had paired only same-shaped records; the smoke run against the real fixture
  caught it immediately. Both directions are now pinned. Lesson for `OP.0b`:
  run the thing against the fixture bundle, not only against unit fixtures.
- **P7 PAN pairing is inference, not a discovered relationship.** A real,
  healthy pair whose configured `peer-ip` is not inventoried reports
  `pan_ha_peer_unresolved`. The durable fix is a discovery-plane peer field —
  a follow-up, deliberately not attempted here.
- **Owed before `OP.0a` is `DONE`:** one real-device confirmation that
  `ha_cluster_mode` resolves rather than falling back to `"unknown"`. The mode
  fixtures are constructed, not captured. Fixture-drift check, not a safety
  gate.
- Carried over: `tests/test_con1_*` / `tests/test_con2_*` have no top-level
  FastAPI/uvicorn skip guard. `C-D4`…`C-D8` remain open.

## 6. Continue or fresh chat

**Fresh chat.** `OP.0a` is closed to AUTOMATED_VALIDATED and every next option
is independent of this session's context. A cold start reading
`AI_START_HERE.md` → `CURRENT_STATE.md` → this file → the one contract doc is
sufficient.

## 7. main.py / UI effect

One new CLI flag, `main.py --ha-readiness-check` — offline, no credential, no
device contact, writes `data/state/ha_readiness.json` and prints a verdict
summary. No other flag, mode or exit-code path changed.

**No UI change, and that is intended.** This build touches no `templates/`,
`static/` or payload builder; the exported static report and the operator
console are byte-identical. The §9 Failover module is deliberately `OP.0c`.
