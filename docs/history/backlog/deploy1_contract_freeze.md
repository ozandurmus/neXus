# DEPLOY.1 contract freeze and server-arrival execution gate

status: done · target: DEPLOY.1

Contract frozen 2026-08-27 in DEPLOY_1_CONTRACT_FREEZE_HANDOVER_2026_08_27.md. No runtime behavior changes before server arrival (~1 week). Mandatory gates on arrival: OIDC viewer boundary, evidence egress policy, CP strict host-key R2 validation, PAN TLS corporate CA validation.

2026-09-13 realignment: closed as superseded. DEPLOY.1 was the Ubuntu+Docker server migration of the Python product; UI 2.0 runs on plain Kubernetes under B1_01C, with independently deployable services under 13D. The server-arrival gate itself is external and carried by the remaining deploy1_evidence_egress_policy and recovery_offhost_key_custody items. PO_DECISION_RECORD_2026_09_12 section 2 (2026-09-12): all UI 2.0 feature work is Java written from scratch; the existing Python is know-how only. Backlog realignment 2026-09-13, approved by the Product Owner in session.
