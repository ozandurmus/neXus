# cp_ha_runtime — ClusterXL/VSX runtime HA role: per-virtual-system probe + explicit direct-Clish capability gap

## Summary

Closes the VSX-closure half of the standing P0 cp_ha_runtime item. A virtual_system row previously always inherited its physical member's cphaprob-derived ha_role verbatim, mislabeled as VS-specific runtime evidence. Each virtual_system context now runs its own vsenv-scoped cphaprob probe; a genuine independent read is labeled interactive_cphaprob_stat_runtime_per_vs/success, an unresolved probe falls back to the physical role but is explicitly relabeled inherited_from_physical_member/unavailable_inherited. Separately, interactive_direct_clish hosts (where cphaprob is an Expert/bash-only command unreachable from that shell) now resolve to an explicit capability_gap/cphaprob_unavailable_in_direct_clish instead of an undifferentiated unavailable. No new device command; cphaprob stat was already whitelisted for the physical member.

## Evidence

- **automated**: py -m pytest -q: 555 passed, 2 skipped, 2 failed (both pre-existing, unrelated test-order pollution reproduced identically on the unmodified baseline). Net +3 from 3 new regression tests in tests/test_phase0_6_1b_cp_configuration_collector_ui.py. Render harness (tests/test_html_render_harness.py): 3 passed, 1 skipped (no bun in this environment).
- **privacy_gate**: No new IP/credential/device-identity literal introduced; synthetic fixture data only (GW1/GW2/VS-A).
- **real_env**: not yet confirmed against a real VSX cluster -- owed under on_hardware_real_env_validation, not required to close this build per its own validation gate.
