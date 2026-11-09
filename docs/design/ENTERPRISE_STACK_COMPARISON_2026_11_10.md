# neXus versus the enterprise reference stack — gap analysis

Date: 2026-11-10. Status: ANALYSIS, not a decision and not a contract. Read from `origin/main` at commit `eea3bafb`.

## Executive summary

1. neXus already speaks the enterprise languages: the screen is TypeScript and React, built with Vite and tested with Vitest; the server side is Java 21, tested with JUnit and ArchUnit, on PostgreSQL. No rewrite of the product language is needed.
2. The largest differences are in the delivery chain, not the product: neXus lives on GitHub, builds with Gradle and an in-cluster builder, and is deployed by scripts to one k3s host. The reference stack wants Bitbucket, Maven, a Jenkins S2I pipeline, ArgoCD and Artifactory. None of these exist in the repository today.
3. Sign-in is the largest security gap. neXus has local accounts and LDAP/Active Directory; it has no SAML single sign-on, and it does not use Spring Security. Its own access-control chain is strict and tested, but it is not the mandated framework.
4. Secrets are not in CyberArk. Platform secrets are Kubernetes Secrets read as files; firewall credentials are kept encrypted in neXus's own database. Where firewall credentials must live is a policy question for the enterprise, and it is the one with the most design impact.
5. There is no monitoring agent, no tracing and no metrics endpoint. Instana is absent on the screen, the service and the workers. Logs go to the console in plain text, in three different formats.
6. Only the web service uses Spring Boot. The workers, the job engine and all database code are plain Java with jOOQ. Spring Data JDBC, MapStruct, Spring Cloud Data Flow and a message broker are not used; the job queue is a PostgreSQL table.
7. OpenShift was planned for from the start: the image is a Red Hat UBI 9 image, pods run without root, with a read-only filesystem, and an OpenShift Route file is committed. It has never been run on OpenShift, and this review found five places where the committed manifests would not pass as they are.
8. Several neXus parts have no slot in the reference stack: SSH and API access to firewalls, packet-capture analysis with a native tool and a second database engine, large encrypted backup volumes, and inbound vendor file pushes. Each needs an explicit architecture ruling.
9. Rough size: delivery-chain and platform onboarding is weeks of work; SAML, CyberArk and monitoring are weeks each; replacing jOOQ, the job engine or the access-control chain with the mandated frameworks would be months each and carries the highest regression risk. Those three are the items to argue as exceptions.
10. Six rows cannot be judged from the repository because they depend on the ARK convention set or on enterprise products not described here. They, and 22 open questions for the enterprise architecture team, are listed at the end.

## 1. Method and limits

- Evidence is the repository only: build files, container files, Kubernetes manifests, the CI workflow and Java/TypeScript source. No host, cluster, database or device was contacted.
- Every claim carries a `path:line` reference. Counts were taken with `find` and `grep` over `src/main` and `src/test`.
- Status vocabulary:
  - `MATCH` — neXus uses the named technology.
  - `PARTIAL` — used in part, or an equivalent exists that covers part of the intent.
  - `GAP` — not present.
  - `DIFFERENT-BY-DESIGN` — not present because a recorded neXus decision chose another approach.
  - `UNKNOWN` — cannot be judged from the repository; the reason is stated.
- Effort vocabulary: `S` = days, `M` = one to four weeks, `L` = more than a month or a redesign. Effort is for one experienced engineer and excludes enterprise approval lead time.
- "needs ARK spec" marks rows that depend on the company convention set, which is not in this repository.
- What this document does not prove: behaviour on a real OpenShift cluster, compatibility of the Instana agent with the runtime versions in use, and anything about the enterprise side. Those are marked `UNKNOWN`.

## 2. What neXus is made of

One Gradle build (`ui2/settings.gradle.kts:19-34`) with thirteen Java modules, one frontend workspace, one image and six Kubernetes workloads.

| Deployable | What it is | Evidence |
| --- | --- | --- |
| `ui2-service` | Spring Boot 4.0.8 web application; REST API, serves the compiled screen, sign-in, RBAC, scheduling. 67 controllers, 244 mapped routes. | `ui2/service/build.gradle.kts:7`, `:28`; `deploy/ui2/50-service-deployment.yaml:53` |
| `ui2-worker` | Plain Java process (no Spring); claims device jobs and runs SSH/API reads against firewalls. | `deploy/ui2/52-worker-deployment.yaml:58`; `ui2/worker/build.gradle.kts:15-24` |
| `ui2-configuration` | Same image, worker role `configuration`; exposes a small HTTP endpoint with the JDK built-in server. | `deploy/ui2/54-configuration-deployment.yaml:36`; `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/configuration/server/Ui2ConfigurationServer.java:70` |
| `ui2-compliance` | Same image, worker role `compliance`; same pattern. | `deploy/ui2/55-compliance-deployment.yaml:36` |
| `ui2-policy` | Same image, worker role `policy`; policy collection and failover execution. Committed with `replicas: 0`. | `deploy/ui2/57-policy-deployment.yaml:19`, `:44-48` |
| `ui2-packet-analysis` | Main image plus `tshark`; worker role `packet_analysis`; DuckDB store on its own volume. | `deploy/ui2/58-packet-analysis-deployment.yaml:67-69`; `ui2/Containerfile.packet-analysis:110` |
| `ui2-db` | PostgreSQL 16 StatefulSet inside the namespace. | `deploy/ui2/40-database-statefulset.yaml:48` |
| Frontend | React single-page application, compiled at build time and packed into the service jar; not a separate deployable. | `ui2/Containerfile:21-36`, `:60`; `ui2/service/src/main/resources/application.yml:33-35` |
| CLI | `nexus-cli` inside the service image, same jar. | `ui2/Containerfile:116` |
| e2e Job and CronJob | Playwright browser test image, run in the cluster nightly. | `deploy/ui2/70-e2e-job.yaml:26`, `:89` |

