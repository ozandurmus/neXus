# 0.6.x polish — HTML Render Performance Profiling

## Status

**PLANNED — architecture contract frozen 2026-08-30**

Product baseline: `0.7.6a AUTOMATED_VALIDATED`.

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
