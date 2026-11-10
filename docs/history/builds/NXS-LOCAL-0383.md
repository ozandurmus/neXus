# NXS-LOCAL-0383 — Enterprise stack alignment, phase 0: Spring Security sign-in, JSON logs, monitoring, secrets, scans

## Summary

The PO ratified the enterprise alignment order on 2026-11-10 from the stack gap analysis: phase 0 needs no enterprise dependency (question pack, SAML via ADFS with LDAP and a break-glass account, structured JSON logs, monitoring endpoints, a file-based secrets provider, SAST in CI). Phase 1 (authorization map, Maven, Bitbucket and Jenkins, ship tooling port) waits until the PO has followed the access-request flows.

Plan (2026-11-10): phase 0 steps 0.1-0.6 of PO_DECISION_RECORD_2026_11_10_ENTERPRISE_STACK_ALIGNMENT.md section 3 run in one or two lanes beside product work. Backlog: spring_boot_4_spring_security_migration (0.2 and 1.1), enterprise_phase0_logging_monitoring_scans (0.3-0.6), enterprise_platform_conformance (phases 1-4, still parked until phase 1 starts). Evidence base: ENTERPRISE_STACK_COMPARISON_2026_11_10.md. Nothing is implemented yet.
