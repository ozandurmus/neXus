# remove_dormant_remote_cleanup — Remove dormant remote-cleanup helper (utils/cleanup.py)

## Summary

Deleted utils/cleanup.py, an unreferenced module that connected over SSH with the CP collection credential and issued unaudited 'rm -f' commands -- outside the network-device command gate (docs/AI_DEVELOPMENT_PROTOCOL.md) and the current read-only product posture, even though nothing imported it. New tests/test_remove_dormant_remote_cleanup.py (pytest.mark.security) asserts the module stays gone and that no tracked .py file reintroduces a cleanup_all() equivalent.

## Evidence

2 new tests, both green. Full suite 881 passed / 23 skipped / 2 failed (same two pre-existing, unrelated, order-dependent failures, re-confirmed passing in isolation) -- zero regressions. Repository privacy gate PASS/0. No device contacted at any point.

## Risks forward

None. Purely local hardening flagged in docs/design/SERVER_PRODUCTIZATION_AND_MODULARIZATION_ARCHITECTURE.md section 3 item 1 as safe before server arrival; no runtime behavior, CLI surface, or test contract changed for any live path.