All roles start from one launcher that picks the role from the first argument (`ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/launch/Ui2Launcher.java:31-34`). Every workload is `replicas: 1` (policy: 0) with the `Recreate` strategy (`deploy/ui2/50-service-deployment.yaml:17-19`), so a rollout is a short outage by construction.

Size, for scale: 1231 main Java files and 751 test Java files; 160 TypeScript source files and 131 Vitest test files; 196 Flyway migrations, latest `V197`.

Spring usage is concentrated in one module: 139 of 321 main files in `service` import Spring; the other ten production modules (`worker` 284 files, `persistence` 198, `packet-analysis` 151, `job-engine` 128, `platform-core` 78, and five smaller ones) import none.

Not deployables, and out of scope for onboarding: `history/` (retired Python product, 448 files, with its own `Dockerfile` and Compose files), `relay/`, `tools/`, `scripts/`, `roles/`, `plugins/` (development governance and delivery tooling).

## 3. Channel (frontend)

| Reference | neXus today | Status | Effort | Risk or blocker |
| --- | --- | --- | --- | --- |
| Language: TypeScript | TypeScript 5.5.4; the build fails on a type error (`tsc --noEmit`). `ui2/frontend/package.json:14`, `:33` | MATCH | — | None. |
| Framework: React | React 18.3.1. `ui2/frontend/package.json:22-23`. Also MUI 5, Emotion, ECharts and xyflow (`:17-21`). | MATCH | — | The extra UI libraries need to be on the enterprise approved list and resolvable from Artifactory. |
| Framework: Axios | Not used. Calls go through the browser `fetch` API behind one timeout wrapper (`ui2/frontend/src/auth/adminApi.ts:112`); that file holds 102 exported functions in 4153 lines, and seven other files call `fetch` directly (for example `ui2/frontend/src/auth/LoginScreen.tsx:26`). | GAP | S to M: swap the wrapper for an Axios instance; M if every call site must be rewritten to Axios idioms. | The CSRF header (`adminApi.ts:161`) and cookie behaviour must stay identical; streamed downloads (`adminApi.ts:512`) need care. Low value on its own: ask whether Axios is mandatory or a default. |
| Test: Vitest | Vitest 2.1.9, 131 test files, jsdom. `ui2/frontend/package.json:11`, `:35`; `ui2/frontend/vite.config.ts:14-19` | MATCH | — | Playwright browser tests (`package.json:12`, `:26`) are extra to the reference; ask whether a browser-test tool is allowed in the pipeline. |
| Build: Vite | Vite 5.4.21. `ui2/frontend/package.json:14`, `:34` | MATCH | — | Node 22 is required (`package.json:8`); the enterprise builder image must offer it. |
| Protocol: HTTP/1 | The embedded Tomcat serves HTTP/1.1; no HTTP/2 setting exists in `ui2/service/src/main/resources/application.yml:9-16`. No WebSocket or server-sent events in the frontend source. | MATCH | — | None. |
| Monitoring: Instana | No monitoring script in the page (`ui2/frontend/index.html:1-17`). | GAP | S: add the Instana website-monitoring snippet with a per-stage key. | Needs the enterprise reporting endpoint and key; the page has no Content-Security-Policy today, so nothing blocks the script, but that also means there is no CSP to show a reviewer. |
| Implied: Channel is its own deployable (behind Nginx) | The screen is packed into the service jar and served by the service from the same origin (`application.yml:33-35`; `ui2/Containerfile:60`). | DIFFERENT-BY-DESIGN | M to split into an Nginx-served static site plus reverse proxy. | API paths are not under one prefix: `/login`, `/session/status`, `/roles`, `/backups/...` sit beside `/api/v2/...` (`LoginScreen.tsx:26`, `AuthGate.tsx:34`, `CustomRolesPanel.tsx:60`, `adminApi.ts:512`). A proxy needs the full route list or the API needs one prefix first. The session cookie is `SameSite=Strict` (`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/api/SessionCookieWriter.java:61`), so the split must stay same-origin. |

Finding outside the reference rows: the page loads an icon font from a public font CDN at run time (`ui2/frontend/index.html:6-10`). On a corporate network without internet egress the icons will not render, and it is an outbound browser call a security review will ask about. Self-hosting the font is `S`.

## 4. aPaaS (application services)

