# inventory_exclusions_management_ui_backend — Inventory exclusions write-path backend logic (no UI, no auth wiring yet -- deliberate partial scope)

## Summary

inventory_exclusions_management_ui's own note says 'not buildable standalone pre-DEPLOY.1A' -- writing to the policy that gates what gets monitored needs an authenticated, authorized, audited actor, which does not exist yet. Asked the user directly rather than building an unauthenticated write UI or skipping the item; they chose the safe slice: backend logic only, wired into nothing. utils/inventory_exclusions.py gained add_exclusion()/restore_exclusion() (mandatory non-empty reason, idempotent add, soft-disable restore preserving history) and an append-only audit ledger with a fail-CLOSED write order (audit append before policy write, deliberately diverging from compliance_history.py's fail-open trend-ledger pattern since this is a security record). A dedicated test asserts neither function is referenced in main.py/html_export.py/inventory_exclusions_ui.py -- this build adds zero new attack surface.

## Evidence

- **automated**: 20 new tests in tests/test_dev0_4_1_inventory_exclusions.py covering add/restore/idempotency/soft-disable/audit-recording/audit-cap/fail-closed-ordering/not-wired-anywhere. py -m pytest -q: 635 passed, 2 skipped, 2 failed (both pre-existing and unrelated, same two tests already documented against the unmodified baseline in every prior 0.6.x closure this session). Zero regressions.
- **privacy_gate**: No credential/device-identity/IP literal; the one actor-field example in tests uses a synthetic .test-TLD email.
- **real_env**: not applicable -- no device or network path; this is local policy-file logic only.
