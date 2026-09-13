# PO Decision Record — 2026-09-13 — Independently deployable services

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-13.** Successor amendment to
`docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
**EP-1**, which is FROZEN and is not edited in place. It changes exactly one
clause there. `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §7 is consequentially
affected and its successor is named in §5.

It also resolves the contradiction reported in
`UI2_0_C3A_LOCAL_AUTHENTICATION_CONTRACT.md` §9's neighbouring class — a
disagreement between two authorities that `AGENTS.md` forbids reconciling
silently. This document is the Product Owner resolving it.

## 1. The contradiction, as it stood

Three authorities disagreed on one axis — whether a capability may be its own
deployable unit:

| Source | Status | Position |
| --- | --- | --- |
| `UI2_0_B1_01C` **EP-1** | FROZEN, Product Owner approved 2026-09-12 | "One image, one entry point. The workload role is selected by an argument, **not by a separate image**, a separate tag, or an environment variable interpreted by a shell." |
| `UI2_0_B1_01A` §7 | FROZEN | One jar with a typed role argument for service/worker/scheduler |
| `UI2_0_V1_SCOPE_AND_SERVICE_TOPOLOGY_BRIEF.md` | DRAFT | UI 2.0 v1 is one logical application; discovery and configuration/inventory are capabilities that "**do not add a deployable service**" |
| `PO_DECISION_RECORD_2026_09_13C` §2 | FROZEN, 2026-09-13 | Three separately deployable services |

**How it arose, recorded so the process failure is visible.** The Product
Owner assistant wrote `13C` §2 from the Product Owner's stated architecture
without checking it against `01C`'s frozen EP-1. `AGENTS.md`'s authority
hierarchy requires a contradiction between two authorities to be reported, not
created; this one was created by omission on 2026-09-13 and found the same day
while rescuing the topology brief.

## 2. The decision

**PO directive, 2026-09-13.** **Independently deployable services, starting
now.** The Product Owner's stated reason, in their own framing: *discovery must
be replaceable tomorrow, or directory login, or the UI web server.*

- **DS-1. A capability may be its own deployable unit.** `EP-1`'s prohibition
  on a separate image per role is **superseded**. A service may be built,
  versioned, released and replaced independently of the others.
- **DS-2. `EP-1`'s other half stands.** A workload's role is still selected
  explicitly and never by "an environment variable interpreted by a shell".
  What changes is that a separate image is now permitted; what does not change
  is that role selection stays typed and explicit.
- **DS-3. `13C` §2's three services stand**, and the set is open rather than
  closed: the Product Owner named directory login and the UI web server as
  further candidates. Adding one is a contract clause, never an implementation
  decision.
- **DS-4. The cost is accepted deliberately, not overlooked.** The topology
  brief's objection — distributed transactions, duplicated authorization, and
  cross-service evidence joins, incurred before a demonstrated need to split —
  was put to the Product Owner with this decision and the Product Owner chose
  replaceability over it. The brief is not wrong; it is outweighed.

## 3. What this decision does NOT settle, and must not be assumed

Each of these is a real consequence of DS-1 and none of them is decided here.
An implementing movement that assumes an answer is inventing scope.

- **AUTH-PLACEMENT.** `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md` (FROZEN)
  fixes **one active session per identity** and places RBAC enforcement and the
  gate chain in `service`. With several deployable services, either every
  service enforces it — duplicating the boundary the contract deliberately
  keeps in one place — or something in front of them does. **This is the
  largest open question created by DS-1** and it blocks any second service
  reaching an authenticated surface.
- **DATA-OWNERSHIP.** `UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` (FROZEN) makes
  Flyway the sole migration authority over one schema, and audit a database
  trigger (`fn_audit_capture`). One shared PostgreSQL preserves both and avoids
  distributed transactions; a database per service breaks both. Neither is
  chosen here.
- **DEVICE-IDENTITY-AUTHORITY.** The topology brief's warning stands and is
  adopted as a constraint regardless of how the above resolve: **two services
  may never acquire independent, conflicting authority over the same device or
  HA target.** One service owns device identity; the others reference it.
- **BUILD-AND-RELEASE.** `01C` fixes an image built inside the cluster with no
  host toolchain. Several images multiply that recipe. How, is a later clause.
- **INTER-SERVICE-BOUNDARY.** Whether services speak over HTTP, events, or only
  through the shared database, and what compatibility guarantee holds across a
  version skew, is undecided.

## 4. Sequencing consequence

Until AUTH-PLACEMENT is decided, only **one** authenticated surface exists and
it is the existing `service`. The local-authentication implementation now in
flight is unaffected: it lives inside that surface and its contract
(`UI2_0_C3A`) is mechanism-level, not topology-level.

A second deployable service may be built, but it may not expose an
authenticated surface of its own until AUTH-PLACEMENT is settled.

## 5. Consequential successor owed

`UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §7 ("one jar with a typed role
argument") is narrowed by DS-1 in the same way `01C` EP-1 is. It is not amended
here because this record's scope is the deployment clause; a skeleton successor
carrying DS-1 through to the build layout is owed and is named as a follow-on.

## 6. Cross-references

- `UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md` EP-1 —
  the clause this supersedes; every other clause of that contract stands.
- `UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §7 — consequentially narrowed,
  successor owed (§5).
- `PO_DECISION_RECORD_2026_09_13C_DISCOVERY_SERVICE_BOUNDARIES.md` §2 —
  upheld, and its service set is open rather than closed (DS-3).
- `UI2_0_V1_SCOPE_AND_SERVICE_TOPOLOGY_BRIEF.md` — the objection that was
  weighed and outweighed, and the device-identity constraint adopted from it.
- `UI2_0_C3_IDENTITY_SESSIONS_RBAC_CONTRACT.md`,
  `UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` — the two contracts AUTH-PLACEMENT and
  DATA-OWNERSHIP must be resolved against.
- `AGENTS.md` — authority hierarchy, and the rule against silently reconciling
  two authorities.
