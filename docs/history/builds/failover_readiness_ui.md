# failover_readiness_ui — OP.0c - Failover readiness UI module (Operator Console + report)

## Summary

Added a read-only Failover module to both the Operator Console and the exported report: a live projection over utils.failover.compute_ha_readiness (fleet view, per-unit verdict, per-stop-condition blocking reasons, the OP.0a fail-closed framing note). No execution control, no new device command, no CLASS 2 job type.

## Evidence

tests/test_op0c_failover_readiness_ui.py (15 tests: fail-closed verdict/check preservation, framing note, no execution markup/network call, no CLASS 2 registration). Full suite 1003 passed / 27 skipped / 0 failed serially. Render harness (happy-dom nav click-through) green. Privacy gate PASS. metadata_warnings == [].

## Risks forward

Every unit correctly shows INSUFFICIENT_EVIDENCE or NOT_A_FAILOVER_UNIT until OP.0b lands -- an empty-looking dashboard is the honest state of the evidence, not a bug. No History sub-view yet (nothing to show history of before OP.1/OP.2 exist).
