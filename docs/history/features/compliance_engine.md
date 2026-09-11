# compliance_engine — Compliance Rule Engine

## summary

Vendor-neutral rule evaluation over verified normalized configuration facts. Designs: docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md (0.7.1 file-based catalog + assignment, delivered 0.7.1a/b) and docs/design/COMPLIANCE_CHECK_ENGINE.md (user-authored data-driven checks over already-collected evidence - CE.1 target 0.7.3, no server / no new command; CE.2 curated read-only command primitives, command-gated; CE.3 UI editor + signed org packs, DEPLOY.1A).

## why

Transforms verified current/expected state into actionable compliance findings, and lets operators/auditors add their own checks as data instead of code.
