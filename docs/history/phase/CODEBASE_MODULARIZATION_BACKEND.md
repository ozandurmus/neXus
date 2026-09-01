# Codebase modularization — backend (`main.py` orchestration split)

## Status

**IMPLEMENTED 2026-09-01** (`Sonnet 5, normal`), same day as the contract
freeze, by a fresh implementation session. `main.py` (2,089 lines) is now a
47-line thin entry; the `application/` package matches the ownership table
below. Full suite **896 passed / 26 skipped / 2 failed** (the 2 are the same
pre-existing order-pollution failures noted throughout this doc's build
history; +9 from the new `tests/test_application_package.py`), zero
regressions vs this branch's pre-build baseline (887/26/2). Privacy gate
PASS/0. AC-1 … AC-8 all green — see "Implementation deviations" below for the
handful of judgment calls a line-verified contract-vs-test read still left
open.

Original freeze note (superseded by the above but kept for history):
**CONTRACT FROZEN 2026-09-01.** Produced as a `SCOPE → AUDIT → CONTRACT` pass
(`Sonnet 5, normal`) immediately after `codebase_modularization` (frontend)
landed. No source file changed by this pass — `main.py`, the vendor collectors,
`utils/*`, and every test are untouched. Ready for a fresh session to
implement against.

### Implementation deviations

1. **Two test files could not stay byte-identical under AC-1.**
   `tests/test_phase0_6_0a4_3_3_2_workflow_and_ha.py` asserted on the literal
   text of `main.py` (e.g. `MAIN.index("if args.render_only:") <
   MAIN.index("from checkpoint.cp_runner import run_cp")`) and patched
   `main_module.info` by name before calling `main_module._cp_stage_cooldown`;
   `tests/test_phase0_6_1c_1_...` and five other UI/coverage test files carried
   the same "read main.py as text" pattern for strings that moved into
   `application/workflows/checkpoint.py` or `application/cli.py`.
   `tests/test_dev_2_1_noninteractive_runtime_config.py` patched
   `main.register_sensitive_value` by name before calling
   `main._build_runtime_config`. Both patterns assume the target function
   still lives in `main`'s namespace — incompatible with AC-1's ≤120-line
   `main.py` by construction, not a behavior assertion. Put to the product
   owner directly; resolved to: keep `main.py` minimal, repoint the ~8 affected
   assertions/patches to the new `application/*.py` locations. Same class of
   mechanical repoint the frontend half applied to 16 source-string UI tests.
   No test's *behavioral* assertion changed — only *where* it reads the source
   text from, or *which module's* name it patches.
2. **F5's three de-closured helpers stayed nested, not module-level.**
   `_pan_config_limit_for_mode` / `_require_partial_inputs` /
   `_render_partial_inventory` are defined inside
   `checkpoint.integration_checkpoint` rather than at `checkpoint.py` module
   scope. They still no longer close over a 1,690-line `main()` scope (the
   stated problem) — only over `integration_checkpoint`'s own parameters — and
   staying nested keeps the Phase-E lazy vendor-import cluster in exactly one
   place rather than spreading it across module-level helper functions that
   would each need their own lazy imports of `run_merge`/`run_html_export`.
3. **A real pre-existing lazy-import gap, found by the new AC-3 check, not
   introduced by this build.** `main.py`'s top-level
   `from utils.config_storage import analyze_configuration_storage, ...`
   transitively imported `lxml` (via `utils/config_evidence.py`) on *every*
   invocation, including `--repository-privacy-check` — contradicting
   `AI_START_HERE.md`'s documented "vendor imports are lazy" contract. F2's
   audit believed this boundary already held; it did not. Closed by moving
   that import into `storage_analyze()` / `storage_deduplicate()` (first use)
   in `application/workflows/maintenance.py` — zero output or exit-code
   change, only import timing.
4. **`register_sensitive_value` / `info` are not re-exported from `main`.**
   Only the F4 names plus `_cp_stage_cooldown` and `_run_scheduler_once` (both
   directly called/patched by existing tests, which the original F4 audit
   undercounted) are re-exported. `main.py` still imports `info` and
   `register_sensitive_value` from `utils.logger` for readability/history, but
   nothing depends on that binding after deviation 1's repoints.

