# neXus

**neXus** is an on-premises operations platform for enterprise firewall estates. It reads what the firewalls
actually run — inventory, configuration, platform identity, HA state — keeps encrypted backups, evaluates
compliance, and shows the exceptions an operator must act on, without ever sending a command the product has
not been explicitly allowed to send.

Principle: `SEE → VERIFY → TRACE → RECOVER → OPERATE`.

Today it runs against a live estate of 105 devices (Check Point gateways, ClusterXL, VSX, a Multi-Domain
Server; Palo Alto firewalls, HA pairs, Panorama) in 39 clusters.

## What it does

| Module | What it gives you |
|---|---|
| **Overview** | Exceptions first: failed jobs, stale evidence, clusters whose members differ, changed configurations, backup targets without an archive, compliance with coverage and gaps, hotfix spread. Every figure opens its filtered list. |
| **Inventory** | Interfaces, routes, HA roles, virtual systems, serial, software version, jumbo hotfix / content versions, uptime. |
| **Configuration** | The sanitized current configuration per device, section by section; cluster members side by side with every difference marked; change detection. |
| **Compliance** | CIS, PCI-DSS 4.0.1, NIST 800-53 and a financial baseline, evaluated from stored configuration. |
| **Backups** | Check Point and Palo Alto backups, AES-GCM encrypted at rest, schedule and retention, archive listing and compare, download under role-based access. |
| **Operations** | Job history with filters and CSV export, HA readiness (read-only), and the automation modules to come. |
| **Administration** | Devices, credentials, local identities, roles, sessions, LDAP/AD, notifications (syslog, SMTP relay), service and storage view, audit and job logs, project plan. |
| **nexus-cli** | The same actions from a shell, for every screen. |

What the product may execute against a device is a closed, gated list: every device command has a gate row
(vendor, class, shell, timeout, frequency, secret-output risk) before it is ever issued. Writes to devices
are limited to the controlled recovery classes and, when the Script Execution module ships, to scheduled,
ledgered writes under the five conditions in `AGENTS.md`.

## Architecture

```
browser ──HTTPS──▶ ingress ──▶ ui2-service (Spring Boot: API, RBAC gate chain, masking, scheduler)
                                   │        ▲
                                   ▼        │ jobs (lease, heartbeat, drain)
                              PostgreSQL ◀──┴── ui2-worker (SSH / PAN XML API collectors, backups)
                                   │
                        encrypted artefact store (backups, configuration evidence)
```

- `ui2/` — the product: `service`, `worker`, `persistence` (jOOQ, Flyway migrations), `job-engine`,
  `capability-registry` (command gates), `cli`, `frontend` (React, Material 3), `architecture-tests`.
- `deploy/` — Kubernetes (k3s) manifests and the image build (kaniko).
- Runs as unprivileged pods in its own namespace; service and worker 512 MiB requested, 2 GiB limit.

## Build and test

```bash
cd ui2
./gradlew :service:test :worker:test :persistence:test :architecture-tests:test
cd frontend && npm ci && npx vitest run
```

Deploy to the development host (watched, stops at the first failure, 12-minute limit):

```bash
scripts/hosta_deploy.sh
```

## Privacy

Real device names, addresses, serials and configurations never enter this repository, commits or chat.
Screens are reviewed under the `aiview` persona, which sees collision-free pseudonyms (`FW-TANGO-04`,
`CLS-ROMEO-01`). A pre-push privacy gate refuses private endpoints and secrets.

## Where to read next

| Document | Purpose |
|---|---|
| `AGENTS.md` | Engineering and security law (authoritative) |
| `CURRENT_STATE.md` · `project/QUEUE.md` | What is being built now, what is next |
| `docs/design/` | Contracts: Overview, Script Execution, automation, platform identity, vendor backups |
| `project/build_history.json` · `docs/history/INDEX.md` | Build timeline |
| `PRIVACY_AND_DATA_HANDLING.md` | Data-handling rules |

## Status

57 % of the declared Java roadmap is evidenced (2026-09-23): inventory, configuration, backup and compliance
run on the live estate; restore is deliberately disabled; automation, Script Execution and further vendors are
contracted and next. The earlier Python product (`utils/`, `application/`, `main.py`, …) is historical and is
being separated from this repository (`docs/design/LEGACY_PYTHON_SEPARATION_PLAN.md`).