| Reference | neXus today | Status | Effort | Risk or blocker |
| --- | --- | --- | --- | --- |
| Language: Java | Java 21 toolchain for every module. `ui2/build.gradle.kts:40-44` | MATCH | — | Confirm the enterprise Java baseline is 21 (the code uses Java 21 features by recorded decision, `docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md:280`). |
| Framework: Spring, Spring Boot | Spring Boot 4.0.8 with Spring MVC, in the `service` module only (`ui2/gradle/libs.versions.toml:12`; `ui2/service/build.gradle.kts:7`, `:28`, `:42`). Workers are plain Java with HikariCP and JSch (`ui2/worker/build.gradle.kts:15-24`); their HTTP endpoints use the JDK built-in server (`Ui2ConfigurationServer.java:70`). | PARTIAL | None if "Spring Boot for web services" is the rule. L if every deployable must be a Spring Boot application (five worker roles, 284 files). | Spring Boot 4.0 is very recent; the enterprise starter, parent POM or Instana agent may still target 3.x. Version baseline is an open question. |
| Framework: Spring Cloud Data Flow | Not used. neXus has its own job engine: jobs, steps, leases, time windows and a scheduler in `ui2/job-engine` (128 files) and `ui2/scheduler`, with a PostgreSQL-backed claim queue (`ui2/job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/lease/ClaimStatementText.java:73`). 12 Spring `@Scheduled` methods in the service trigger periodic work. | DIFFERENT-BY-DESIGN | L to move onto Spring Cloud Data Flow. | The job engine carries device-safety rules (one job per device, lease loss, admission, windows, quarantine). Data Flow also needs its own server and normally a message broker. Strong candidate for an exception. |
| Framework: MapStruct | Not used; no dependency in `ui2/gradle/libs.versions.toml`. Mapping between database rows and API records is hand-written. | GAP | S to add for new code; M to convert existing mappers. | Low value, no functional gain. Ask whether it is mandatory or a recommended default. |
| Test: JUnit | JUnit Jupiter 6.0.3 on every module; 751 test files. `ui2/gradle/libs.versions.toml:3`; `ui2/build.gradle.kts:50-51` | MATCH | — | Integration tests need a PostgreSQL 16 container (Testcontainers, `ui2/integration-tests/build.gradle.kts:32-34`); a Jenkins agent must be able to start one or be given a database. |
| Test: ArchUnit | ArchUnit 1.3.0 in a dedicated module; 13 test classes, 44 test methods. `ui2/gradle/libs.versions.toml:4`; `ui2/architecture-tests/build.gradle.kts:18` | MATCH | — | None. This is a strength to show: module boundaries and mutation fences are machine-checked. |
| Build: Maven | Gradle 8.14.3, Kotlin DSL, version catalog, dependency locking for all configurations and checksum verification metadata. `ui2/gradle/wrapper/gradle-wrapper.properties:4`; `ui2/build.gradle.kts:73-76`; `ui2/gradle/verification-metadata.xml` (3250 lines); 13 `gradle.lockfile` files | GAP | M: 13 module POMs, the frontend step, CI commands, the container build and the deploy scripts all call `gradlew`. | The repository's own decision table says Maven "would require a successor contract and equivalent locks/verification" (`docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md:281`). Maven has no built-in equivalent of Gradle's checksum verification; the supply-chain control would have to move to Artifactory. Ask whether Gradle is acceptable when it resolves only from Artifactory. |
| Protocol: HTTP/1, HTTP/2 | HTTP/1.1 only at the application; HTTP/2 is not switched on. TLS ends at the edge (Traefik on HOST-A, `deploy/hosta/traefik-config.yaml:14-36`; the Route on OpenShift). | PARTIAL | S: one Spring Boot property if HTTP/2 to the pod is required. | Probably not required; confirm. |
| Monitoring: Instana | No agent configuration, no tracing library, no Spring Boot Actuator, no metrics endpoint. Health is one `/healthz` path per process (`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/HealthController.java:15`). | GAP | S if the platform's Instana agent instruments JVMs automatically; M if job-level tracing through the non-Spring workers is expected. | `UNKNOWN`: whether the agent supports Spring Boot 4, Java 21, the JDK built-in HTTP server and JSch. Pods run with a read-only root filesystem and all capabilities dropped (`deploy/ui2/50-service-deployment.yaml:169-175`); agent attachment must work under that. |

## 5. DaaS (data services)

neXus has no separate data-service tier. The service and every worker open their own connections to one PostgreSQL database through a shared `persistence` library.

| Reference | neXus today | Status | Effort | Risk or blocker |
| --- | --- | --- | --- | --- |
| Language: Java | Java 21. | MATCH | — | None. |
| Framework: Spring Boot, Spring Data JDBC | jOOQ 3.19.11 for all data access (`ui2/gradle/libs.versions.toml:7`; `ui2/persistence/build.gradle.kts:8`); the `persistence` module has 198 files and no Spring import. Spring's JDBC starter is present in the service only (`ui2/service/build.gradle.kts:42`), and `JdbcTemplate` appears in three files. Schema changes are Flyway 10.17 migrations run by the application, not by Spring (`application.yml:31-32`, `:50-52`). | DIFFERENT-BY-DESIGN | L to rewrite onto Spring Data JDBC repositories. | Recorded decision: "PostgreSQL + jOOQ + Flyway Community ... no ORM auto-DDL" (`docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md:283`). The SQL is hand-written with row locking and compare-and-set updates that Spring Data JDBC's repository model does not express directly. Highest regression risk in this document. Candidate for an exception. |
| Database: Oracle or PostgreSQL | PostgreSQL 16, as a StatefulSet in the application namespace with a community container image (`deploy/ui2/40-database-statefulset.yaml:48`), 2 Gi volume (`deploy/ui2/30-database-pvc.yaml:23`). Oracle is recorded as deferred (`UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md:283`). | MATCH on the engine; PARTIAL on the hosting model | S to point at an enterprise-managed PostgreSQL: the connection is host, port and name from a ConfigMap plus credential files (`deploy/ui2/10-configmap.yaml:19-21`; `application.yml:47-51`). | The repository lists this as open item U-7 (`docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md:601`). neXus needs two database roles (migrate and application) and runs its own migrations at start-up; a managed service must allow that. Backup of the in-cluster database is a host script today (`deploy/hosta/nexus-platform-backup.sh`). |
| (no reference row) Second engine | DuckDB 1.5.6, embedded, file-based, for packet-capture analysis only (`ui2/gradle/libs.versions.toml:22`; `ui2/packet-analysis/build.gradle.kts:6`), on a 120 Gi volume (`deploy/ui2/36-packet-analysis-pvc.yaml:23`). | No slot | — | Not Oracle or PostgreSQL. Needs an explicit ruling as an embedded analysis file format rather than a database service. It ships native code inside the JDBC jar. |
| Messaging: IBM MQ, RabbitMQ, Kafka | None. No broker client in any build file. Work is queued in PostgreSQL and claimed with `FOR UPDATE SKIP LOCKED` (`ClaimStatementText.java:73`; `ui2/persistence/src/main/java/com/securityexpert/nexus/ui2/persistence/jobrecords/JooqJobLeaseDao.java:87`). Pods talk to each other over plain HTTP inside the namespace. | DIFFERENT-BY-DESIGN | None if messaging is only required when a system publishes or consumes enterprise events. L to move the job queue onto a broker. | A database queue keeps job state and job claim in one transaction; a broker would split that. Ask whether a broker is mandatory or "use one of these if you need messaging". |
| Protocols: AMQP, MQTT over WebSocket | None. The screen polls over HTTP. | GAP (not applicable today) | — | Becomes relevant only if live push to the screen is wanted. |
| Monitoring: Instana | As in section 4. Database calls go through plain JDBC (PostgreSQL driver 42.7.13, HikariCP 7.0.2), which agents usually see. | GAP | S | Same `UNKNOWN` as section 4. |

