# cp_identity_edges — CP identity-gate edge case review -- reviewed, no defect found

## Summary

Review-and-document build per docs/history/phase/PHASE0_6_1C_CP_IDENTITY_EDGES_REVIEW.md. Three findings, all reviewed-and-clean, no code change: (1) a dot in a short-name segment is FQDN-parsed consistently on both sides in _normalize_host_token, so it safely under-matches to different_observed rather than false-matching; the three matched relations (exact/shortname_match/normalized_match) are treated identically (HIGH) by _identity_gate, the finer split is audit granularity only. (2) _collector_identity_gate's MEDIUM-confidence fallback is anchored on target.management_ip (the already-connected endpoint), never on hostname/naming pattern -- proven by tests showing acceptance survives a completely unrelated hostname but fails without a selected endpoint. (3) the pre-poll exact-name exclusion filter and the post-connect identity gate are structurally decoupled: excluded_device_names/load_inventory_exclusions are consumed only by checkpoint/cp_runner.py, never by checkpoint_config_collector.py/checkpoint_config_probe.py, so an excluded device structurally never reaches the identity gate.

## Evidence

- **automated**: 21 new regression tests: a 13-case parametrized synthetic _identity_relation matrix plus IP-anchor and exclusion-decoupling proofs. py -m pytest -q: 590 passed, 2 skipped, 2 failed (both pre-existing and unrelated, same two tests already documented against the unmodified baseline in prior 0.6.x closures). Net +21 from baseline 569, zero regressions.
- **privacy_gate**: All hostname/name pairs fabricated; no real device identity from the user's estate.
- **real_env**: not applicable to this review -- real-estate confirmation of the boundary remains owed under on_hardware_real_env_validation, unchanged from before this review.
