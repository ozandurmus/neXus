# Device onboarding flow — contract (PO 2026-09-25)

**Status:** FROZEN — the Product Owner approved the flow on 2026-09-25 ("Onboardingle devam").

**Decision (Product Owner, 2026-09-25):** "Onboarding a device while seeing half the data is not acceptable."
Adding a device — by hand or by importing from a management server's discovery — is one flow that runs its steps
back to back on the server; the device is not presented as finished until the flow is done or has stopped with a
stated reason.

## Today (measured in the code, 2026-09-25)
- `DeviceAddSingleService.runInTransaction` registers a DRAFT row and admits the identity confirm; both the manual
  add and the discovery import go through it (`registration_source` manual_registration / discovery_import).
- The browser dialog (`AddDeviceDialog`) chains one inventory Collect after a successful confirm while it is open.
  Closing the dialog breaks the chain; a discovery import is never chained; no configuration read is ever chained.

## The flow
One row per device in `device_onboarding` (V77): `device_id`, `source`, `state` (RUNNING | COMPLETED | STOPPED),
`step` (identity | inventory | configuration), `step_job_id`, `reason`, `started_at`, `updated_at`,
`completed_at`.

| Step | Job | Next when COMPLETED | When the job fails |
|---|---|---|---|
| 1 identity | the confirm admitted at registration | inventory | STOPPED, reason = the confirm's terminal reason |
| 2 inventory | `InventoryCollectService.requestCollect` | configuration | STOPPED, reason = the collect's terminal reason |
| 3 configuration | `ConfigurationCollectService.requestCollect` | COMPLETED | STOPPED, reason = the read's terminal reason |

- A step the device's vendor/role has no read for (an admission refusal such as VENDOR_UNSUPPORTED) is **skipped
  with its reason recorded**, never failed: Infoblox, Radware and Blue Coat have no configuration read yet; a
  Panorama has no inventory read yet.
- The steps stay separate jobs: each keeps its gates, time bounds, audit and eligibility (a read never runs before
  the identity is confirmed; DRAFT until the confirm succeeds).
- **Backup is never a step** — it creates a file on the device. The finished flow offers "make this a backup
  target" (off by default).
- A service scheduler (`OnboardingFlowScheduler`, every 5 s) advances RUNNING rows whose current job is terminal.
  The flow does not depend on a browser being open.
- **Retry:** `POST /devices/{id}/onboarding/retry` re-admits the stopped step only (identity through the existing
  confirm retry; inventory/configuration with a fresh nonce).
- Devices enrolled before V77 have no row and are treated as onboarded.

## What the screens show
- **Add device dialog:** after submit it shows the flow — `1/3 identity ✓ · 2/3 inventory … · 3/3 configuration`
  — and the result panel only when the flow is COMPLETED (facts filled) or STOPPED (the step, its reason, Retry).
- **Devices list:** a device whose flow is RUNNING carries an "Onboarding · n/3" chip; STOPPED carries
  "Onboarding stopped" with the reason on hover. No device shows a card of "Unknown" values while onboarding.
- **Device detail:** while RUNNING, a progress panel replaces the empty tabs.

## Amendment (PO 2026-09-25, after the UI council)
A read the vendor or role does not have is **not shown at all** — no "not available" row or label. The dialog numbers
only the applicable steps (an Infoblox shows 1/2 Identity, 2/2 Inventory); the skip and its code stay recorded in
`device_onboarding.skipped` for audit. Each vendor's facts are shown in its own terms (Check Point version and
hotfix take; Palo Alto its own; vendors without such a fact show nothing for it).

## Acceptance
Minutes after adding a device (or importing a batch): every device is COMPLETED with name, model, version and its
inventory filled — or STOPPED with the failing step and its reason. No "Unknown" without a reason.
