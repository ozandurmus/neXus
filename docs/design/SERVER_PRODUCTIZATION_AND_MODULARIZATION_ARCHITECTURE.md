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

**Amendment 2026-08-31 (`CON.0`).** The condition this paragraph attaches to —
"before real operational need and the relevant gates exist" — has now been met
on one axis and only one: the BackBox exit
(`BACKUP_AND_RECOVERY_ARCHITECTURE.md` §2) makes a local operator surface a
stated operational need. The sanctioned form of that surface is
`docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md`, and **none of the prohibitions
above are relaxed by it**: it is not a generic REST wrapper (a closed
server-side job registry, no browser-supplied commands or arguments), not part
of the report viewer (§2 below), not exposed beyond loopback, and not a
Kubernetes or multi-worker change. See §7 for the full reconciliation.

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

---

## 7. Amendment — the sanctioned control plane (2026-08-31, `CON.0`)

§2 requires that "future policy, scheduling, exclusion, check-pack, or recovery
administration must use a separately authenticated and audited control plane;
it must not be added to the static viewer." `CON.x`
(`docs/design/OPERATOR_CONSOLE_ARCHITECTURE.md`) is that control plane, in its
first, deliberately smallest deployment shape. This section records how it
satisfies each boundary this document set, so a future reader does not have to
reconstruct the reasoning.

| This document requires | How `CON.x` satisfies it |
|---|---|
| No generic REST wrapper around `main.py` | A closed, source-resident job registry (`CON.2` `C2-1`). The browser sends a `job_type` plus `entity_id` values validated against `unified.json`; no command string, flag or free-form argument reaches argv. |
| No browser-supplied device commands | Same boundary, stated as `CON.0` §4. Argv is built by the server from a fixed template shared with the scheduler (`CON.2` `C2-2`). |
| The control plane is not added to the static viewer | The console is a separate application in the worker trust zone. The nginx viewer is unchanged, keeps its read-only report mount, and never gains a console route (`CON.0` §7.5). |
| The viewer may never hold credentials, evidence metadata, runtime keys or recovery material | Unchanged. The console holds no credential in the browser at all; recovery payload is unreachable over HTTP in every phase (`CON.0` §7.6, `CON.3` `C3-7`, `CON.4` `C4-1`). |
| Separately authenticated and audited | Per-launch cookieless bearer token on a loopback bind now (`CON.0` §7.2); durable job records with `provenance="console"` from `CON.2`; corporate OIDC + role mapping only at `CON.6`, behind this document's §6 gates. |
| Single worker, static report near-term | Preserved. The exported report keeps its inline single-script form and gains no action surface; the console is a second consumer of the same composed modules (`CON.1` `C1-2`). |

**Not authorized by this amendment:** running the console anywhere but loopback
on an operator workstation (`CON.0` `C-D5`), publishing a console port from
`docker-compose.yml`, any new device command, or any device write beyond the
`D3` pilot allowlist that `RB.3b` already governs. `CON.6` — the console behind
corporate OIDC on a server — remains blocked on the complete §6 gate set and is
deliberately left uncontracted.

§5's modularization plan is unchanged and remains a precondition rather than a
casualty: `CON.1` depends on the frontend split landing first, and reuses its
module order rather than introducing a second composition path.
