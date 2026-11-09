# PO decision record - 2026-11-10: enterprise stack alignment, direction and action plan

**Status: RATIFIED - PO DECISION (chat, 2026-11-10), direction and order only.** Each phase below still needs its
own design, review and ship. The evidence base is
[ENTERPRISE_STACK_COMPARISON_2026_11_10.md](ENTERPRISE_STACK_COMPARISON_2026_11_10.md).

## 1. PO answers (verbatim where quoted)

| # | Question | Answer |
| --- | --- | --- |
| A1 | Purpose | "Plan, kurumsal standartlar ve ortama uyumun sağlanması ... ilk hedefim github yerine bitbucketa alıp sdlc süreçlerine dahil olup taramalarımızı yapmak" (align with enterprise standards and environment, item by item; first target: move from GitHub to Bitbucket, join the SDLC process, pass its scans) |
| A2 | Conformance stance | "Olabildiği kadarıyla tam uyum" (full conformance as far as possible); asked whether Spring Data JDBC, Spring Cloud Data Flow, Spring Security and Maven have hard limits |
| A3 | Who answers the open questions | Mixed: the PO answers what they know, the rest goes to the enterprise architecture team |
| A4 | First areas | Security, and monitoring and logging. Delivery chain after the PO has followed the access-request flows. OpenShift last |
| A5 | SAML identity provider | ADFS |
| A6 | Firewall credentials | Stay encrypted inside neXus. CyberArk holds platform secrets only |
| A7 | SDLC scans in the enterprise pipeline | SAST, SCA, image scan and secret scan (product names not yet known) |
| A8 | Instana access, ARK log format | Neither available yet |
| A9 | Tanzu licence | Unknown; ask the architecture team |
| A10 | Local break-glass administrator beside SAML | Allowed |
| A11 | Pace | In parallel with product work, one or two lanes |
| A12 | Order of the large rewrites | Spring Security, then Maven, then Spring Boot workers, then Spring Data JDBC |

## 2. Answer to A2: hard limits per technology

| Technology | Hard limit? | Finding |
| --- | --- | --- |
| Spring Cloud Data Flow | **Yes** | Spring ended open-source maintenance on 2025-04-21; 2.11.x is the last open line and later releases go to Tanzu customers only. Broadcom KB 424427 states that the open-source Kubernetes version is deprecated in the second half of 2026. It is also a stream and batch pipeline orchestrator (one pod per task, a Kafka or RabbitMQ binder), with no equivalent of device leases, quarantine or time windows. Without a Tanzu licence, adopting it means running unpatched software. |
| Spring Data JDBC | No | 65 production files use jOOQ. The job-claim lock (`SKIP LOCKED`, 2 files) and upserts (12 files) can be kept as hand-written `@Query`/`@Modifying` SQL. Lost: compile-time SQL checking, and the dynamic query builder for filtered lists. Prerequisite: the workers run on Spring Boot. Months of work; the 88 PostgreSQL integration test files are the safety net. |
| Spring Security | No | Needed anyway for SAML. Authentication moves first (SAML via ADFS, LDAP, and one local break-glass account). The closed route-to-action authorization map then moves into Spring Security authorization managers, keeping deny-by-default and the audit of every decision. |
| Maven | No | The 13 modules map to a Maven multi-module build, and the frontend builds through a Maven plugin. Lost: Gradle dependency checksum verification (`ui2/gradle/verification-metadata.xml`), which has no built-in Maven equivalent. Artifactory and the enterprise SCA scan compensate; this trade-off is recorded, not hidden. |

## 3. Action plan

The work runs in phases, in one or two lanes beside product work (A11). Each phase ends with its own PR or PRs through the normal ship gate.

### Phase 0 - no enterprise dependency, start now

| Step | Work | Size |
| --- | --- | --- |
| 0.1 | Send the architecture team the question pack (section 4) | S |
| 0.2 | Spring Security authentication in `ui2-service`: SAML 2 service provider for ADFS, the existing LDAP path, one local break-glass administrator (audited, alerting on use), ending in the existing session. Tested against a SAML identity provider that runs only in tests. | M |
| 0.3 | One structured JSON console log format for every role (service, workers, policy, packet analysis), with a correlation id per request and per job. The field set is kept configurable so that the ARK format drops in when it arrives. | S-M |
| 0.4 | Monitoring readiness: health and metrics endpoints, plus trace propagation that an Instana agent or OpenTelemetry can pick up. Whether Instana ingests these signals is UNKNOWN until A8 access exists. | S-M |
| 0.5 | Platform secrets read through one file-based provider, so that CyberArk delivery plugs in whichever variant the answer to Q13 names. Firewall credentials stay in neXus (A6); write the exception argument. | S |
| 0.6 | Scan readiness: we already run Trivy (image and dependency) and gitleaks (secrets). Add a SAST scanner to the existing CI, and clear its findings before the enterprise scans see the code. Remove the npm TLS bypass (`ui2/Containerfile:33`) and self-host the icon font (`ui2/frontend/index.html:6-10`). | S-M |

