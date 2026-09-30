# Secure SDLC controls mapped to NIST SSDF

Implementation evidence for the ratified
`PO_DECISION_RECORD_2026_09_30_SECURE_SDLC_DAILY_SCANS.md`. This is a control mapping, not a certification or a new
contract. Repository checks and deployment validation are distinct; scanner pins and runtime validation remain
reviewer prerequisites (see `deploy/security/README.md`).

| SSDF practice | neXus control | Status |
| --- | --- | --- |
| PO.1 — Define security requirements | Constitution, ratified security decisions and per-vendor command contracts | Existing; product-wide security requirements and vulnerability SLA still open |
| PO.3 — Implement supporting toolchains | Isolated `ui2-security` jobs, proxy-only external egress, non-root/read-only scanners, offline Semgrep rule packs | Repository implementation; actual pins, rule-cache provenance and host enforcement pending |
| PO.4 — Define security-check criteria | Exact, owned, dated acceptance baseline; fail-closed summary; explicit logged emergency bypass | Implemented and covered by offline Python tests |
| PS.1 — Protect code from unauthorized access | Read-only committed source/history snapshot; no scanner API token or cluster roles; local-only reports | Repository implementation; host/PVC access validation pending |
| PS.2 — Verify release integrity | Existing Gradle locks/verification metadata and digest-addressed images; new gate bound to built commit/digest | Gate logic tested with fake Jobs; actual build/scan/rollout boundary pending host validation |
| PS.3 — Archive/protect releases | CycloneDX SBOM per image; normalized reports and counts retained 90 days | Repository implementation; retention/capacity exercise pending |
| PW.4 — Reuse well-secured components | Daily Trivy filesystem/dependency and deployed-image scans; ship image/dependency gate | Implemented; database freshness and Gradle/package-lock coverage require scanner smoke test |
| PW.7 — Review/analyze human-readable code | Semgrep Java, TypeScript, OWASP and secrets packs; gitleaks tree/history; Trivy IaC | Implemented; actual pinned-tool/rule behavior requires host validation |
| PW.8 — Test executable code | Weekly authenticated, read-only ZAP baseline on internal UI | Implemented; passive spider coverage and machine-session behavior unverified on-host |
| RV.1 — Identify/confirm vulnerabilities | Daily/weekly new, fixed, accepted and existing counts; independent `security_scan` notification route | Counts-only projection tested; migration and SMTP delivery pending reviewer validation |
| RV.2 — Assess/prioritize/remediate | New unaccepted HIGH/CRITICAL stop rollout; acceptance requires reason, owner and review date | Gate implemented; organization-wide remediation SLA remains open |
| RV.3 — Analyze root causes | Existing targeted regressions and engineering reviews | Formal vulnerability root-cause feedback process remains open |

Threat modelling (PW.1/PW.2), a comprehensive security-requirements catalogue and a vulnerability-response SLA are
not supplied by scanners. No claims are made about production readiness, complete DAST coverage or vulnerability
absence from these repository-only checks. Scanner findings contain no device evidence and authorize no device action.