`project/backlog.json` `codebase_modularization` (P1) — this is the **backend
half** of that entry; the frontend half is IMPLEMENTED
(`docs/history/phase/CODEBASE_MODULARIZATION_FRONTEND.md`). Scoped from
`docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md` §5
"Backend: split at orchestration and vendor boundaries" — this contract is the
concrete, line-verified version of that section's directional proposal,
covering the **`main.py` orchestration seam only**. The vendor-collector split
(`configuration/pan/`, `configuration/checkpoint/`) is explicitly **not** in
this build — §5 says those move "only when touched by a bounded feature", and
this build touches none.

## Objective

`main.py` is 2,089 lines. Its `main()` function alone is ~1,690 lines (399 →
2,089) and carries every responsibility the application has: the full argparse
surface (~30 flags), all mutually-exclusive-mode validation (~40
`parser.error` checks), the runtime-path / logging / evidence-backend /
coordinator / scheduler bootstrap, and the bodies of **sixteen** distinct CLI
mode blocks — the eight offline maintenance/check modes the code groups as
`maintenance_modes` (`--repository-privacy-check`, `--storage-analyze`,
`--storage-deduplicate`, `--persistent-secret-material-check`,
`--restore-readiness-check`, `--recovery-store-check`, `--recovery-validate`,
`--compliance-trend-reconstruct`), `--scheduler-once`, `--recovery-collect`,
`--recovery-attest`, `--cp-config-probe`, `--cp-config-collect`,
`--render-only`, the `--only pan-config` development path, and the full staged
integration checkpoint (`--only all`: collect → config → snapshot → merge →
verify → html → support-bundle) with its degraded-status policy and
`RunContext` lifecycle. There is no internal structure beyond a long sequence
of `if args.<mode>:` blocks sharing ~15 local variables in one scope and one
`try/except/finally`.

This build makes `main.py` the thin CLI/bootstrap layer §5 calls for: argument
parsing and dispatch only. Each mode group moves to a responsibility-owned
module under a new `application/` package, reached through one typed
entrypoint per group, with the shared bootstrap state passed explicitly as an
`ApplicationContext` instead of threaded implicitly through one function.
**Zero behavior, output, exit-code, command-string, or artifact change** — a
code-health/maintainability build, not a feature, exactly as the frontend half
was.

## Scope

### In scope

1. New `application/` package holding the split:
   `application/cli.py`, `application/services.py`,
   `application/context.py`, `application/workflows/{checkpoint,recovery,maintenance}.py`
   (see "Design decisions" for exact ownership).
2. `main.py` reduced to a thin entrypoint: `main(...)` delegates to
   `application.cli.run(argv, …)`, the `if __name__ == "__main__"` guard stays,
   and it **re-exports** the small set of module-level names the existing test
   suite imports from it (D-MOD-B2). Parser construction lives entirely in
   `application.cli`.
3. An explicit `ApplicationContext` dataclass (`application/context.py`)
   carrying the shared bootstrap state (`args`, `parser`, `runtime_paths`,
   `support_bundle_output_root`, lazily-built `services`, `provenance`,
   `admission_run_context`) that `main()` currently holds as locals.
4. A new regression test that statically proves the lazy-import boundary is
   intact — `application.cli` / `application.services` / `application.context`
   import no vendor, transport, or heavy-parser module at module-load time
   (see AC-3), plus that `main.main` and every historically-imported
   `main.<name>` still resolves.
5. Behavior-parity proof: the full existing suite (12 files drive
   `main.main(...)` directly), plus a before/after CLI transcript diff over a
   representative mode matrix (AC-4).

### Explicitly out of scope

- **The vendor-collector split** — `configuration/panorama_config_collector.py`
  (2,595 lines), `configuration/checkpoint_config_collector.py` (1,941),
  `configuration/checkpoint_config_probe.py` (976), `checkpoint/cp_runner.py`,
  `panorama/panorama_runtime_runner.py`. §5: "split only when touched by a
  bounded feature." This build touches none of them and must not start.
- `utils/collection_executor.py`, `utils/run_context.py`,
  `utils/html_export.py`, `utils/merge.py`, `utils/snapshot.py`,
  `utils/verification.py`, `utils/support_bundle.py` — the orchestration
  *callees*. Their signatures and behavior are fixed; this build only moves
  the code that *calls* them.
- Any change to a CLI flag, its help text, its default, its
  mutually-exclusive rules, any mode's stdout/stderr, any exit code, any
  emitted artifact, any command string, admission-coordination call, or
  redaction-before-persistence step.
- Introducing a CLI framework (`click`, `typer`), a plugin/entry-point
  mechanism, or a config file. `argparse` stays; `main.py` stays the only
  build tool.
