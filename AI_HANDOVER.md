# AI_HANDOVER

> **NON-AUTHORITATIVE DERIVED SUMMARY**
> **DO NOT USE AS PROJECT-STATE AUTHORITY**
> If this disagrees with `CURRENT_STATE.md` or `project/QUEUE.md`, those win.

## Operating role

`roles/PO.md` (PO+O) or `roles/ENGINEER.md`; routing and workspace
environment facts in `docs/reference/COPILOT_OPERATING_MODEL.md`.

## 1. Snapshot

- **The product runs and a human has signed in** (local Kubernetes,
  `docs/operations/UI2_LOCAL_KUBERNETES_DEPLOYMENT.md`). Login, sign-out,
  local identity administration and the credential store are merged; forced
  password change is a posture switch, default off.
- **Discovery is in Java for both vendors**; bindings `UNVERIFIED`, gate
  documents DRAFT, **nothing has run live**.
- `DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md` **FROZEN**; the four
  first-contact reads approved. The product is **neXus**.

## 2. What this session did

- CP transport aligned to the measured method; PAN transport built.
- Login step delivered; records `13G` and `2026-09-14` written.
- Brand: option-D wordmark, favicon, title; governing docs renamed.
- Deployed to minikube past VPN and TLS-interception; Containerfile builds
  the frontend in a Node stage and accepts CA anchors (`ui2/.ca/`).

## 3. Exact next action

**`NXS-LOCAL-0156` (single-device add: address, vendor, credential → first
contact → `ENROLLED`, peer follow under corroboration) — review and merge.**
Then the management-server branch (`DA-3`) once live discovery runs confirm
the bindings; then the collection measurement briefs (`13E` step 3). Keep the
VM; rebuild only the image.

## 4. Test delta

`ui2` build and all suites green; migrations through `V11` (`V12` reserved
for `0156`); CI `validate` green; contract-authority gate green on `main`.

## 5. New risks

- VPN claims all private space: VM reachable only with VPN off or a host
  route; the `vmnet` subnet change applies after a reboot.
- Role-binding and credential-store keys are wired by hand, not by
  `deploy/ui2/` manifests.
- Budget ceilings hit three times with work committed: verify from the PO
  side and open the PR instead of re-running.
