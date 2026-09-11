# codebase_modularization_backend-contract — Codebase modularization (main.py orchestration split) -- backend-half contract

## Summary

SCOPE -> AUDIT -> CONTRACT pass for the backend half of docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md section 5, immediately after the frontend half landed. Line-verified read of main.py (2,089 lines; main() alone ~1,690 lines / 399-2089, five execution phases, 17 CLI modes, ~15 shared locals, one try/except/finally with cfg.clear_credentials()). Froze docs/history/phase/CODEBASE_MODULARIZATION_BACKEND.md: a new application/ package (cli.py, services.py, context.py, workflows/{maintenance,recovery,checkpoint}.py) binding to section 5's named layout, an explicit ApplicationContext dataclass replacing the shared locals, main.py kept as a thin entry that re-exports the seven module-level names 12 test files import (no test rewrite), the lazy vendor-import boundary turned into a tested invariant (AC-3 static + AC-5 runtime sys.modules check), and a before/after CLI transcript-diff parity gate (AC-4). Vendor-collector split (configuration/pan, configuration/checkpoint) explicitly OUT -- section 5 says those move only when a bounded feature touches them, and this build touches none.

## Evidence

No code changed; no test re-run required. Repository privacy gate not re-run (docs only).

## Risks forward

A moved mode block that drops a parser.error or reorders mode precedence (AC-2/AC-4 guard); a vendor import creeping to module scope in cli.py/services.py so an offline maintenance run starts loading paramiko (AC-3/AC-5 guard, the backend twin of the frontend half's ReferenceError risk); the Phase-E try/except/finally and cfg lifetime must move as one unit into checkpoint.py or credential cleanup can be skipped on a mid-pipeline error. checkpoint.py stays the largest application/ module (~560 lines of Phase E) after the split -- acceptable and in-bounds; splitting the 2,595-line panorama_config_collector.py / 1,941-line checkpoint_config_collector.py is a separate feature-triggered item, not this build.