- The `CON.x` console (`OPERATOR_CONSOLE_ARCHITECTURE.md`) — it reuses
  `main(argv, runtime_services, provenance)` and this build must keep that
  entry signature and its semantics **exactly** (see D-MOD-B7).
- Type-annotating or refactoring the moved code beyond what the extraction
  mechanically requires. A helper that reveals an obvious cleanup is a noted
  follow-up, not folded in — same discipline the frontend half and
  `frontend_rendering_boundary` applied.

## Audit findings (this session, 2026-09-01)

Verified against a full read of `main.py` (2,089 lines) and its coupling to the
test suite.

### F1 — `main()` is one 1,690-line function with five execution phases

`main()` spans lines 399–2,089. In file order:

| Phase | Lines | Content |
| --- | --- | --- |
| **A — argument surface** | ~402–759 | ~30 `parser.add_argument(...)` calls; `args = parser.parse_args(argv)` (651); ~40 `parser.error(...)` mutually-exclusive-mode checks (653–759). No I/O, no runtime state. |
| **B — pre-runtime maintenance** | 761–835 | `--repository-privacy-check`, `--storage-analyze`, `--storage-deduplicate` — each does its work and `return`s **before** `resolve_runtime_paths`. Deliberately offline: no RuntimeRoot, no credentials, no collector import. |
| **C — shared runtime foundation** | 835–860 | `support_bundle_output_root`; `resolve_runtime_paths`; `configure_log_root`; the `>>> RUNTIME PATH FOUNDATION READY` print; the `verify_evidence_backend_ready()` / `active_evidence_backend_kind()` preflight (DEV.3.3). Runs for every mode that clears Phase B. |
| **D — single-purpose modes** | 862–1519 | `--persistent-secret-material-check` (862); `--restore-readiness-check` (893); `--recovery-store-check` (941); `--recovery-validate` (980); `--compliance-trend-reconstruct` (1040); services construction — coordinator backend, scheduler-policy load, the `_admitted` closure (1056–1098); `--scheduler-once` (1082); `--recovery-collect` (1112–1256); `--recovery-attest` (1257–1348); `--cp-config-probe` (1349–1386); `--cp-config-collect` (1387–1487); `--render-only` (1488–1519). Each block ends in `return` or `raise SystemExit(...)`. |
| **E — integration checkpoint** | 1520–2083 | The heavy collector imports (1520–1530); `_pan_config_limit_for_mode` / `_require_partial_inputs` / `_render_partial_inventory` nested helpers (1562–1611); the staged pipeline `cp → vsx → cp_config → panorama → pan_config → snapshot → merge → verify → html → support-bundle` with per-stage `RunContext` start/capture/finish and the degraded-vs-success status policy; the enclosing `try / except KeyboardInterrupt / except Exception / finally: cfg.clear_credentials()`. |

### F2 — the lazy-import boundary is load-bearing and currently enforced only by placement

`main.py`'s module-level imports (lines 1–21) are dependency-light:
`argparse/getpass/json/os/sys/time/pathlib`, `utils.logger`,
`utils.config_storage`, `utils.runtime_config_source`, `utils.runtime_paths`,
`config.Config`. **Every** vendor / transport / heavy-parser import is lazy —
inside a mode block or inside `main()` just before Phase E:

- `from checkpoint.cp_runner import run_cp` etc. at **1520–1530**, reached only
  by the integration checkpoint and `--only` partials;
- `from panorama.panorama_recovery_collector import …` at 1161, inside
  `--recovery-collect`;
- `from configuration.checkpoint_config_probe import …` at 1354, inside
  `--cp-config-probe`;
- 20+ more `from utils.X import …` scattered through Phases B/D at first use.

`AI_START_HERE.md` states this as a contract: "Vendor/config imports are lazy
— maintenance modes return before touching them." Nothing tests it. After the
split, `application/cli.py` and `application/services.py` load on **every**
invocation; if either grows a top-level vendor import, a `--repository-privacy-check`
run silently starts importing `paramiko`/`lxml`/`requests`. AC-3 exists to make
that boundary a tested invariant — this is the one new guarantee this build
adds that the flat file could not have had, the direct analogue of the frontend
half's AC-3 ordering check.

### F3 — shared state is ~15 locals in one scope, not a passed context

