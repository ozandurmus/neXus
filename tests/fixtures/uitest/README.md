# `uitest` render-harness fixture bundle

Hand-authored, privacy-clean **topology matrix** that makes **every** UI module
render *populated*, so the render harness can check the generated report loads
and navigates without a human.

Covered: CP standalone gateway, ClusterXL (2 members, active/standby), VSX host
(standalone) + virtual systems, VSX cluster (2 hosts) + a shared virtual system,
one UNAVAILABLE gateway; PAN single firewall, HA pair (active/passive),
multi-vsys firewall, multi-vsys HA pair. Plus: interface/route divergence between
cluster members, stale + disconnected inventory, the full alignment
classification set, SAME / CHANGED / FIRST / insufficient history, crypto
PASS / FINDING / UNKNOWN across every category, enforced + advisory + WAIVED
compliance, per-framework COVERED / PARTIALLY_COVERED / UNCOVERED, and every
restore-readiness state (READY / STALE / PARTIAL / UNPROTECTED / UNKNOWN).

## Files

| File | Feeds | Consumed how |
| --- | --- | --- |
| `unified.json` | Network Inventory + Overview | read from disk by `run_html_export` (real path) |
| `configuration_ui.json` | Configuration module + `build_compliance_posture` input | injected for `build_configuration_ui_payload` |
| `crypto_ui.json` | Compliance crypto card + `crypto_facts_by_subject` | injected for `build_crypto_posture` |
| `discovery_ui.json` | Discovery module | injected for `build_discovery_capability_payload` |
| `state/compliance_checks.json` | user-defined compliance cards | copied to `<data_root>/state/`, read by `build_compliance_posture` |
| `state/control_assignments.json` | a WAIVED control | copied to `<data_root>/state/`, read by `load_control_assignments` |
| `state/compliance_history.json` | 0.7.5 trend sparkline + delta chip | copied to `<data_root>/state/`, read by `load_history` |
| `state/inventory_exclusions.json` | Exclusions module | copied to `<data_root>/state/`, read for real by `load_inventory_exclusions` (not injected -- runs the real payload builder, like `build_compliance_posture`) |
| `state/restore_readiness.json` | Recovery module + Overview recovery-posture tile + `compliance_posture`'s additive readiness evidence | copied to `<data_root>/state/`, read for real by `utils.recovery_ui.load_persisted_readiness_record` / `build_recovery_ui_payload` (not injected -- same real-builder pattern as `inventory_exclusions.json`) |

`build_compliance_posture`, `build_project_plan_payload` (from the real
`project/*.json`), the template fill and `_script_json` all run for real —
only the three builders whose real inputs are collector telemetry / PAN XML on
disk / live stores are injected.

M9's Device Management module (`device-management`) and the Inventory pane's
"Add device" dialog are console-only and have no static-report payload field —
their data comes live from `GET /api/registry/devices`/the job engine, not
from any file in this bundle, so there is nothing to add here for them.
Coverage for that path lives in
`tests/test_m9_enrollment_preview_and_confirmation.py`'s Playwright-based
`test_add_device_dialog_live_click_through_zero_console_errors` and
`tests/test_con1_operator_console_read_only.py`'s module-nav walk (both use
this same fixture bundle to boot the console).

## Regenerate

```
py -V:3.12 tests/fixtures/uitest/build_fixture.py
```

## Growth rule

**When a build adds or changes a `configuration_ui` / `compliance_overview` /
`crypto` / `discovery` payload field, or a UI module / tab, extend the matching
fixture here in the same change and re-run `build_fixture.py`.** Otherwise the
render harness keeps green while never exercising the new path — which is exactly
how the `0.7.4a` dead-button bug shipped.

## Privacy

Obviously-fake device names; RFC 5737 documentation IP ranges
(`192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`); no secrets, no real
identities. The repository privacy gate is lenient under `tests/` and these
values pass everywhere.
