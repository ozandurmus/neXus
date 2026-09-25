# Add device = one flow of separate jobs (PO 2026-09-25: half-filled devices are not acceptable): identity (confirm) -> inventory (Collect) -> configuration where the vendor has one; one progress view, stop at the failing step with its reason, retry that step only; discovery imports use the same flow; backup never in the flow (a write; offered as an opt-in at the end). Acceptance: minutes after adding, no Unknown field without a stated reason. Design note first, PO approval, then code (~1 day)

status: automated_validated · target: ui2/frontend/src/shell/AddDeviceDialog.tsx

V77 device_onboarding + OnboardingFlowService (5 s scheduler) + retry route + Add device stepper + list chip; contract docs/design/DEVICE_ONBOARDING_FLOW_CONTRACT.md; V77 dry-run clean on the live DB.
