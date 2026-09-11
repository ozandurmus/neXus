# op_vsx_real_env_retry_fixes — VSX real-env retry - three post-merge defects found and fixed

## Summary

Real-environment retry of the VSX operational-identity correction (PR #30) against a live physical VSX pair found three defects invisible to synthetic unit tests: (1) checkpoint/scripts/cp_inventory.sh's echo|tr SAFE_GW pipeline silently appended a trailing underscore to every collected CP device name (echo's own newline being converted by tr, not a real naming convention), which propagated into (2) VSX-vs-ClusterXL misclassification and VS Virtual Systems splitting into per-member duplicates instead of merging under one cluster parent, and (3) HA-runtime checks always reporting INSUFFICIENT_EVIDENCE because two independently-computed entity-id strings for the same device diverged by that artifact. Fixed at the source (SAFE_GW strip) plus defensive join-key normalization in utils/failover/assessment.py; also threaded the VSX vsys context name into each Virtual System unit's display_name and widened the zero-padded member-ordinal display-name regex.

## Evidence

tests/test_op0a_ha_readiness.py grew 2 tests reproducing the exact real-env device-name-separator and cp_ha_runtime entity-id shapes reported against the real pair. tests/test_phase0_5_3_cluster_hierarchy_ui.py grew 1 test for zero-padded member ordinals. tests/test_phase0_4_2_parallel_cp.py grew 1 test reproducing the echo/tr SAFE_GW pipeline bug directly. Full suite 1074 passed / 26 skipped / 0 failed. Confirmed against the real <DEVICE_NAME> and <DEVICE_NAME> pairs by the product owner after each fix.

## Risks forward

Fix applies only to collections run after this change -- output/cp.json etc. captured before it still carry the trailing-underscore artifact until re-collected. The defensive join-key normalization in assessment.py stays in place as a safety net for that stale data and any other, not-yet-seen instance of the same separator quirk.
