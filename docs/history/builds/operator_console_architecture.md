# operator_console_architecture — CON.x Operator Console -- architecture + all five phase contracts

## Summary

Froze docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md (CON.0) and the five phase contracts CON.1-CON.5 for a second, authenticated, action-capable delivery surface over the existing engine, driven by the BackBox exit: the exported static report stays portable and action-free, one UI source tree feeds both surfaces, and the browser sends intent against a closed server-side job registry rather than device commands. SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md gains a section 7 reconciliation recording how the console satisfies each of its boundaries without relaxing any prohibition.

## Evidence

Docs and project metadata only; no source file touched, so no test re-run was required (last evidence, 888 passed / 23 skipped / 0 failed, unaffected). Repository privacy gate re-run on the working tree: PASS / 0.

## Risks forward

Scope drift is the main risk: the console is a surface over an existing engine, and any phase that proposes a new collector, a console-only payload shape or a second orchestration path has left the architecture -- each contract restates this as an explicit out-of-scope line, and CON.2 AC-2 makes the single-orchestration-path rule structurally checkable. CON.1 must not start before codebase_modularization (frontend) is DONE; CON.3 must not start before RB.3b is REAL_ENV_VALIDATED. Eight open decisions (C-D1..C-D8) are product-owner or security calls and block CON.1/CON.2/CON.3/CON.5 respectively. A polished Recovery screen can make the still-open D1 vendor-coverage gap easier to overlook, which is why CON.4 C4-2 requires uncovered devices to be counted, never omitted.