Storage that the reference stack does not name: an encrypted backup artefact store on a 400 Gi volume and a report store on 10 Gi (`deploy/ui2/35-artefact-store-pvc.yaml:23`; `deploy/ui2/37-report-store-pvc.yaml:19`). All four volumes are `ReadWriteOnce`, and the packet-analysis volume is mounted by two pods (`deploy/ui2/36-packet-analysis-pvc.yaml:6`). That works on one node; on a multi-node OpenShift cluster the pods must be co-scheduled or the storage model must change.

## 6. DevOps

| Reference | neXus today | Status | Effort | Risk or blocker |
| --- | --- | --- | --- | --- |
| Source: Bitbucket | GitHub, pull requests, one required check. `.github/workflows/validation.yml:29-33` | GAP | S to move the repository; M to re-create the gates and the pull-request tooling, which calls the GitHub command line (`tools/delivery/nexus_po_tool_gate.py`, `tools/delivery/orchestrator_providers.py`). | The Git history must pass the enterprise secret and data scan before import; the repository scans its own history with Gitleaks (`tools/security/security_scan.sh:58`, `.gitleaks.toml`), but the enterprise rules may differ. |
| Branch management: same as ARK | Trunk-based: short-lived branches into `main` through pull requests; no release branches. | UNKNOWN — needs ARK spec | — | — |
| Project naming: same as ARK | Product `neXus`; build root `ui2`; Java package root `com.securityexpert.nexus.ui2`. | UNKNOWN — needs ARK spec | S to M if the Java package or artefact names must change. | A package rename touches every Java file and every ArchUnit rule. |
| Deployment namespace: same as ARK | One namespace `ui2` (`deploy/ui2/00-namespace.yaml:12`) and a build namespace `ui2-build` (`deploy/ui2-image-build/00-namespace.yaml:10`). The namespace name is written into every manifest. | UNKNOWN — needs ARK spec | S: rename across about 30 manifest files. | The repository records as open whether the platform team pre-creates the project (U-4, `UI2_0_B1_01C_...CONTRACT.md:598`). |
| Repo naming: same as ARK, with labeling | One repository holding product, deployment manifests, design documents, delivery tooling and the retired Python product. | UNKNOWN — needs ARK spec | S to M to split product from tooling if ARK expects one repository per deployable. | Manifests carry `app.kubernetes.io/part-of` and `component` labels (`deploy/ui2/56-service-internal.yaml`); ARK label keys are unknown. |
| Build: S2I with a Jenkins pipeline | A Kaniko Job inside the cluster builds `ui2/Containerfile` (`deploy/ui2-image-build/30-build-job.yaml:39-43`), started by a shell script with `kubectl` (`deploy/ui2-image-build/run_build.sh:59-73`). No Jenkinsfile exists. Tests run in GitHub Actions and in local scripts. | GAP | M: a Jenkinsfile with the test stages, plus either an OpenShift build that uses the Containerfile or custom S2I scripts. | The Containerfile does hardening that a stock S2I Java build does not: it removes the Python packages (`ui2/Containerfile:82-83`), blocks a set of CA roots (`:99-111`), and closes every writable path except two (`:136-145`). The build stage is the Red Hat UBI 9 OpenJDK 21 builder image (`:39`), which is the S2I family, but the stock S2I Java build flow is Maven-oriented (to be verified against the enterprise builder image). Ask whether a Containerfile-based build inside Jenkins is accepted as "S2I". |
| Deployment: Jenkins, ArgoCD | Shell scripts over SSH and `kubectl apply` against one host (`tools/delivery/hosta_deploy.sh:2-19`; `deploy/ui2-image-build/run_build.sh:34`), with a snapshot-based rollback (`tools/delivery/rollback.sh:1-5`). Manifests are plain YAML, no Helm or Kustomize, with the image pinned by digest in Git (`deploy/ui2/50-service-deployment.yaml:50`). | GAP | M | Plain manifests in Git with pinned digests suit ArgoCD well. The hard part is the logic in the scripts: refusing a rollout while a device job is running (`hosta_deploy.sh:7`), waiting for workers to drain, ordering the database before the service. That has to become ArgoCD sync waves and hooks, or stay as a Jenkins stage. |
| Artifacts: Artifactory | Dependencies come straight from Maven Central and the Gradle plugin portal (`ui2/settings.gradle.kts:6-7`; `ui2/build.gradle.kts:70`) and the public npm registry (`ui2/Containerfile:33`). Base images come from the Red Hat registry, Quay, Docker Hub, Google's and Microsoft's registries (`ui2/Containerfile:21`, `:39`, `:70`; `ui2/Containerfile.packet-analysis:26`; `deploy/ui2/40-database-statefulset.yaml:48`; `deploy/ui2/05-flyway-bootstrap.yaml:24`; `deploy/ui2-image-build/30-build-job.yaml:39`; `ui2/frontend/Dockerfile.e2e:2`). Built images go to a registry inside the cluster (`deploy/ui2-image-build/05-registry.yaml:29`). | GAP | S to M: repository and registry address substitution; the build is already written to accept a corporate CA (`ui2/Containerfile:25-32`). | Every dependency is version-locked and checksum-verified, and every base image is pinned by digest, so a proxying Artifactory can serve identical bytes. Two things to fix first: the frontend stage turns off TLS certificate checking for npm (`ui2/Containerfile:33`), and the packet-analysis image downloads and compiles Wireshark source in a Rocky Linux stage (`ui2/Containerfile.packet-analysis:26`). |

