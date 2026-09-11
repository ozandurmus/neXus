# op0b_s5_cp_preflight_collector — OP.0b S5 - Check Point dedicated preflight collector

## Summary

New checkpoint/preflight_collector.py implements the first dedicated Check Point failover-preflight collector, strictly within the PO-frozen OP.0b.1 command gate (docs/history/phase/OP_0B_1_COMMAND_GATE_PACKAGE.md, Approval record, PR #41): for one caller-selected, bounded (<=2 member) HA operational entity, one SSH session per physical member (reused for every read, B1 included, never re-opened), one preflight_run_id, and exactly the authorized battery (A1-A3 existing + A4-A8 new + B1 VSX-only), with evidence-based (never failure-driven) A6/A8 form dispatch and no application-level command retry anywhere. New checkpoint/cp_preflight_battery.py (fixed typed command plan, dispatch resolvers, a deterministic guard proving A9/A10/A11 and every rejected mutating command are absent by construction) and checkpoint/cp_preflight_extraction.py (one pure fail-closed parser per new command); checkpoint/cp_preflight_projection.py gains one projection function per new command on the existing S1/S3 seam (A1-A3's project_cp_preflight_facts left unchanged). No readiness verdict, no raw output persisted, no new SSH transport/credential path. tests/test_op0b_s5_cp_preflight_collector.py (48 tests) plus S1/S3/S4/architecture-convergence regression, the full local suite (1277 passed / 26 skipped / 0 failed, serial), and the repository privacy gate (0 findings) all green locally. No PR/CI evidence recorded here yet.

## Evidence

- tests/test_op0b_s5_cp_preflight_collector.py -- 48 passed
- tests/test_op0b_s1_preflight_model.py, test_op0b_s3_cp_extraction.py, test_op0b_s3_cp_projection.py, test_op0b_s4_command_gate.py -- 104 passed (unaffected)
- tests/test_architecture_convergence.py -- passed, metadata_warnings == []
- full local suite: 1277 passed / 26 skipped / 0 failed (serial, py -m pytest -q)
- py .\main.py --repository-privacy-check -- PASS, 0 findings

## Risks forward

- Real-env validation owed for A4-A8/B1 (S8) -- exact vendor field vocabulary for A6 syncstat/pstat and A8 failover-history forms remains UNKNOWN pending a real device read.
- D-F3 (flap/failover threshold) stays unresolved -- check 7 stays fail-closed regardless of A8's collected count.
- A9 (configured recovery/preemption) remains DEFERRED_UNKNOWN -- CP-3, P0 before CLASS 2.
- S6 (Palo Alto dedicated preflight collector) is the parallel sibling slice, same approval, not yet implemented.
