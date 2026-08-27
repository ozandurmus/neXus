---
description: "Next chat bootstrap for 0.6.1B.1.6 Compliance Control Pack v1"
---

SESSION START

- Product baseline: 0.6.1B.1.5 (Compliance Posture Foundation), automated validated.
- Engineering baseline: DEV.1 (Corporate Git + Copilot Development Foundation), local-only workflow still active.
- Immediate next build: 0.6.1B.1.6 (Compliance Control Pack v1).
- Movement type: ARCHITECTURE -> IMPLEMENTATION -> VALIDATION -> RELEASE_HANDOVER.

Scope for this chat:

1) Compliance control pack expansion
- Implement total 10 deterministic evidence-backed controls for CP/PAN.
- Preserve strict evidence boundary: no raw config, no secrets, no real identity leakage.
- Keep no-certification-claim posture.

2) Compliance information architecture
- Keep control scopes explicit:
  - Subject controls (device-specific)
  - Platform controls (vendor/platform behavior)
  - Global safeguards (fleet-wide governance/privacy)
- Show control traceability metadata where available:
  - framework areas (CIS / PCI DSS / BDDK)
  - status semantics
  - what evidence fields were checked (safe, normalized names only)

3) UX expectations
- Preserve master-detail behavior already established in Compliance:
  - Fleet and Subject views are mutually exclusive.
  - No empty actionable-only subject state for PASS-only devices.
- Keep design language aligned with existing Configuration module patterns.

4) Tests and acceptance
- Add/update targeted tests for new controls and scope partition.
- Run targeted regression first, expand only by blast radius.
- Keep known xfails untouched unless explicitly fixed.

5) Durable state updates required at build close
- CURRENT_STATE.md
- project/roadmap.json
- project/backlog.json
- project/feature_registry.json
- project/build_history.json

Important constraints:

- No new device commands in this build.
- No write/change automation.
- No runtime/output/log directory broad scans.
- CP and PAN contracts remain vendor-native and evidence-first.

If architecture ambiguity appears:
- Pause implementation,
- freeze a compact architecture contract,
- then proceed deterministically.