Phase C/D/E share, by lexical scope: `args`, `parser` (for `parser.error`),
`runtime_paths`, `support_bundle_output_root`, `services`, `cfg`, `run_ctx`,
`current_stage`, `report`, `config_result`, `checkpoint_config_result`,
`inventory_support_path`, plus `provenance` / `admission_run_context` /
`runtime_services` from `main()`'s own signature. `services` is built once
(1056–1098) and consumed by Phases D and E. `cfg` is built lazily in Phase E
(or per-mode in D) and **must** be `cfg.clear_credentials()`-d in the `finally`.
An extraction that splits these blocks across modules must pass this state
explicitly or reproduce the bug where a mode forgets to clear credentials.

### F4 — the test suite imports `main` as a stable surface

`grep` over `tests/`: **12 files** call `main.main(...)` directly. Beyond the
entrypoint, tests import these module-level names from `main`:
`main._bootstrap_gaps`, `main._build_runtime_config`,
`main._load_recovery_attestations`, `main._prompt_management_endpoint`,
`main._require_bootstrap`, `main._scheduler_workflow_argv`, `main.Config`
(re-exported `from config import Config`). No test imports from a submodule of
`main` (there are none). The split must keep `import main; main.main(...)` and
every `main.<name>` above resolving — via re-export, not by rewriting 12 test
files (D-MOD-B2; contrast the frontend half, which repointed 16 source-string
reads because there `static/app.js` genuinely ceased to exist).

### F5 — three nested helpers in Phase E close over `main()` locals

`_pan_config_limit_for_mode` (reads `args.pan_config_limit` /
`args.pan_config_stage` / `args.only`), `_require_partial_inputs` (reads
`runtime_paths`), `_render_partial_inventory` (reads `runtime_paths`,
`services`, calls `run_merge` / `run_html_export`). They are only used by the
`--only cp` / `--only vsx` / `--only pan-config` partial-render paths. After the
split they become module-level functions in `application/workflows/checkpoint.py`
taking their inputs as parameters — a mechanical de-closure, no logic change.

### F6 — top-level helpers already exist and group cleanly

`main.py` lines 29–398 hold 14 module-level helpers/constants. Their natural
owners:

| Helper(s) | Owner |
| --- | --- |
| `_load_output_json`, `_remove_output_files` | `application/services.py` (shared I/O) |
| `_ARTIFACT_PRODUCERS`, `_MODE_PREREQUISITES`, `_BOOTSTRAP_SEQUENCE`, `_bootstrap_gaps`, `_require_bootstrap` | `application/services.py` (bootstrap-prerequisite gate — used by Phases D and E) |
| `_workflow_context` | `application/services.py` (used by checkpoint, recovery-render, `--render-only`, partials) |
| `_load_recovery_attestations` | `application/workflows/recovery.py` |
| `_env_int`, `_cp_stage_cooldown` | `application/workflows/checkpoint.py` |
| `_PRINCIPAL_VAR`/`_SECRET_VAR`/`_CP_MDS_ENDPOINT_VAR`/`_PANORAMA_ENDPOINT_VAR`, `_prompt_management_endpoint`, `_resolve_or_prompt`, `_build_runtime_config` | `application/services.py` (runtime-config construction) |
| `_scheduler_workflow_argv`, `_run_scheduler_once`, `_evaluate_and_dispatch_due_workflows` | `application/workflows/maintenance.py` (`--scheduler-once` lives there) |

## Design decisions

### D-MOD-B1 — one `application/` package, module-per-phase, not a rewrite

§5: "This is a direction, not an immediate filesystem migration." The split is
**mechanical relocation** of existing blocks into modules, each block moved
verbatim except for de-closuring locals into parameters. No control-flow
redesign, no async, no dependency-injection framework. The dispatch in
`main()` stays a linear sequence of `if <mode>:` checks in the same order they
run today; each `if` body becomes a single call to a workflow entrypoint.

### D-MOD-B2 — `main.py` stays the public surface; re-export, don't rewrite tests

`main.py` keeps `def main(argv=None, *, runtime_services=None, provenance="manual", admission_run_context=None)`
and the `if __name__ == "__main__": main()` guard. `main()` becomes a
one-line body: `return application.cli.run(argv, runtime_services=runtime_services, provenance=provenance, admission_run_context=admission_run_context)`,
where `application.cli.run` does `build_parser()` → `parse_args` →
`validate_modes` → `dispatch`. `main.py` also carries, as explicit re-exports
at module scope, every name F4 lists (`_require_bootstrap`,
`_build_runtime_config`, `_bootstrap_gaps`, `_load_recovery_attestations`,
`_prompt_management_endpoint`, `_scheduler_workflow_argv`, `Config`), each
`from application.… import … as …`.
This is the D-MOD2 analogue from the frontend half: the enforcement tool is
AC-3's static check plus the unchanged 12 `main.main()` test files, not a
namespace rename that ripples into tests.

