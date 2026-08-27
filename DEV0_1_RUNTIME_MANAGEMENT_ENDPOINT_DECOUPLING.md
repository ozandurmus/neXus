# DEV.0.1 — Runtime Management Endpoint Decoupling

## Objective
Remove environment-specific Check Point Management and Palo Alto Panorama endpoints from active repository source while preserving collector interfaces and runtime behavior.

## Changes
- Management endpoints are requested at runtime only when the selected execution mode requires them.
- Check Point-only modes request only the Check Point Management endpoint.
- Panorama-only modes request only the Panorama endpoint.
- Full collection requests both endpoints.
- Existing `cfg.mds_ip`, `cfg.panorama_ip`, `cfg.username`, and `cfg.password` collector contracts are preserved.
- The legacy top-level `config.py` no longer embeds environment endpoints.
- Password behavior is unchanged: runtime-only via `getpass`; no new persistence mechanism was introduced.

## Explicitly out of scope
- credential vault/CyberArk integration
- SSH host-key hardening
- PAN TLS trust hardening
- collector concurrency/polling changes
- device command changes
- fixture/documentation sanitization
- Git migration

## Validation
- Targeted DEV.0.1 + affected workflow tests: PASS.
- Full automated regression: 190 passed, 2 known xfailed.
- No network collection was executed during build validation.

## Real-environment acceptance
Run a normal or partial collection and verify the required management endpoint prompt(s) appear before Username/Password and that collection behavior remains unchanged.
