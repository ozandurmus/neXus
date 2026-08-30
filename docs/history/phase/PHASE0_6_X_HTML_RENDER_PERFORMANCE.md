# 0.6.x polish — HTML Render Performance Profiling

## Status

**DONE — AUTOMATED_VALIDATED 2026-08-30 (profiling only, per this contract's own scope)**

Product baseline: `0.7.6a AUTOMATED_VALIDATED`.

### Closure evidence and measured findings (2026-08-30)

**Instrumentation shipped**: `utils/html_export.py` gains a `_stage_timer`
context manager (a true no-op — no `time.perf_counter()` call at all — when
disabled) wrapping every stage named in this contract's scope, plus
`read_unified_json` and `load_compliance_history` for finer attribution.
Enabled via `run_html_export(..., profile=True)` or the
`SECURITYEXPERT_HTML_RENDER_PROFILE` env var (so a normal `main.py`
checkpoint needs zero call-site change); an explicit `profile=` kwarg always
overrides the env var. `scripts/render_uitest.py` gained a `--profile` flag.

**Measured on the uitest 16-device topology-matrix fixture** (3 runs,
`python3 scripts/render_uitest.py --profile`):

| Stage | Run 1 | Run 2 | Run 3 |
| --- | --- | --- | --- |
| `fill_template` | 45.9% | 37.4% | 41.9% |
| `build_compliance_posture` | 34.2% | 30.5% | 27.3% |
| `write_output_html` | 9.0% | — | — |
| `build_project_plan_payload` | 6.3% | — | — |
| everything else | <3% each | | |
| **TOTAL** | 0.030s | 0.037s | 0.038s |

**Critical caveat, read before drawing conclusions**: `render_uitest.py`
monkeypatches `build_configuration_ui_payload`, `build_crypto_posture`, and
`build_discovery_capability_payload` to return the fixture's pre-built JSON
directly (see `tests/fixtures/uitest/README.md`) — their **true** per-device
cost is therefore invisible in this measurement, showing as ~0.0000s
regardless of real cost. Only `build_compliance_posture`, `fill_template`,
and `write_output_html` reflect real computation in this run.
`--render-only` against a real, production-scale `unified.json` was **not
reachable this session** — this cloud environment has never run a real
collection checkpoint (no MDS/Panorama reachability, per
`AI_START_HERE.md`), so no real artifact exists to render against. That
real-scale profile remains owed, same class of gap as every other
`on_hardware_real_env_validation` item.

**Ranked cost centers, with the above caveat (AC-3)**:

1. **`fill_template`** (~40-46% of measured total) — the single-pass regex
   substitution (`_fill_template`) over the full template string against
   every embedded JSON payload. On this fixture the total embedded JSON is
   ~170KB (mostly `configuration_ui.json` at 161KB); this scales with fleet
   size and is a genuine, real signal (not skewed by the monkeypatch —
   `_fill_template` runs on the fixture's JSON regardless of whether that
   JSON came from a real builder or an injected file).
2. **`build_compliance_posture`** (~27-34%) — real computation on this run
   (not injected). Consistent with the contract's own pre-identified
   candidate: `_subject_user_checks` iterates the user-check pack once per
   subject (`utils/compliance_posture.py`), and the CE.1 engine's
   regex-based assertions carry an eval-time timeout as their safety
   backstop (a real per-subject × per-check cost, not a fixed one).
3. **`write_output_html`** (~9-20% depending on run, noisy at this scale) —
   a single `Path.write_text()` of the full rendered HTML string; likely
   dominated by string-object overhead at these small microsecond totals
   rather than actual disk I/O, and probably not a real hotspot at
   production scale relative to (1) and (2) — flagged as noisy, not a
   strong finding.

**What this does NOT establish**: whether `build_configuration_ui_payload` /
`build_crypto_posture` / `build_discovery_capability_payload` are hot at
real fleet sizes — they were not measured. Any follow-up optimization
contract must profile those for real (against `--render-only` on an actual
checkpoint) before touching them; the uitest-fixture numbers above must not
be used to justify optimizing the three injected builders.

Evidence: 6 new tests in `tests/test_phase0_6_x_html_render_performance.py`
proving AC-1 (opt-in, env-var + kwarg precedence, zero log output when
disabled) and AC-4 (the rendered HTML is byte-identical — modulo the
project-plan payload's own inherent per-call timestamp — with profiling on
vs. off). Full suite: 616 passed, 2 skipped, 2 failed (both pre-existing and
unrelated — same two tests already documented against the unmodified
baseline in every prior 0.6.x closure this session). Net +5 from baseline
611, zero regressions. `tests/test_html_render_harness.py` unaffected
(profiling is off by default in that harness's render path).

## Objective

Backlog item `html_render_performance` (P2): "Recent full HTML generation is
roughly one minute. Preserve correctness first, then profile
payload generation/serialization/rendering before optimization." This
contract covers **only the profiling phase** — establish where the wall-clock
time actually goes before touching any payload-builder code. Optimization
itself is explicitly a follow-up build, scoped only after the evidence exists.

## Scope

### In scope

- Add opt-in stage timing around `utils.html_export.run_html_export`: each
  payload builder call (`build_configuration_ui_payload`,
  `build_project_plan_payload`, `build_crypto_posture`,
  `build_compliance_posture`, `build_discovery_capability_payload`), template
  read, `_fill_template`, and the final `output_html.write_text`. Timing must
  be **off by default** (e.g. gated by an env var or a `profile=` kwarg) so it
  never runs in a normal collection checkpoint.
- Run the instrumented path against `scripts/render_uitest.py` (the existing
  16-device topology-matrix fixture used by the 0.7.6 render harness) and
  against `--render-only` on whatever real `unified.json` is available, and
  record per-stage timings.
- Identify the top cost centers with evidence (not guesswork). One concrete
  candidate already visible in the code: `utils/compliance_posture.py` builds
  a full per-subject × per-check evaluation (`_subject_user_checks` iterates
  `pack.checks` once per subject, `utils/html_export.py:116-127`) and the CE.1
  user-check engine runs a regex-based assertion per step with an eval-time
  timeout as its safety backstop (`AI_HANDOVER.md` §4 open risk) — this is a
  plausible hotspot to measure, not a confirmed one.
- Produce a short profiling report (in the closure doc for this build) with
  the measured per-stage breakdown and a ranked list of candidates for a
  follow-up optimization contract.

### Explicitly out of scope

- Any actual optimization, caching, algorithmic change, or payload-builder
  rewrite. This build produces *evidence*, not a fix.
- Any change to collector timing, network I/O, CAS/history read pattern, or
  the render harness itself.
- Any change to `main.py`'s stage ordering or the admission coordinator.

## Privacy and safety invariants

1. Profiling output (timings) contains no device identity, credential, or raw
   configuration — only stage names and durations.
2. No new dependency; use `time.perf_counter()` only (already available in
   `utils/config_evidence.py`), no external profiler library.
3. Instrumentation must be provably inert when disabled (no import-time cost,
   no behavior change in default `main.py` runs).

## Implementation plan

1. Add a `profile: bool = False` parameter (or read an env var, whichever is
   less invasive to the call sites in `main.py`) threaded into
   `run_html_export`; when set, wrap each stage in `time.perf_counter()` and
   log via `utils.logger.info` at the end with a per-stage table.
2. Run against the uitest fixture and (if reachable this session) a real
   `--render-only` pass; capture the numbers.
3. Write up findings as the closure section of this same doc — no separate
   handover doc needed for a profiling-only build.
4. Full regression + render harness (`tests/test_html_render_harness.py`)
   must still pass unchanged with profiling disabled (default).

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | Stage timing is available opt-in and produces zero output/behavior difference when disabled. |
| AC-2 | A measured per-stage timing breakdown exists for at least the uitest fixture. |
| AC-3 | The report names the top 1-3 cost centers with numbers, not assumptions. |
| AC-4 | No payload shape, schema version, or builder logic changes. |
| AC-5 | Full regression + render harness pass unchanged. |

## Validation and merge gate

Dev/CI-only change (like 0.7.6); no real-device run required. Merge to `main`
requires AC-1 through AC-5 and a clean privacy gate.

## Definition of done

`DONE` when the profiling report is committed with real measured numbers and
the ranked hotspot list is written up as `project/backlog.json` follow-up
notes (a candidate `html_render_optimization` item scoped from the findings).
This contract does **not** close until the report exists — an unmeasured
"looks fine" is not acceptance.