### D-MOD-B3 — `ApplicationContext` carries shared state explicitly

New frozen-ish dataclass in `application/context.py`:

```text
ApplicationContext
  args                     argparse.Namespace
  parser                   argparse.ArgumentParser        (for parser.error in a workflow)
  runtime_paths            RuntimePaths | None            (None only for Phase-B modes)
  support_bundle_output_root  Path | None
  provenance               str
  admission_run_context    object | None
  services                 RuntimeCollectionServices | None   (lazily built; see D-MOD-B5)
```

Workflow entrypoints take `(ctx: ApplicationContext)` and return `int | None`
(an exit code, or `None` to mean 0). `parser.error()` and
`raise SystemExit(code)` are kept exactly where they fire today — a workflow
may still `parser.error(...)` via `ctx.parser`. No new exception type, no new
return protocol beyond the `int | None` the CLI already implies.

### D-MOD-B4 — `application/cli.py` owns the argument surface and dispatch order

`cli.py` holds: `build_parser() -> ArgumentParser` (Phase A add_argument
block, verbatim), `validate_modes(args, parser)` (Phase A mutually-exclusive
`parser.error` block, verbatim, one function),
`dispatch(args, parser, *, runtime_services, provenance, admission_run_context)`
— the linear mode sequence — and the thin
`run(argv, *, runtime_services, provenance, admission_run_context)` that
`main.main` calls (`build_parser` → `parse_args` → `validate_modes` →
`dispatch`). `dispatch` runs Phase B inline (the three pre-runtime modes call
straight into `application.workflows.maintenance`), then builds the runtime
foundation (Phase C, via `application.services`), constructs the
`ApplicationContext`, and calls exactly one workflow entrypoint. `cli.py`
imports only `argparse`, `application.services`, `application.context`, and
the three workflow modules — **never** a vendor module (AC-3).

### D-MOD-B5 — `application/services.py` owns bootstrap; services stay lazily built

`services.py` holds Phase C (`resolve_runtime_paths` + `configure_log_root` +
the evidence-backend preflight, as `build_runtime_foundation(args, parser)`),
the F6 runtime-config helpers (`_build_runtime_config` and its prompt chain),
the bootstrap-prerequisite gate (`_require_bootstrap` / `_bootstrap_gaps` /
the three constant maps), `_workflow_context`, and the shared
`_load_output_json` / `_remove_output_files`. It also holds
`build_collection_services(args, runtime_paths, runtime_services) -> RuntimeCollectionServices`
(the current 1056–1098 block: coordinator-backend selection, scheduler-policy
load, the `_admitted` closure factory). This is called **only** by the
workflows that need it (`checkpoint`, `recovery`, `--scheduler-once`), keeping
its `utils.collection_executor` import off the maintenance-only path exactly as
today. `services.py` imports no vendor module (AC-3).

### D-MOD-B6 — three workflow modules, matching §5, plus what F1 shows they must hold

