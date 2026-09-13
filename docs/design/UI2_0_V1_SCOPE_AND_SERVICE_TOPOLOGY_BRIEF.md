# UI 2.0 — v1 scope and service topology brief

## Status

**DRAFT — architecture recommendation only, NOT implementation authority,
2026-09-11.** This records the smallest deployable topology for UI 2.0 v1.
It creates no runtime, schema, migration, image, device command, or delivery
commitment. It does not settle an unresolved vendor semantic.

## 1. Recommendation

UI 2.0 v1 is one logical Java application, delivered as the existing
one-JAR, one-image deployment shape. `service`, `worker`, and `scheduler`
are role-selected entry points in that image, not independently deployable
feature services. PostgreSQL is the UI 2.0-owned persistence boundary; it is
not a feature service. This preserves B1-1's one-image contract and avoids
distributed transactions, duplicated authorization, and cross-service
evidence joins before there is a demonstrated operational need to split.

| Boundary | v1 responsibility | Explicitly not its responsibility |
| --- | --- | --- |
| `service` | HTTP API, session/RBAC enforcement, static UI, and read models for Project Plan, discovery/onboarding, inventory/configuration, compliance, backup, and readiness | device transport, job claiming, scheduler loop |
| `worker` | claim and execute admitted capability jobs; write sanitized projections/provenance through the shared job and persistence ports | browser/API surface, a feature-specific worker, uncontrolled device action |
| `scheduler` | evaluate approved schedules and create job requests through `job-engine` | device contact, a second execution engine |
| `persistence` | Flyway-owned schema, audit trigger, provenance, and projections | business decisions or raw vendor-response retention |
| `capability-registry` / `job-engine` | shared capability/gate and job-state boundaries used by all features | a network adapter or feature-owned persistence schema |

The frontend remains a build-time asset consumed by `service`. Project Plan is
a `service` read model over durable project-state inputs; it needs no
independent service or direct repository/browser-to-device path. Discovery,
configuration/inventory, compliance, backup, and failover readiness are
capabilities and projections behind the same boundaries. A capability may add
its own Flyway-owned projection table and worker implementation when its
contract permits; it does not add a deployable service.

Controlled failover execution is outside v1. Readiness may be represented as
sanitized, fail-closed evidence; no topology statement authorizes a class-2
action, plan executor, or browser-to-device path.

## 2. v1 scope map

| Requested v1 concern | Boundary | Status and limit |
| --- | --- | --- |
| Project Plan visibility | `service` read model | v1 read-only presentation; no GitHub-source decision is made here |
| Discovery/onboarding | `service` API + later capability worker | manual registration is B1-4b; full discovery remains `REL-DISCOVERY` |
| Configuration and inventory | capability worker + `service` projections | first B1 capability is the narrow CP inventory subset; configuration follows its own capability contract |
| Compliance | capability worker + `service` projections | `REL-CHECKS`; inherited proof remains `UNPROVEN` until revalidated |
| Backup and restore | `worker` + `scheduler` + shared persistence | `REL-BACKUP`; restore remains structurally disabled pending its named amendments/admission gates |
| Failover readiness | capability worker + `service` projections | `REL-FAILOVER-READINESS`; controlled failover is a later, separately gated release |

## 3. Exact B1-2 V1 inputs

`B1-2` implements the frozen C1 contract rather than deriving a new topology.
Its V1 migration input is C1 §3's ownership matrix and sketches:

- `devices`, `endpoints`, and `credential_references` as opaque-identifier
  placeholders for B1-4b;
- `jobs` and `job_steps` identity/audit-linkage columns, with lifecycle
  semantics owned by C2;
- `audit_log` and `fn_audit_capture`, including fail-closed transaction-local
  actor/action context and grants that prevent direct application writes;
- `provenance_records`, `secrets_metadata`, and the first
  `cp_inventory_projection` with mandatory provenance linkage;
- Flyway-only migration authority, `ui2_migrate`/`ui2_app` role separation,
  no raw-output column, and C1 §9's ten Testcontainers acceptance checks.

The migration is packaged through `persistence` into `service`, as B1-1
specifies. It is not a new schema service. C3-owned session/RBAC tables,
C4-owned registry tables, and C7-owned backup/artefact tables retain their
own contracts and migrations; B1-2 must not invent or absorb them.

## 4. Blockers and non-decisions

1. **Authority conflict — B1-1 readiness.**
   `UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` declares itself `DRAFT`, but
   `project/backlog.json` calls that contract frozen. Under `AGENTS.md` the
   contract-status document outranks project JSON. The Product Owner must
   freeze or correct the contract before B1-1 implementation, and B1-2
   cannot assume a `ui2/` skeleton exists until then.
2. **B1-2 sequencing.** C1 is frozen and defines the schema inputs, but the
   B1-1 skeleton is the required implementation home. B1-2 therefore waits
   for the resolved B1-1 authority and its resulting Gradle/Testcontainers
   harness; this brief makes no roadmap reorder.
3. **Feature facts and vendor semantics.** The first capability's extraction
   spec/fixtures and its unresolved channel-drain completeness question stay
   with C6 and the named B1 capability movements. Compliance, backup, and
   readiness retain their existing validation gates; this brief supplies no
   new evidence.
4. **Split trigger.** Revisit a deployment split only when measured
   independent scaling, isolation, release cadence, or failure containment
   cannot be met by the role-selected one-image deployment. A future split
   needs a successor architecture contract covering authorization, audit,
   provenance, transactions, and deployment evidence.

## 5. Checks and next movement

This recommendation preserves the frozen runtime direction (Java only),
independent UI 2.0 schema, one executor, raw-evidence prohibition, opaque
identity law, and no Browser → device path. It intentionally leaves project
state unchanged: the recommendation does not alter the existing sequence.

Next: Product Owner resolves the B1-1 status conflict; then the authorized
`B1-1` implementation movement creates the one-image skeleton. After its
validation, `B1-2` may implement C1 V1 in that existing harness.

## 6. Authority references

- `AGENTS.md`
- `docs/design/UI2_0_BASELINE_CONTRACT.md` §§1–2 (FROZEN)
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §§2–3, §9 (FROZEN)
- `docs/design/UI2_0_C2_JOB_EXECUTION_CONTRACT.md` (FROZEN)
- `docs/design/UI2_0_C4_CAPABILITY_REGISTRY_GATE_RESOLUTION_CONTRACT.md` (FROZEN)
- `docs/design/UI2_0_C7_BACKUP_ARTEFACT_RESTORE_ENGINE_CONTRACT.md` (FROZEN)
- `docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` (DRAFT; not implementation authority)
- `docs/design/UI2_0_DEVELOPMENT_WORKFLOW.md` §5 (BASELINE)