What is already in place and worth showing in an onboarding review: Trivy scans of the source tree, the manifests and each image, a CycloneDX software bill of materials per image, Semgrep, Gitleaks on tree and history (`tools/security/security_scan.sh:49-76`), and a browser-level ZAP scan (`tools/security/security_dast.py`).

## 7. IaaS

| Reference | neXus today | Status | Effort | Risk or blocker |
| --- | --- | --- | --- | --- |
| OpenShift | Runs on single-node k3s (HOST-A). Designed for OpenShift's `restricted-v2` rules from the first contract: Red Hat UBI 9 base, no fixed user id on the main workloads, group-0 file ownership, read-only root filesystem, all capabilities dropped, seccomp default (`ui2/Containerfile:70-175`; `deploy/ui2/50-service-deployment.yaml:34-36`, `:169-175`; `docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md:91-94`). An OpenShift Route is committed to replace the Ingress (`deploy/ui2/openshift/60-route.yaml:13-27`). | PARTIAL | M | Never run on OpenShift: the contract's stage 3 (`UI2_0_B1_01C_...CONTRACT.md:530`) has not been reached, so admission is unproven. The contract's claim that only the Route differs no longer holds for the manifests as committed; see the five findings below. |
| Nginx | Not used by the Java product. `deploy/nginx/default.conf:1-14` belongs to the retired Python product. The screen is served by the embedded Tomcat; the edge on HOST-A is Traefik (`deploy/hosta/traefik-config.yaml:7-36`). | GAP | M, same work as the Channel split in section 3. | Ask what Nginx is for in the reference: static hosting of Channel, reverse proxy, or both. |

Manifest findings that would stop or complicate an OpenShift admission as committed:

1. Fixed user ids. The Flyway bootstrap Job sets `runAsUser: 10001` (`deploy/ui2/05-flyway-bootstrap.yaml:14`) and the e2e Job and CronJob set `runAsUser`, `runAsGroup` and `fsGroup` to 1000 (`deploy/ui2/70-e2e-job.yaml:21-23`, `:107-109`). `restricted-v2` rejects ids outside the namespace range; the repository's own contract explains why (`UI2_0_B1_01C_...CONTRACT.md:215-238`).
2. A host directory mount. The worker mounts a `hostPath` inbox for a vendor push feed (`deploy/ui2/52-worker-deployment.yaml:268-272`). The contract already says this is for the k3s stage only and that the OpenShift mechanism is unknown (`UI2_0_B1_01C_...CONTRACT.md:375-376`).
3. A cluster-wide object. The packet-analysis manifest creates a `PriorityClass` (`deploy/ui2/58-packet-analysis-deployment.yaml:14-27`), which a project-level deployer normally may not create.
4. Extra permissions. The service's default service account is given read access to pods and pod metrics (`deploy/ui2/54-service-status-rbac.yaml:4-30`) and calls the Kubernetes API for the status screen (`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/system/SystemStatusService.java:83`, `:270`). This needs platform approval, or the screen should read from Instana instead.
5. Single-node storage assumptions. Four `ReadWriteOnce` volumes, one shared by two pods (section 5), and the Route has no `tls` block, so edge TLS policy is undefined.

## 8. Logging

| Reference | neXus today | Status | Effort | Risk or blocker |
| --- | --- | --- | --- | --- |
| ELK (ARK Logging Console Log) | Every process writes to the console, which is what a cluster log collector reads. The format is plain text and not uniform: the service uses Spring Boot's default Logback output (`application.yml:114-116`); other modules use the JDK `System.Logger` (65 files), `java.util.logging` (20 files) and SLF4J (5 files). No JSON layout and no request or trace identifier in the log context were found. | PARTIAL | S to M: one logging facade and a JSON console encoder across all roles. | The required field set and format is "needs ARK spec". The repository rule that no credential or connection string may reach a log (`UI2_0_B1_01C_...CONTRACT.md` SEC-5) supports this move. |
| Splunk | The product can forward audit events and job failures as RFC 5424 syslog over UDP or TCP to a configured receiver (`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/notification/SyslogSender.java`; `NotificationForwarder.java:76`, `:125`). The audit trail itself is a PostgreSQL table with a redaction policy (migrations `V5`, `V7`, `V153`). | PARTIAL | S if Splunk accepts syslog; M if the HTTP Event Collector or TLS syslog with client certificates is mandatory. | Syslog here is plain UDP or TCP; no TLS option was found in the sender. |