### Phase 1 - delivery chain, after the PO has followed the access flows (A4)

| Step | Work | Size |
| --- | --- | --- |
| 1.1 | Spring Security authorization: the route-to-action map moves into Spring Security, with the ArchUnit fences and the deny-by-default tests kept | M-L |
| 1.2 | Gradle to Maven conversion, with dependency resolution through Artifactory | M |
| 1.3 | Bitbucket repository, a Jenkinsfile that mirrors the current CI stages, and enterprise SAST, SCA, image and secret scans | M |
| 1.4 | Ship tooling port: `tools/delivery`, the ship queue and the PR checks currently assume GitHub and `gh`. Until this is done, a Bitbucket move stops the PR-based ship process (see the risk below). | M |

### Phase 2 - runtime alignment

| Step | Work | Size |
| --- | --- | --- |
| 2.1 | Workers (worker, policy, packet analysis) on Spring Boot, with no change in behaviour | M-L |
| 2.2 | Instana agent on all workloads, once access exists | S-M |
| 2.3 | Job engine. If a Tanzu licence exists, assess Spring Cloud Data Flow. Otherwise move the engine onto Spring Boot and Spring Batch primitives where they fit, and argue the device-safety core as an exception. | M-L |

### Phase 3 - data access

| Step | Work | Size |
| --- | --- | --- |
| 3.1 | jOOQ to Spring Data JDBC, one module at a time, with the claim and compare-and-set SQL kept verbatim. Re-confirm with the PO before starting, using the cost measured in phases 1 and 2. | L |

### Phase 4 - last (A4)

| Step | Work | Size |
| --- | --- | --- |
| 4.1 | OpenShift admission: fix the five manifest findings and prove admission on a real project | M |
| 4.2 | ArgoCD deployment, with sync waves for rollout order and the "no rollout during a device job" rule | M |
| 4.3 | Network path from OpenShift to the firewall management networks. Outside the repository; this is the hard blocker. | ? |

### Risks the PO assistant flags

- **Bitbucket before the ship tooling port (1.4).** This breaks the PR-based ship gate, CI and the cloud routines. Mirror the repository first and switch the source of truth last.
- **Spring Data JDBC (3.1) is the costliest and riskiest step,** with no user-visible gain. "As far as possible" (A2) leaves room for an exception if the measured cost is high.
- **Firewall credentials in neXus (A6) is an exception to "Secret Management: CyberArk".** The enterprise security team may still require it; question Q14 stays open.

## 4. Question pack for the architecture team

These are the questions from section 13.3 of the comparison, minus those the PO has answered (IdP = ADFS; break-glass allowed; firewall credentials stay in neXus, subject to the security team accepting it; scan types known).

| Area | Questions |
| --- | --- |
| ARK conventions | Q1 branch, project, repository, label and namespace rules; Q2 "ARK Logging Console Log" field set |
| Baselines | Q3 approved Java and Spring Boot versions (neXus: Java 21, Spring Boot 4.0.8); Q4 mandatory versus default rows; Q5 must non-HTTP workers be Spring Boot; new: is there a Tanzu licence (Spring Cloud Data Flow is commercial-only since 2025) |
| Build and deploy | Q6 Containerfile build inside Jenkins versus S2I scripts; Q7 PostgreSQL for integration tests on Jenkins agents; Q8 ArgoCD and database migrations; Q9 project creation, PriorityClass, pod metrics |
| Data | Q10 managed PostgreSQL and a migration role; Q11 storage classes for about 500 Gi; Q12 DuckDB inside a pod volume |
| Security | Q13 which CyberArk integration; Q14 confirm firewall credentials may stay encrypted in neXus; Q15 privileged-session gateway for automated SSH and API; Q17 CNAPP and XDR products and allow-listing; Q18 the ADFS claim that carries groups, and who maps groups to roles; new: the SAST, SCA, image and secret scanner products and their gate thresholds |
| Platform | Q19 the role of Nginx; Q20 what iPaaS offers; Q21 network path and source addresses to firewall management networks; Q22 Instana runtime support, test access, and job-level tracing |

## Sources
- https://spring.io/blog/2025/04/21/spring-cloud-data-flow-commercial
- https://spring.io/projects/spring-cloud-dataflow
- https://knowledge.broadcom.com/external/article/424427/unable-to-download-spring-cloud-dataflow.html
