# codebase_modularization_frontend-contract — Codebase modularization (static/app.js split) -- frontend-half contract

## Summary

Scoping pass for project/backlog.json codebase_modularization (P1), the frontend half of docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md section 5. Grounded in the same session's frontend_rendering_boundary full read of static/app.js (169 top-level functions, no strict mode/wrapper, three interleaved top-level executable-code regions, decentralized module-level state). Froze docs/history/phase/CODEBASE_MODULARIZATION_FRONTEND.md: an 8-file module ownership table amending the architecture doc's original 7-file proposal (adds overview_ui.js), two deliberate design deviations (no window.SecurityExpert namespace, no shared-state bucket in app_core.js) each with a stated reason, a file-concatenation composition mechanism (no bundler/ES-modules/build-step), a new static dependency-order regression test design (AC-3), and a step-by-step implementation plan for a fresh session.

## Evidence

No code changed; no test re-run required (last evidence, 888/23/0, unaffected). Repository privacy gate not re-run (docs only, no tracked-source secrets pattern touched).

## Risks forward

A wrong composition order fails silently (ReferenceError partway through the inline script, same class of risk frontend_rendering_boundary's CSP violations posed) -- AC-3's static ordering check exists specifically to catch this before a browser does. D-MOD5's line ranges will drift slightly during real extraction; the ownership rule matters more than preserving exact line numbers. The backend half of codebase_modularization (main.py / vendor-collector splitting) remains unscoped and must not be folded into the same implementation session as this frontend-only contract.