Not to be confused with the above: neXus also collects system logs from firewalls as product data. That is a product feature, not application logging.

## 9. Traces and metrics

| Reference | neXus today | Status | Effort | Risk or blocker |
| --- | --- | --- | --- | --- |
| Instana | Nothing: no agent settings, no OpenTelemetry or Micrometer dependency in `ui2/gradle/libs.versions.toml`, no metrics endpoint. The System Status screen reads pod CPU and memory from the Kubernetes metrics API instead (`SystemStatusService.java:83`). Worker liveness is a heartbeat row in the database (`ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/Ui2WorkerMain.java:655`). | GAP | S for infrastructure and JVM metrics through the platform agent; M for end-to-end job traces (screen, service, queue, worker, device). | `UNKNOWN`: agent support for the runtime versions (section 4). Work crosses a database queue, which automatic tracing does not follow; trace context would have to be stored with the job. |

## 10. Security

| Reference | neXus today | Status | Effort | Risk or blocker |
| --- | --- | --- | --- | --- |
| Framework: Spring Security | Not used; no dependency. Access control is a custom chain run by a Spring MVC interceptor on every route: session lookup, CSRF check, action lookup, RBAC decision, audit of the decision (`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/GateChain.java:17-25`, `:96-97`; `GateChainInterceptor.java:46`; `SecurityWebMvcConfig.java:367-371`). Sessions are server-side rows; the cookie is `HttpOnly` and `SameSite=Strict` (`SessionCookieWriter.java:58-61`). Local passwords are hashed with Argon2id (`ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/Argon2PasswordHasher.java:15-24`). | DIFFERENT-BY-DESIGN | L to replace the chain. M to put Spring Security in front for authentication only and keep the chain for authorization. | Every route is mapped to a named action in a closed list, and unmapped routes are refused. Re-expressing that in Spring Security annotations risks silent gaps. The middle path (Spring Security for sign-in, existing chain for permissions) arrives with the SAML work below. |
| Login: SAML | Not present. Two sign-in mechanisms: local accounts and LDAP or Active Directory bind over LDAPS or StartTLS (`ui2/ldap-adapter/src/main/java/com/securityexpert/nexus/ui2/identity/ldap/DirectoryConnection.java:24-29`), with directory groups bound to roles. Single sign-on appears in design documents only as a deferred item (`docs/design/M14_LOCAL_LDAP_AUTHORIZATION_ARCHITECTURE.md:546-548`). | GAP | M | The mechanism registry is built for user-name-and-password checks (`MechanismRegistry.java:28-36`); SAML is a browser redirect flow and needs a new entry path that ends in the same session creation (`LoginFlow.java:143-144`). Needs from the enterprise: identity-provider metadata, which attribute carries groups, and whether the local account, which the code makes impossible to remove (`MechanismRegistry.java:14-21`), is allowed as a break-glass account. |
| Secret management: legacy CyberArk | Not present. Two kinds of secret: (a) platform secrets — database users and passwords and five 256-bit keys — are Kubernetes Secrets mounted as files; the tracked Secret manifests hold no values (`application.yml:47-51`, `:71`, `:77`; `deploy/ui2/20-secret.yaml`; `deploy/ui2/50-service-deployment.yaml:246-262`). (b) Firewall and directory credentials are stored by neXus itself in PostgreSQL, encrypted with AES-256-GCM under one of those keys (`ui2/platform-core/src/main/java/com/securityexpert/nexus/ui2/platform/CredentialStoreCipher.java:27`), behind one interface (`CredentialStorePort.java:20`). | GAP | (a) S to M: the application already reads secrets from files, which is how CyberArk's Kubernetes integrations deliver them. (b) M to L: a CyberArk-backed implementation of the credential interface, fetching at job time. | (b) is a policy decision before it is an engineering one: may a product keep privileged firewall credentials in its own store, or must they stay in CyberArk with rotation? If CyberArk rotates a password while a long device job runs, the job design must tolerate it. "Legacy CyberArk" suggests a specific older integration method; which one is an open question. |
| CNAPP | No enterprise product integrated. Own controls: image, dependency, manifest and secret scanning with a bill of materials (section 6), hardened pod settings (section 7), NetworkPolicies on three workloads (`deploy/ui2/56-service-internal.yaml:20`; `deploy/ui2/57-policy-deployment.yaml:198`; `deploy/ui2/58-packet-analysis-deployment.yaml:178`). | PARTIAL | S on the neXus side; the platform attaches its own scanner and admission rules. | Likely findings to prepare for: the SSH library is JSch 0.1.55, the last release of that artefact line, from 2018 (`ui2/gradle/libs.versions.toml:17`, `:56`); the `hostPath` mount; fixed user ids; `tshark` compiled from source; npm TLS checking disabled at build. |
| XDR | Nothing in the repository; this is a platform and node concern. HOST-A has its own host controls (audit rules, file-integrity, host firewall under `deploy/hosta/`) that do not carry over to OpenShift nodes. | UNKNOWN | S on the neXus side | neXus behaviour can look hostile to a detection tool: one pod opening SSH sessions to many firewalls, packet-capture files, a packet dissector binary. Allow-listing should be agreed before the first run. |

Also relevant to a security review, with no reference row: the service opens a second port, 8086, for machine sessions used by the in-cluster browser tests and diagnostics, fenced by a filter and a NetworkPolicy (`ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/security/MachinePortConfiguration.java:21-39`; `deploy/ui2/56-service-internal.yaml:20-45`). Decide whether that port exists at all on the corporate platform.

## 11. iPaaS

The reference names iPaaS without detail, so every row is `UNKNOWN`. neXus uses no integration platform. The outbound and inbound integrations that an iPaaS team would ask about:

