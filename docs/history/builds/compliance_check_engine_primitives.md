# compliance_check_engine_primitives — CE.2 -- curated read-only command-primitive registry for checks

## Summary

New configuration/command_primitives.py: a static, fail-closed registry of individually 10-point network-device-command-gate-reviewed READ-ONLY primitives (one Check Point, one Palo Alto proof entry), each reusing an already-controlled transport/command rather than a new credential path. New main.py --compliance-probe (application/workflows/compliance.py) runs them through the existing admission coordinator, once per device per run, writing a new local-only redacted evidence artifact; 'primitive' source namespace added to the compliance-check engine so a pack can reference primitive.<id>, never a raw command string.

## Evidence

Relay-authorized movement (.nexus/approved_task.json SESSION_START, content-hash-verified against relay/NXS-LOCAL-0037-ce2-compliance-check-engine-primitives.json). Full detail: project/backlog.json's compliance_check_engine_primitives note. 33 new tests (tests/test_ce2_compliance_check_engine_primitives.py) plus the existing CE.1 suite (tests/test_phase0_7_3_compliance_check_engine.py) green. Full one-shot regression, foreground, awaited to completion (.venv/bin/python3 -m pytest -q -n auto --dist worksteal): 3126 passed, 27 skipped, 2 failed -- the same pre-existing, unrelated DLP-token-collision pair (project/build_history.json's own prose + relay/NXS-LOCAL-0003) named as an accepted standing exception in this movement's own merge_gate. Repository privacy gate PASS, 0 new findings vs origin/main. git diff --check clean.

## Risks forward

Real-environment validation (a real device/Panorama run confirming cp_gaia_show_version_all / pan_show_system_info's actual output shape against the registry's redaction rule) is owed before promotion beyond opt-in --compliance-probe mode -- no live device reachable from this workspace; added to CURRENT_STATE.md's real-environment-validation-owed list. diagnostic_runbooks_read_only's own runbook-catalog implementation was deliberately not built here -- it shares this registry per its own backlog note; only vendor-generic extension points (PRIMITIVE_REGISTRY, primitives_for_vendor, an explicit primitives= parameter on both run_*_primitives functions) were left, no runbook logic.