| Module | Owns (§5 wording, then this build's verified content) |
| --- | --- |
| `application/workflows/maintenance.py` | "privacy, storage, render and diagnostic modes" — `--repository-privacy-check`, `--storage-analyze`, `--storage-deduplicate` (Phase B), `--persistent-secret-material-check`, `--compliance-trend-reconstruct`, `--render-only`, `--scheduler-once` (+ `_scheduler_workflow_argv` / `_run_scheduler_once` / `_evaluate_and_dispatch_due_workflows`). Note: `_evaluate_and_dispatch_due_workflows` re-invokes the top-level entry per due workflow (today `main(...)` at `main.py:~361`); post-split it calls `application.cli.dispatch(...)` (or `main.main`), an entry-ward call that is not the "no workflow imports another workflow" rule AC-3 enforces. |
| `application/workflows/recovery.py` | "recovery/attestation modes" — `--restore-readiness-check`, `--recovery-store-check`, `--recovery-validate`, `--recovery-collect`, `--recovery-attest` (+ `_load_recovery_attestations`). Each keeps its own lazy `from utils.recovery_* import …` / `from checkpoint.checkpoint_recovery_* import …` inside the entrypoint. |
| `application/workflows/checkpoint.py` | "full-stage orchestration and degraded-status policy" — Phase E in full (the staged pipeline, `RunContext` lifecycle, degraded/success policy, the `try/except/finally` with `cfg.clear_credentials()`), the `--only` partial paths, `--cp-config-probe`, `--cp-config-collect` (Check Point configuration modes are the same vendor and share the CP-config lazy import), and F5's three de-closured helpers, `_env_int`, `_cp_stage_cooldown`. The heavy collector imports stay lazy **inside** the entrypoint. |

Rationale for `--cp-config-probe` / `--cp-config-collect` landing in
`checkpoint.py` rather than a fourth "config" module: they are Check Point
device-configuration collection, share
`from configuration.checkpoint_config_collector import run_checkpoint_config_collection`
with Phase E, and §5 names only three workflow files. A fourth module is a
possible follow-up if `checkpoint.py` stays uncomfortably large after the
split (F1: Phase E alone is ~560 lines); it is **not** created pre-emptively.

### D-MOD-B7 — the `main()` entry signature and its `CON.x` contract are frozen

`main(argv=None, *, runtime_services=None, provenance="manual", admission_run_context=None)`
stays byte-identical, including `runtime_services` pass-through (used by the
scheduler at `main.py:~361` today and by the future console). `dispatch`
threads all four straight through. The `provenance` string values
(`"manual"` / `"scheduled"`) and `admission_run_context` semantics are
unchanged. Any change here re-opens `CON_2_CONSOLE_JOB_ENGINE_READ_ACTIONS.md`
— out of bounds for a code-health build.

### D-MOD-B8 — lazy imports preserved exactly; verified, not assumed

Every lazy `from … import …` currently inside a mode block moves **with that
block** into its workflow module and stays inside the entrypoint function (not
hoisted to that module's top). The Phase-E collector import cluster
(`cp_runner` / `vsx_runner` / `panorama_runtime_runner` /
`panorama_config_collector` / `checkpoint_config_collector` / `merge` /
`html_export` / `verification` / `run_context` / `support_bundle` / `snapshot`)
moves into `checkpoint.py`'s entrypoint as a single lazy block, exactly as it
sits at `main.py:1520-1530` today. AC-3 asserts the `application/` top-level
(cli/services/context) stays vendor-free; a companion assertion checks the
workflow modules do not import vendors at **module** scope either.

## Module ownership (amends architecture doc §5)

```text
main.py                     thin entry: one-line main() -> application.cli.run,
                            __main__ guard, re-exports of the F4 public names
application/
  __init__.py
  cli.py                    build_parser, validate_modes, dispatch (mode order), run
  context.py                ApplicationContext dataclass
  services.py               build_runtime_foundation, build_collection_services,
                            _build_runtime_config (+ prompt chain), _require_bootstrap
                            (+ _bootstrap_gaps + the 3 constant maps), _workflow_context,
                            _load_output_json, _remove_output_files
  workflows/
    __init__.py
    maintenance.py          repository-privacy / storage-analyze / storage-deduplicate /
                            persistent-secret-material / compliance-trend-reconstruct /
                            render-only / scheduler-once (+ scheduler helpers)
    recovery.py             restore-readiness / recovery-store-check / recovery-validate /
                            recovery-collect / recovery-attest (+ _load_recovery_attestations)
    checkpoint.py           integration checkpoint (Phase E) + --only partials +
                            cp-config-probe / cp-config-collect + _env_int +
                            _cp_stage_cooldown + the 3 de-closured Phase-E helpers
```

**Import direction** (enforced by AC-3): `main.py → application.cli →
{application.services, application.context, application.workflows.*}`;
`application.workflows.* → {application.services, application.context}` and
(lazily, in-function) the vendor / `utils.*` callees. No workflow module
imports another workflow module. `cli.py`, `services.py`, `context.py` import
no vendor/transport/heavy-parser module at load time.

## Acceptance criteria

- **AC-1** `main.py` is ≤ ~120 lines: module docstring, imports, `main()`
  (a one-line delegation to `application.cli.run`), the `__main__` guard, and
  the F4 re-exports. Every mode body, every Phase-C bootstrap step, the Phase-A
  parser, and every F6 helper now lives in `application/` per the ownership
  table, moved **verbatim** apart from de-closuring locals into parameters.
  Zero mode behavior change; any placement that differs from the §5 three-file
  proposal is because D-MOD-B5/B6 said so.
- **AC-2** `application/cli.py`'s `dispatch` runs the exact same mode-precedence
  order as today's `main()` (`repository-privacy` → `storage-*` → runtime
  foundation → `persistent-secret-material` → `restore-readiness` →
  `recovery-store-check` → `recovery-validate` → `compliance-trend-reconstruct`
  → services → `scheduler-once` → `recovery-collect` → `recovery-attest` →
  `cp-config-probe` → `cp-config-collect` → `render-only` → integration
  checkpoint). Same `parser.error(...)` strings, same exit codes, same
  `SystemExit` values.
- **AC-3** A new static regression test asserts: (a) importing
  `application.cli`, `application.services`, `application.context` pulls in no
  module under `checkpoint/`, `panorama/`, `configuration/`, and none of
  `paramiko` / `lxml` / `requests` (checked via `sys.modules` delta around a
  clean import, this repo's pragmatic-check style — no import hook); (b) the
  three `application.workflows.*` modules likewise import no vendor module at
  **module** scope; (c) `main.main` is callable and every name in F4 resolves
  as `getattr(main, name)`.
- **AC-4** CLI behavior parity: the full existing suite is green (12 files
  exercise `main.main(...)` across maintenance, recovery, config, scheduler and
  render modes), **and** a before/after stdout+exit-code transcript diff over a
  representative offline matrix (`--help`; `--repository-privacy-check`;
  `--storage-analyze`; `--restore-readiness-check`; `--recovery-store-check`;
  `--render-only`; `--recovery-collect` with no `--recovery-vendor`;
  three representative `parser.error` collisions) is empty except for run-scoped
  noise (timestamps, tmp paths).
- **AC-5** The lazy-import boundary holds at runtime, not just structurally: a
  `--repository-privacy-check` run (offline mode, Phase B) imports no vendor
  module — asserted by `sys.modules` inspection after `main.main([...])` in a
  subprocess-clean or `importlib`-reload harness.
- **AC-6** `main()`'s entry signature is unchanged
  (`inspect.signature(main.main)` byte-compared to the frozen string in the
  test) and `runtime_services` / `provenance` / `admission_run_context` still
  thread through to the workflow that consumes them (covered by the existing
  scheduler-wiring and recovery tests).
- **AC-7** Repository privacy gate PASS/0 on a clean checkout.
- **AC-8** Full suite at or above the pre-build baseline; render harness green
  (this build does not touch `static/` or `utils/html_export.py`, but the
  integration-checkpoint path calls `run_html_export`, so the full suite's
  render-harness tests must still pass).

## Implementation plan

Sequenced so the risky move (Phase E) is last and every step is independently
green.

1. **Create `application/` skeleton** — `__init__.py`, `context.py` with the
   `ApplicationContext` dataclass, empty `cli.py` / `services.py` /
   `workflows/*.py`. No behavior yet.
2. **Move Phase C + F6 shared helpers into `services.py`**
   (`build_runtime_foundation`, `_build_runtime_config` + prompt chain,
   `_require_bootstrap` + `_bootstrap_gaps` + constant maps, `_workflow_context`,
   `_load_output_json`, `_remove_output_files`, `build_collection_services`).
   `main.py` imports them back and calls them in place. Full suite green.
3. **Move `maintenance.py` modes** (Phase B three + persistent-secret-material +
   compliance-trend-reconstruct + render-only + scheduler-once and its
   helpers). `main()` now calls `application.workflows.maintenance.<fn>(ctx)`
   for each. Green.
4. **Move `recovery.py` modes** (restore-readiness, recovery-store-check,
   recovery-validate, recovery-collect, recovery-attest, `_load_recovery_attestations`).
   Green.
5. **Move `checkpoint.py`** — `--cp-config-probe`, `--cp-config-collect`, then
   Phase E in full (the staged pipeline, `RunContext` lifecycle, degraded
   policy, the `try/except/finally`), plus `_env_int`, `_cp_stage_cooldown`, and
   F5's three de-closured helpers. The heavy import cluster becomes one lazy
   block inside the entrypoint. Green.
6. **Move the Phase-A parser** (`build_parser` / `validate_modes`) and the
   dispatch sequence into `cli.py` as `run` / `dispatch`; **reduce `main.py`**
   to docstring + imports + one-line `main()` + `__main__` + F4 re-exports.
7. **AC-3 / AC-5 tests**, then AC-4 transcript diff, then AC-7 / AC-8 — the
   static and lazy-import checks are cheap and catch a bad move before the
   full-suite / transcript runs.
8. **Project metadata**: `project/build_history.json`,
   `project/backlog.json` (`codebase_modularization` note: frontend + backend
   halves both done — decide `in_progress` vs `done` per whether the
   vendor-collector split is considered a third, separate future item, which
   this contract says it is → the id can move to `done` when the backend half
   lands, with vendor-collector work tracked as a fresh backlog entry if/when a
   feature needs it), `CURRENT_STATE.md`, this doc's Status → `IMPLEMENTED`,
   `AI_HANDOVER.md`.

## Validation and merge gate

- Full suite one-shot: `py -m pytest -q > pytest_result.log 2>&1`. Record the
  branch-vs-`main` delta the way the frontend half did.
- Repository privacy gate PASS/0.
- Render harness green (full-suite coverage; no `static/` change expected).
- AC-4 transcript diff attached to the build-history entry.
- No real-environment run required — this build changes no command string, no
  transport, no device path. (Contrast the frontend half, which still owed a
  human browser open; here the 12 `main.main()` test files plus the transcript
  diff are the equivalent proof and they run in CI.)

## Risks

- **A moved block silently drops a `parser.error` or reorders mode precedence**
  — two maintenance modes both set, or `--only` + a maintenance flag, must
  still collide with the identical message. AC-2 + AC-4's `parser.error`
  matrix + the existing `test_*` mutually-exclusive checks are the guard.
- **A vendor import creeps to module scope** in `cli.py` / `services.py` and a
  `--repository-privacy-check` run starts loading `paramiko` — the exact
  regression AC-3/AC-5 exist to catch. This is the backend twin of the
  frontend half's composition-order `ReferenceError` risk.
- **`cfg.clear_credentials()` in the Phase-E `finally`** — if the extraction
  puts the `try/finally` in `checkpoint.py` but builds `cfg` in a caller, the
  cleanup can be skipped on an early error. The `try/except/finally` and the
  `cfg` lifetime move **together** into `checkpoint.py`'s entrypoint (D-MOD-B6);
  a test asserts credentials are cleared on a mid-pipeline exception.
- **`ApplicationContext` becomes a dumping ground** — keep it to the F3 list;
  a workflow needing something not there is a signal the seam is wrong, not a
  reason to add a field.
- **Scope creep into the vendor-collector split** — `checkpoint.py` will still
  be the largest `application/` module after this build (~560 lines of Phase E).
  That is acceptable and in-bounds; splitting `configuration/checkpoint_config_collector.py`
  is explicitly a separate, later, feature-triggered item. Do not start it here.
- **`build_parser` used by anything other than `main()`** — grep first; if a
  test builds the parser directly it must import from the new location or
  `main` must re-export `build_parser` too (add to F4's list if so).

## Rollback

Revert the `application/` package and restore `main.py` to its single-file
form. No stored data, schema, artifact, CLI surface, or command string is
touched, so rollback is a pure source-layout revert with no migration concern —
identical in kind to the frontend half's rollback.

## Definition of done

1. AC-1 … AC-8 all green.
2. Full suite at or above the pre-build baseline; privacy gate PASS/0; render
   harness green; the AC-4 transcript diff recorded in the build-history entry.
3. `main.py` ≤ ~120 lines; `application/` matches the ownership table; the F4
   names still import from `main`.
4. Status → `IMPLEMENTED`. The `codebase_modularization` backlog id can then be
   closed for both halves; a vendor-collector split, if ever needed, opens as
   its own feature-scoped entry.

## Next movement / model

This contract (scope + audit + design) is **frozen** (2026-09-01, `Sonnet 5,
normal` — the module boundaries follow directly from §5's named layout plus a
line-verified read of `main.py`; the only real design calls, D-MOD-B2
re-export-don't-rewrite and D-MOD-B6's `--cp-config-*` placement, follow from
stated facts about the test coupling and the shared CP-config import, not novel
architecture judgement). Implementation is scoped for a fresh session, also at
**`Sonnet 5, normal`** — mechanical block relocation against a concrete
ownership table, verified by AC-3's static import checks and AC-4's transcript
diff doing the work a human re-review of a large move otherwise would. The one
place to slow down is step 5 (Phase E: the `RunContext` lifecycle, the degraded
policy, and the `cfg.clear_credentials()` `finally` all move as one unit) —
still `normal` reasoning, just careful rather than fast.
