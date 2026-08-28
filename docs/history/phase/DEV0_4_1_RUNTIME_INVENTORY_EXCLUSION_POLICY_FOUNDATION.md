# DEV.0.4.1 — Runtime Inventory Exclusion Policy Foundation

## Status

COMPLETE — automated validation and repository privacy gate PASS. Real-environment CP behavior-preservation validation remains operator-run before the next CP collection.

## Purpose

Remove environment-specific Check Point non-device identities from repository source while preserving the existing exact-name exclusion point before direct device polling.

## Runtime policy

Local-only file:

`RuntimeRoot/data/state/inventory_exclusions.json`

Repository-safe example:

`examples/inventory_exclusions.example.json`

Schema version 1 entries contain `vendor`, `identity`, `enabled`, and `reason`. Real identities must exist only in the local RuntimeRoot policy and must never be committed.

## Behavior

- Policy is loaded and validated before CP Management SSH is opened.
- Missing/empty policy is allowed but emits a count-only warning and means zero manual exclusions.
- Malformed policy fails before network access rather than silently broadening collection.
- CP exclusions use the existing remote exact-name filter; no pattern inference is introduced.
- Runtime identities are shell-quoted and are never printed in diagnostics or telemetry; only exclusion counts are recorded.
- Repository source contains no default environment identities.

## Local setup before CP validation

1. Copy `examples/inventory_exclusions.example.json` to `RuntimeRoot/data/state/inventory_exclusions.json`.
2. Replace the synthetic example with the local management objects that are known not to represent real devices.
3. Keep `vendor` as `checkpoint`, `enabled` as `true`, and use an operator-meaningful reason.
4. Run `py.exe -B .\\main.py --repository-privacy-check`; the repository candidate must remain PASS.
5. Run the next normal/partial CP inventory and compare discovery/selection behavior with the previous validated baseline. Do not paste real exclusion identities into AI/chat output.

## Validation

- targeted exclusion/privacy tests: PASS
- full regression: PASS
- repository privacy gate: PASS / zero findings
- network access during automated validation: NONE
