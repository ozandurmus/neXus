# Server Productization and Modularization Architecture

**Status:** proposed architecture baseline, 2026-08-31  
**Scope:** local-code health now; server productization only after approved infrastructure is available.  
**Does not authorize:** new device commands, device writes, a browser-to-device path, or production deployment.

## 1. Decision

SecurityExpert is on the right product path: retain the read-only evidence
pipeline and evolve it into a controlled internal platform in deliberate
increments. The current Docker Compose shape is a single-operator deployment
foundation, not an internally exposed production application.

The near-term architecture remains a single worker and static report. Do not
introduce Kubernetes, per-vendor workers, a generic REST wrapper around
`main.py`, or browser-supplied device commands before real operational need and
the relevant gates exist.

## 2. Deployment boundaries

```text
Corporate OIDC / SSO
        |
        v
authenticated reverse proxy --> read-only report viewer
                                      |
                                      +--> published report volume only

control plane / external scheduler --> single collection worker --> CP / PAN
                                      |                         (read-only)
                                      +--> evidence metadata database
                                      +--> evidence payload store
                                      +--> separate recovery vault

secrets manager / KMS --> worker only
```

The viewer is a read model. It may never mount recovery artifacts, evidence
metadata, runtime keys, credentials, device trust material, or a Docker socket.
The worker is the only component that receives device credentials and that can
contact network devices. Future policy, scheduling, exclusion, check-pack, or
recovery administration must use a separately authenticated and audited control
plane; it must not be added to the static viewer.

## 3. Local-now versus server-only work

### Local development work, safe before server arrival

1. Remove the unreferenced `utils/cleanup.py` remote-deletion helper. It is
   outside the current read-only product posture even though no active path
   imports it.
2. Establish reproducible release assurance: locked dependencies, CI on the
   supported Python 3.12 container baseline, privacy gate, full regression,
   render harness, secret/dependency/container scans, SBOM and release
   provenance.
3. Define the report's XSS boundary: CSP, regression tests for hostile
   inventory/configuration labels, and one audited rendering/escaping contract.
4. Extract large modules by responsibility while preserving all commands,
   payload shapes, telemetry and current test contracts.
5. Convert runtime-created database schema to versioned migration design before
   the application is given a production database role.

### Server-only work, blocked until infrastructure is available

1. Corporate OIDC with role mapping and audited report view/export events.
2. Strict CP host-key and PAN corporate-CA validation against real endpoints;
   no compatibility transport setting in the production profile.
3. Non-root worker/viewer containers, rootless Docker or user namespaces,
   dropped capabilities, immutable image digests, resource limits and health
   checks.
4. Separate report publication mount from the evidence/runtime mount. The
   current viewer must not receive a read-only mount of the whole runtime
   volume because that volume contains sensitive evidence and the recovery
   wrapping key.
5. Dedicated TLS-enabled PostgreSQL instance, migration role separated from
   application role, encrypted backup and recovery verification.
6. Approved secret manager/KMS and off-host encrypted recovery replication.
   Local volume separation alone is not a disaster-recovery guarantee.
7. Real-environment acceptance: trusted transport, OIDC access, viewer egress,
   multi-container lock/last-known-good behavior, and a restore drill.

## 4. Current implementation findings

| Finding | Disposition |
| --- | --- |
| CP SSH and PAN TLS compatibility modes exist for local continuity. | Retain locally; prohibit in the server profile and validate against real endpoints. |
| Nginx serves only `/runtime/output` but mounts all `/runtime`. | Split to a report-only publication volume before exposure. |
| Recovery artifacts are encrypted and separated from the report volume. | Preserve; add KMS/key custody and off-host recovery before RB.3b is relied on. |
| PostgreSQL backend creates schema at startup. | Replace with deployment-controlled migrations before production least-privilege roles. |
| `requirements-postgres.txt` is optional and absent from the base image. | Add an explicit production image/profile when PostgreSQL modes are enabled. |
| `static/app.js`, `main.py`, and vendor configuration collectors are large. | Incrementally extract seams; no big-bang rewrite. |

## 5. Modularization plan

### Frontend: preserve one portable report

The output remains one dependency-free HTML report with one ordered inline
script. Source modules may be separate, but `html_export` must compose them in
an explicit dependency order. This avoids making report viewing depend on a
server, CDN, browser module loading, or a new frontend framework.

Proposed source ownership:

```text
static/
  app_bootstrap.js       navigation, report initialization, public facade
  app_core.js            safe/escapeHtml, formatters, shared state and DOM helpers
  inventory_ui.js        normalization, cluster collapse, inventory tables/filters
  configuration_ui.js    configuration fleet, current state, alignment, evidence/history
  compliance_ui.js       controls, framework coverage, crypto and trends
  discovery_ui.js        lifecycle, coordinator, scheduler and exclusions
  project_plan_ui.js     roadmap/backlog/build-history presentation
```

Extraction rule: each module may depend only on `app_core` and documented
upstream module APIs. A temporary `window.SecurityExpert` namespace is allowed
at the composition boundary, but no new unconstrained globals. Existing render
harness interactions must remain stable. Every extraction is behavior-only:
no markup, payload, collector, command, or CSS change in the same build.

### Backend: split at orchestration and vendor boundaries

`main.py` should gradually become a thin CLI/bootstrap layer. Its target
ownership is:

```text
application/
  cli.py                argument parsing and mutually-exclusive mode validation
  services.py           runtime paths, logging, backend/service construction
  workflows/
    checkpoint.py       full-stage orchestration and degraded-status policy
    recovery.py         recovery/attestation modes
    maintenance.py      privacy, storage, render and diagnostic modes
```

Vendor collectors should be split only when touched by a bounded feature:

```text
configuration/pan/
  transport.py          TLS/authenticated XML request boundary
  intent.py             Panorama intent and expected compilation
  direct_evidence.py    direct-device identity/effective evidence
  persistence.py        CAS/support projection

configuration/checkpoint/
  targets.py            identity and target resolution
  session.py            interactive SSH/Clish capability boundary
  projection.py         redaction and structured current-configuration projection
  collector.py          bounded collection orchestration
```

This is a direction, not an immediate filesystem migration. Preserve lazy
imports for offline maintenance modes, exact command strings, admission
coordination, redaction-before-persistence, and existing test entry points.

## 6. Acceptance gates

Before a server is opened internally, all of the following are required:

- OIDC authentication, role mapping, TLS and audit event capture;
- report-only viewer volume, no credentials or runtime keys in viewer;
- strict CP/PAN transport trust proven against real endpoints;
- non-root/least-privilege container profile and protected Docker host;
- migrations, restricted database application role and encrypted database backup;
- secret/KMS custody and off-host encrypted recovery path;
- release CI, lockfile, SBOM and vulnerability policy;
- real-environment collection, multi-container state, and recovery-restore
  acceptance evidence.

RB.3a is compatible with this design and may proceed as the current read-only
product build. RB.3b must not be treated as a recovery outcome until the
off-host/key-custody and restore gates are satisfied.