| Integration | Direction and protocol | Evidence | Likely home |
| --- | --- | --- | --- |
| Firewall management access | Outbound SSH (exec and interactive shell), HTTPS management API, vendor XML API | `ui2/worker/src/main/java/com/securityexpert/nexus/ui2/worker/transport/ssh/SshExecTransport.java`, `.../https/HttpsDeviceClient.java`, `.../xmlapi/PanXmlApiTransport.java` | aPaaS (see section 12); not a fit for iPaaS |
| Directory | Outbound LDAPS or StartTLS | `ui2/ldap-adapter` | Security |
| Mail notifications | Outbound SMTP relay | `ui2/service/src/main/java/com/securityexpert/nexus/ui2/service/notification/SmtpRelaySender.java` | iPaaS candidate, if the enterprise offers a mail or notification service |
| Audit and failure forwarding | Outbound syslog | `SyslogSender.java`, `NotificationForwarder.java:76` | Logging |
| Vendor file pushes | Inbound files through a host SFTP receiver and a host directory | `deploy/hosta/quantum-spark-sftp-receiver.md`; `deploy/ui2/52-worker-deployment.yaml:268-272` | iPaaS candidate (managed file transfer); no OpenShift mechanism exists today |
| Vulnerability source refresh | Outbound HTTPS from a host timer | `deploy/security/security-source-refresh.timer` | DevOps or iPaaS |

## 12. neXus components the reference stack has no slot for

| Component | What it does | Evidence | Where it would fit | What needs a ruling |
| --- | --- | --- | --- | --- |
| Device transport | Opens SSH and API sessions to firewalls and management servers, with stored host-key and certificate trust. | `ui2/worker/.../transport/` (21 files); `ui2/gradle/libs.versions.toml:17` | aPaaS, as a workload with special network egress | Network path from OpenShift to device management networks, fixed egress addresses for firewall rules, and whether privileged sessions must pass through a session-recording gateway. This is likely the first hard blocker, and it is outside the repository. |
| Job engine and scheduler | Durable jobs with leases, time windows, admission and quarantine; PostgreSQL queue. | `ui2/job-engine`, `ui2/scheduler`; `ClaimStatementText.java:73` | aPaaS, in the place the reference gives to Spring Cloud Data Flow | Exception to Spring Cloud Data Flow and to the message broker. |
| Policy, configuration and compliance workers | Collect and analyse firewall policy and configuration; compliance checks; failover execution that changes device state. | `deploy/ui2/54-`, `55-`, `57-` manifests | aPaaS | Change-management: failover writes to production firewalls (`deploy/ui2/57-policy-deployment.yaml:53-55`); who approves and where it is recorded. |
| Packet analysis | Parses uploaded packet captures with `tshark` and stores results in DuckDB. | `ui2/Containerfile.packet-analysis`; `ui2/packet-analysis` (151 files); `application.yml:86-93` | aPaaS, with a storage exception | A native binary built from source, a second database engine, captures up to 2 GB each on a 120 Gi single-writer volume. Captures are sensitive data: retention and classification. |
| Sanitizer (anonymizer) | Replaces names, addresses and serials in exported files; keeps a time-limited mapping. | `ui2/sanitizer` (39 files); `application.yml:94-106` | aPaaS, a library inside the service | Whether the enterprise has a mandated data-masking service. |
| Backup artefact store | Encrypted device backups on a 400 Gi volume, streamed to the browser on download. | `deploy/ui2/35-artefact-store-pvc.yaml:23`; `ui2/platform-core/.../ArtefactStoreCipher.java:50`; `application.yml:26-30` | DaaS, as file or object storage | Whether large binary storage must be an object store; six-hour request timeout for downloads through the Route. |
| Reports | Renders PDF and spreadsheet reports. | `ui2/gradle/libs.versions.toml:20`, `:25` | aPaaS | None beyond library approval. |
| Command-line client | Same actions as the screen, run inside the pod. | `ui2/Containerfile:114-117` | DevOps or operations tooling | Shell access into production pods is usually restricted on OpenShift; the client may need to run from outside. |
| In-cluster browser tests | Nightly Playwright run against the live service through the machine port. | `deploy/ui2/70-e2e-job.yaml:84-89` | DevOps (test stage) | Whether test workloads may run in a production namespace. |
| Host controls on HOST-A | Host firewall, audit rules, file integrity, backup timer, AppArmor profile. | `deploy/hosta/` | IaaS; replaced by the platform | Nothing to migrate; confirm the platform covers database backup. |

## 13. Priority list

### 13.1 Must change to be onboardable

Ordered by how early each blocks progress.

1. Source and artefact supply (S to M). Move the repository to Bitbucket; resolve every dependency and base image through Artifactory; push images to the enterprise registry. Remove the npm TLS bypass (`ui2/Containerfile:33`) and self-host the icon font (`ui2/frontend/index.html:6-10`) first.
2. Pipeline (M). A Jenkinsfile that runs the existing test stages and builds the image; agreement on Containerfile build versus S2I scripts.
3. OpenShift admission (M). Fix the five manifest findings in section 7, parameterise the namespace and image references, add TLS policy to the Route, then prove admission on a real project. Until that run happens, OpenShift readiness is a design intention, not a fact.
4. Deployment through ArgoCD (M). Express rollout ordering and the "no rollout during a device job" rule as sync waves or hooks.
5. SAML sign-in (M). New sign-in path ending in the existing session; role mapping from identity-provider groups.
6. Platform secrets from CyberArk (S to M). File delivery of the database credentials and the five keys.
7. Instana (S to M). Agent on all six workloads, website monitoring on the screen, a decision on job-level tracing.
8. Console log format (S to M). One JSON format across all roles, to the ARK field set.
9. Network path to the firewalls (size unknown, outside the repository). Without it the product has nothing to manage.

