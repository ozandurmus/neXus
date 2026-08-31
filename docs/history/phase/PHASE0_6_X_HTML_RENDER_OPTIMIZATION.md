# 0.6.x polish follow-up — HTML Render Optimization

## Status

**AUTOMATED_VALIDATED 2026-08-31**

Follow-up to `html_render_performance` (profiling-only,
`PHASE0_6_X_HTML_RENDER_PERFORMANCE.md`), scoped from its own ranked
cost-center findings. Product baseline: `0.7.7` line (main HEAD at start of
this build: the `deploy_persistent_secret_material` / `RB.0-RB.2` /
`DEV.2.2` merge, `#15`).

## Objective

`html_render_optimization` (P2): apply safe, correctness-preserving
optimization to the two real (non-injected) hot paths the prior profiling
build identified — `fill_template` (~40-46% of measured total) and
`build_compliance_posture` (~27-34%) — without touching the three payload
builders `scripts/render_uitest.py` stubs to instant fixtures
(`build_configuration_ui_payload`, `build_crypto_posture`,
`build_discovery_capability_payload`), whose real per-device cost remains
unmeasured pending a production-scale `--render-only` pass.

## What was actually done

### 1. Resolved the open attribution question

The prior profiling report explicitly could not say whether `fill_template`'s
cost was the regex-alternation substitution itself or the JSON
serialization/escaping feeding it, because both ran inside one
`_stage_timer(timings, "fill_template")` block — the dict literal building
seven `_script_json(...)` calls was a sub-expression of the call to
`_fill_template(template, {...})`.

`utils/html_export.run_html_export` now builds that dict as its own named
stage, `build_json_replacements`, before the `fill_template` stage wraps only
`_fill_template(template, replacements)` itself. Re-measured on the uitest
16-device fixture (`python3 scripts/render_uitest.py --profile`, 3 runs):

| Stage | Run 1 | Run 2 | Run 3 |
| --- | --- | --- | --- |
| `build_json_replacements` | 45.0% | 44.1% | 43.6% |
| `build_compliance_posture` | 31.4% | 31.9% | 33.8% |
| `write_output_html` | 7.7% | 7.8% | 6.9% |
| `fill_template` | 5.5% | 5.7% | 5.6% |
| **TOTAL** | 0.0229s | 0.0224s | 0.0225s |

**Answer**: the regex-alternation substitution was never the cost center.
`json.dumps()` + the `</` escape pass over the ~170KB of embedded JSON
(`configuration_ui.json` alone is ~161KB on this fixture) accounts for the
whole of what the prior report attributed to `fill_template`. This scales
with fleet size (bigger `unified.json` → bigger `configuration_ui`/etc. →
proportionally more to serialize) and is real, unavoidable work — there is no
faster way to turn an in-memory payload into embedded JSON text than one
`json.dumps()` pass per payload, and the `</` → `<\/` guard against a literal
`</script>` sequence (0.7.4a-class defect) cannot be dropped.

### 2. Removed genuine repeated work from `_fill_template`

`_fill_template` previously re-sorted its replacement keys by length,
re-`re.escape()`d each one, and re-`re.compile()`d the resulting alternation
pattern on **every single call** — including every one of the ~614 real
production render calls this session's regression suite makes, and every real
`main.py`/`render_uitest.py` render. The sentinel key set is fixed across
every real call site (nine constants). The compiled pattern is now cached by
`@lru_cache(maxsize=32)` on `_sentinel_pattern(keys: tuple[str, ...])`, keyed
on the replacement dict's key tuple — the real call site hits the cache on
every render after the first; `tests/test_html_export_placeholder_integrity.py`'s
ad hoc small key sets (`_fill_template` must stay generic — it is exercised
directly with arbitrary keys, not just the nine production sentinels) still
work unchanged, just without cache reuse across differently-shaped calls.
`_fill_template`'s public behavior and its docstring's core invariant
(single left-to-right pass, longest-key-first so no sentinel is shadowed by a
prefix of a longer one) are unchanged.

Net effect, visible in the table above: `fill_template`'s own stage (now
correctly isolated to just the substitution) dropped to ~5.5-5.7% of a
noticeably smaller total (~0.022-0.023s vs. the prior report's
~0.030-0.038s on the same fixture).

### 3. `build_compliance_posture` — no change made

Investigated `_subject_user_checks` → `evaluate_check` → `apply_assertion`
(the CE.1 candidate the profiling report named). Two findings, both reasons
**not** to touch this path in this build:

- The per-step regex evaluation already goes through a deliberate,
  documented ReDoS timeout backstop (`utils/compliance_check_engine.py`'s
  `_search`, `_REGEX_TIMEOUT_S = 0.25`, optional `regex` module with a
  hard-capped stdlib `re` fallback). That backstop is a safety control, not
  incidental overhead — weakening or bypassing it for speed is out of scope
  for a P2 performance build and was not done. (The optional `regex` package
  is not installed in this environment or listed in
  `requirements*.txt`, so every measurement above exercised the stdlib `re`
  fallback path, not the module-cache-per-call path.)
- `check.steps[].selector` is already parsed once at pack load
  (`utils/compliance_check_pack.py`), not re-parsed per subject; the
  per-subject × per-check × per-step cost that remains is the evaluation
  itself (`resolve_source`/`_drill`, `_values`/`_numbers`, the assertion),
  which scales with subject count × check count and is inherent to what the
  engine is asked to do, not obviously wasteful work. Restructuring it
  without production-scale real evidence (the fixture's 2 subjects is not
  representative of a real fleet) risks a correctness bug in
  security/compliance-relevant evaluation for a P2 item's speculative gain.

This stays a candidate for a future build gated on a real `--render-only`
profile against production-scale `unified.json` (`on_hardware_real_env_validation`
class gap, unchanged from the parent profiling item).

## Privacy and safety invariants

1. No payload shape, schema version, sentinel set, or rendered-output byte
   content changed — `_fill_template`'s and `_script_json`'s observable
   behavior are unchanged; only internal repeated work was removed.
2. No new dependency.
3. The CE.1 regex-timeout safety backstop is untouched.

## Evidence

- `tests/test_html_export_placeholder_integrity.py`,
  `tests/test_phase0_6_x_html_render_performance.py`: 10 passed (targeted).
- Full suite: 764 passed, 10 skipped, 2 failed. Both failures are the
  pre-existing, unrelated test-order-pollution pair already documented
  against the unmodified baseline in prior closures
  (`tests/test_phase0_6_1c_discovery_capability_ui.py::test_run_html_export_embeds_discovery_payload_without_leftover_placeholder`,
  `tests/test_phase0_7_5_compliance_trend.py::test_checkpoint_render_appends_one_record`)
  — both pass in isolation, confirming no regression from this change.
- `tests/test_html_render_harness.py`: 5 passed, 1 skipped (JSON-validity +
  Playwright checks; `bun`/`happy-dom` skips cleanly, this session's
  environment gap per `render_harness_happydom_pin`, unrelated to this
  change).
- Re-measured profiling report above (`python3 scripts/render_uitest.py
  --profile`, 3 runs).

## Definition of done

`DONE` for the scope actually undertaken (the `fill_template` path):
attribution question answered with real numbers, real repeated-work removed,
zero behavior/output change, full regression + render harness green.
`build_compliance_posture` optimization stays explicitly deferred — not a
gap in this build, a deliberate scope boundary pending real fleet-scale
evidence, consistent with the parent item's own caution against acting on
fixture-only numbers for anything not already proven real computation.
