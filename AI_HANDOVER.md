# NON-AUTHORITATIVE DERIVED SUMMARY — DO NOT USE AS PROJECT-STATE AUTHORITY

# Snapshot
UI2 is live on K3s HOST_A (ui2.nexus.local).
AIView privacy-preserving HMAC masking funnel (`role:replay_viewer`) is deployed, seeded, and live-verified.
Zero production DB mutation: `nexusadmin` sees cleartext prod data, `aiview` sees relationship-preserving pseudonyms and masked IPs.
Authority: `UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md` & `PO_DECISION_RECORD_2026_09_18A_UI2_K3S_REMEDIATION_AND_ORCHESTRATION_ALIGNMENT.md`.

# Recent session changes
- AIView Privacy Masking Funnel (`NXS-LOCAL-0333`): Implemented `role:replay_viewer`, `AiViewIdentitySeedingRunner`, `SubnetPreservingIpMasker`, `TopologyNamePseudonymizer`, and `PrivacyMaskingResponseBodyAdvice`.
- Intercepts `/devices`, `/devices/{id}`, `/devices/{id}/inventory`, `/clusters/{ref}/inventory`, and `/api/v2/jobs`.
- Bidirectional cluster name resolution in `InventoryQueryService` allows transparent drilldown using pseudonyms (`CLS-TITAN-06`).
- Idempotent masking prevents double-masking of already-pseudonymized entities.
- Live side-by-side verification on K3s: `aiview` receives masked IPs and topology names while `nexusadmin` receives cleartext data.
- Automated relay records `NXS-LOCAL-0333` through `NXS-LOCAL-0336` created, closed, and validated with 0 privacy findings.

# Exact next action
- Product Owner review and live UI inspection under `aiview` user.

# Test delta
- Unit test suites: `:platform-core:test`, `:persistence:test`, `:service:test`, `:job-engine:test`, `:worker:test` all PASS.
- Repository privacy check: 0 findings across 2,336 files (`python3 scripts/repository_privacy_check.py`).
- Live K3s curl validation: `aiview` authenticated (200), synthetic IPs/routes/clusters verified; `nexusadmin` authenticated (200), raw data intact.

# Risks
- None.
