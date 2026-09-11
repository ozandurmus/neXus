# compliance_engine — Compliance Rule Engine

## summary

Vendor-neutral rule evaluation over verified normalized configuration facts. Designs: docs/design/COMPLIANCE_ASSIGNMENT_AND_FRAMEWORKS.md (0.7.1 file-based catalog + assignment, delivered 0.7.1a/b) and docs/design/COMPLIANCE_CHECK_ENGINE.md (user-authored data-driven checks over already-collected evidence - CE.1 target 0.7.3, no server / no new command; CE.2 curated read-only command primitives, command-gated; CE.3 UI editor + signed org packs, DEPLOY.1A).

## why

Transforms verified current/expected state into actionable compliance findings, and lets operators/auditors add their own checks as data instead of code.

## criterion note (rules)

In progress: 0.6.6B static rule pack + 0.7.1a/b catalog + 0.7.3 (CE.1) data-driven user check engine (compliance_check_pack.py / compliance_check_engine.py) all delivered. Marked done once CE.2 (curated read-only command primitives; cp_device_interaction_safety itself closed 2026-08-25, remaining gate is the per-primitive network-device command gate + real-environment validation) and CE.3 (UI editor + signed org packs, DEPLOY.1A) land.
