# Phase 0.5 Final - Palo Alto Management IP Closure

This closes the remaining Palo Alto inventory gap without changing the existing interface/route collection method.

## Source of truth

Panorama `show devices all` already returns the managed firewall `<ip-address>` field. F-Buddy previously parsed the serial, hostname and connection state but discarded this field.

The final collector now preserves it as `management_ip` in `panorama_runtime.json` and telemetry. No additional per-firewall API request is required.

## UI

- Standalone Palo Alto firewall: management IP is shown in the detail header.
- Palo Alto HA parent: each member and its management IP are shown as separate Management chips.
- Palo Alto VSYS child: inherits the same physical-member management information from its HA parent.
- Management IP is intentionally not inserted into the dataplane interface table because the PAN management interface is management-plane state, not a VSYS dataplane interface.

## Regression

`62 passed, 2 xfailed`

The two existing xfails remain the deferred VSX network canonicalization and Panorama default-route classification semantics.