### 13.2 Can be argued as an exception

| Item | Argument | What makes the argument credible |
| --- | --- | --- |
| jOOQ instead of Spring Data JDBC | Hand-written, locked SQL for job claiming and compare-and-set state changes; 198 files; rewrite is months with the highest regression risk and no user-visible gain. | Data access sits behind repository interfaces in one module; 88 integration test files run against real PostgreSQL. |
| Own job engine instead of Spring Cloud Data Flow | The engine encodes device-safety rules that a generic pipeline tool does not have. | ArchUnit fences around mutation paths (`ui2/architecture-tests`, for example `FailoverMutationFenceTest.java`). |
| No message broker | The queue and the job state share one database transaction; nothing is published to or consumed from other systems. | Offer to adopt a broker when the first enterprise event integration appears. |
| Own access-control chain instead of Spring Security authorization | Closed route-to-action map, refusal by default, every decision audited. | Offer the middle path: Spring Security for SAML authentication, existing chain for permissions. |
| Gradle instead of Maven | Dependency locking and checksum verification are already enforced; conversion is weeks with no functional gain. | Offer Artifactory-only resolution and a published bill of materials. Weakest of the five arguments if the Jenkins shared library assumes Maven. |
| Plain Java workers instead of Spring Boot | Workers have no web layer; they are queue consumers. | Only credible if Instana can still see them. |
| Fetch instead of Axios; no MapStruct | Convenience libraries; no architectural effect. | Low cost to concede if the answer is no; do not spend exception credit here. |
| Frontend inside the service image instead of a separate Nginx deployable | Same-origin by construction, strict cookie policy, one artefact to scan. | Concede if Channel monitoring or caching policy requires the split. |
| DuckDB and `tshark` for packet analysis | Embedded analysis engine and dissector with no enterprise equivalent; isolated in its own image, pod and NetworkPolicy. | May be easier to leave this module out of the first onboarding scope. |

### 13.3 Open questions for the enterprise architecture team

Conventions (ARK):

1. What are the ARK rules for branch model, project name, repository name and labels, and deployment namespace? Is one repository per deployable required?
2. What is the "ARK Logging Console Log" format: field names, JSON or text, required correlation fields?

Baselines:

3. Which Java and Spring Boot versions are approved? neXus is on Java 21 and Spring Boot 4.0.8.
4. Which rows of the reference are mandatory and which are defaults "if you need one"? In particular Axios, MapStruct, Spring Cloud Data Flow, the message brokers, and Maven.
5. Must every deployable be a Spring Boot application, or only those that serve HTTP?

Build and deploy:

6. Is a Containerfile-based image build inside Jenkins accepted as S2I, or are builder-image S2I scripts required?
7. Can Jenkins agents start a PostgreSQL container for integration tests, or is a shared test database provided?
8. Does ArgoCD own database migrations and rollout ordering, or may a Jenkins stage keep that logic?
9. Is the OpenShift project pre-created by the platform team, and may a project create a PriorityClass or read pod metrics?

Data:

10. Is PostgreSQL provided as a managed service? Does it allow a separate migration role and application-run migrations?
11. What storage classes exist: is shared (multi-writer) file storage or object storage available for about 500 Gi of backups and captures?
12. Is an embedded analysis database file (DuckDB) acceptable inside a pod volume?

Security:

13. Which CyberArk integration is "legacy CyberArk": the credential provider agent, the central credential provider web service, or a Kubernetes secrets provider?
14. Must privileged firewall credentials stay in CyberArk and be fetched per use, with rotation, or may neXus keep them encrypted in its own store?
15. Must administrative sessions to firewalls pass through a privileged-session gateway? If so, automated SSH and API collection needs a supported path through it.
16. Is a local break-glass account allowed beside SAML?
17. Which CNAPP and XDR products are in use, and what is the process for allow-listing expected behaviour such as SSH fan-out and packet-capture handling?
18. Which identity-provider attribute carries group membership, and who administers the group-to-role mapping?

Platform and integration:

19. What is Nginx for in the reference: static hosting for Channel, reverse proxy, or both?
20. What does iPaaS offer: mail relay, managed file transfer, API gateway? Inbound vendor file pushes need a landing point that is not a host directory.
21. How does a workload on OpenShift reach firewall management networks, and from which source addresses?
22. Does Instana support the runtime versions in use, and is job-level tracing through a database queue expected?

## 14. Scorecard

Rows counted once per reference item, across sections 3 to 11.

| Status | Count | Items |
| --- | --- | --- |
| MATCH | 10 | TypeScript, React, Vitest, Vite, Channel HTTP/1, Java (aPaaS), JUnit, ArchUnit, Java (DaaS), PostgreSQL engine |
| PARTIAL | 6 | Spring and Spring Boot, HTTP/2, OpenShift, ELK console logging, Splunk, CNAPP |
| GAP | 12 | Axios, Instana (Channel, aPaaS, DaaS, traces and metrics), MapStruct, Maven, AMQP and MQTT, Bitbucket, S2I with Jenkins, Jenkins and ArgoCD, Artifactory, Nginx, SAML, CyberArk |
| DIFFERENT-BY-DESIGN | 4 | Spring Cloud Data Flow, Spring Data JDBC, messaging, Spring Security |
| UNKNOWN | 6 | ARK branch management, ARK project naming, ARK namespace, ARK repository naming, XDR, iPaaS |

Instana is counted once in the GAP row although it appears in four layers.
