# op0a_pan_ha_peer_pairing_identity_closure — OP.0a.P7 revision - PAN HA peer-pairing identity closure

## Summary

Fixed two real defects found auditing PAN HA peer-pairing after the CP-side VSX real-env retry: peer_ip was never populated by the PAN config collector (pairing was dead code against real telemetry), and panorama_runtime_runner.py/panorama_config_collector.py parsed the same managed-device hostname with divergent whitespace handling. Sourced peer_ip/peer_ipv6 from the running-config XML already fetched (no new device command); added a shared normalize_pan_hostname() seam used by both parsers; and required mutual (both-directions) configuration agreement before _derive_pan_units forms a pair, with a distinguishable pan_ha_peer_asymmetric reason for one-sided/contradictory configuration. peer_ip remains explicitly Grade A (configuration intent for OP.0a read-only pairing only) -- never sufficient for a future CLASS 2 authorization decision.

## Evidence

tests/test_pan_ha_peer_pairing_identity_closure.py (9 tests: shared hostname seam, peer_ip/peer_ipv6 extraction depth-independent and fail-closed, no new network/device call). tests/test_op0a_ha_readiness.py grew 6 tests (mutual-agreement pairing, asymmetric/contradictory fail-closed, self-reference fail-closed, pair identity stable across active/passive swap, identity never derived from management_ip/serial/vsys). Full suite 1074 passed / 26 skipped / 0 failed. Privacy gate PASS/0. Architecture convergence 13/13.

## Risks forward

Real-environment validation not yet performed -- the config-XML peer-ip XPath depth was not asserted (searched as a descendant match, deliberately), and the first real PAN HA pair run through this collector should confirm it resolves. IPv6 peer matching is captured (peer_ipv6) but not wired into pairing. The two PAN hostname parsers remain structurally independent (only their whitespace handling was unified); a shared PAN identity resolver mirroring CP's resolve_entity_id precedent is a follow-up, not done here.
