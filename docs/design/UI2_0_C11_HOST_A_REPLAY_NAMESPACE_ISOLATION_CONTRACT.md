# UI 2.0 — C11 HOST-A `ui2-replay` namespace isolation contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-15.** Authored under movement
`NXS-LOCAL-0239` (`ARCHITECTURE`), on explicit Product Owner instruction to
design and freeze a distinct, isolated `ui2-replay` Kubernetes namespace
contract for `HOST-A`, so an operator can later browse the `role:replay_viewer`
projection (`UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md`) against synthetic
data first, and against a privacy-gated HMAC-anonymized package in a future,
separately authorized movement. This document authorizes no manifest file, no
`kubectl apply`, no namespace creation, and no other `HOST-A` command — it
fixes the isolation and data-flow boundaries and the exact successor; a
follow-up `IMPLEMENT` movement, dispatched separately and gated by the
preconditions of §3, builds and applies them.

No `HOST-A` command, no production `ui2` namespace read or change, no device
access, and no production data of any kind was touched to produce this
freeze; only repository source and design/contract documents were read.

---

## 1. What this resolves, and what it does not

This is a new contract, not a successor to a prior `ui2-replay`-specific
design document — none existed before this movement. It composes three
already-frozen contracts rather than re-deciding any of them:
`UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md` (the
image and manifest model), `UI2_0_C8_MOCKUP_PALACE_TEST_ENVIRONMENT_CONTRACT.md`
(the synthetic seed mechanism), and
`UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md` (the anonymized read-only
role). What this contract adds is the **deployment-level namespace, data,
credential and network boundary** those three do not themselves fix, because
none of them addresses running on a second, separate namespace on a shared
host.

It does not authorize implementation of C9's `RoleToken.REPLAY_VIEWER`
activation (C9 §11 item 2, unstarted) or C8's seed-loader `cli` subcommand
(C8 §9 item 1, unstarted). Those remain their own contracts' successors; this
document only fixes where and how their eventual output may be deployed.

## 2. Authority chain (highest first)

1. `AGENTS.md` — durable constitution: identity law, evidence laws,
   UNKNOWN/fail-closed law, raw-evidence law, host action boundary.
2. `docs/design/PO_DECISION_RECORD_2026_09_15A_THE_DEVELOPMENT_HOST_AND_WHAT_AN_AGENT_MAY_DO_ON_IT.md`
   (FROZEN) and `docs/design/HOST_REGISTER.md` (FROZEN) — what an agent may
   execute on `HOST-A` specifically, as distinct from what the product may
   execute against a device; §3 below restates their binding effect on this
   contract's preconditions, never their content.
3. `docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
   (FROZEN) — the image, the OpenShift-safety construction rules `OS-1`
   through `OS-10`, the Secret path `SEC-1` through `SEC-7`, and the
   portability rules `PORT-1` through `PORT-4` this contract's manifests
   must also satisfy.
4. `docs/design/UI2_0_C_POSTGRESQL_NETWORKPOLICY_CONTRACT.md` (FROZEN) — the
   precedent NetworkPolicy shape (`NP-1` through `NP-4`) this contract reuses
   for the new namespace's own database isolation.
5. `docs/design/UI2_0_C8_MOCKUP_PALACE_TEST_ENVIRONMENT_CONTRACT.md`
   (FROZEN) — the `mockup-palace` Spring profile, the `mockup_palace_marker`
   guard, the embedded UnboundID identity fixture, and the v1 seed scope this
   contract's synthetic mode reuses unchanged.
6. `docs/design/UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md` (FROZEN) — the
   `role:replay_viewer` activation shape, the exclusive read-only session, the
   server-side projection funnel, and the durable per-installation HMAC key
   this contract's identity boundary (§7) depends on.
7. `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` (FROZEN, Phase A scope
   only) — the offline, fresh-key-per-export pattern this contract cites as
   precedent for the deferred future HMAC-anonymized package (§6), not as
   authority for this contract's live namespace boundary.

## 3. Precondition finding: `HOST-A` is a shared host, not yet a running cluster

A read of `docs/design/HOST_REGISTER.md` and
`docs/design/PO_DECISION_RECORD_2026_09_15A_THE_DEVELOPMENT_HOST_AND_WHAT_AN_AGENT_MAY_DO_ON_IT.md`
during this freeze found a fact the dispatching task did not itself state and
that governs every clause below: **`HOST-A` is a second product's host — its
"logger" workload runs there today — not a dedicated neXus host**, and its
agent tier ceiling is `HOST_R` (read-only: node/cluster health, our own
workloads' status and logs, our own namespace's objects) as of this freeze.
The register states the ceiling rises to `HOST_W1` only when, in order: a
dedicated non-`sudo` account exists, the human has installed the Kubernetes
runtime under `HOST_W2`, the kubeconfig handed to the agent is scoped to
**neXus's own namespace** (singular), and an `EV-1` ledger baseline is
recorded. None of those four conditions is reported as met by this movement's
own reading, and this movement produces no ledger entry and performs no host
action.

Two consequences bind this contract's implementation successor, not this
document's own freeze:

- **PRE-1.** No manifest this contract names may be applied to `HOST-A`
  until the register's ceiling reaches `HOST_W1` for the account that would
  apply it. Until then, this contract's manifests are specified but
  unappliable, exactly as B1-01C's own manifests were specified but
  unappliable at its own freeze (B1-01C §13).
- **PRE-2.** Because the kubeconfig the register describes is scoped to a
  **single** namespace, the `Namespace` object itself (`kind: Namespace`) is
  a cluster-scoped resource outside that scope by construction — creating it
  is `HOST_W2` ("anything outside that workspace"), never `HOST_W1`. The
  human performs the `ui2-replay` namespace creation and the RBAC/kubeconfig
  scoping that follows it; the agent prepares the exact command and the
  validation plan, per `HOST_W2`'s own definition, and applies only what
  falls inside the resulting scoped namespace thereafter.
- **PRE-3.** `HOST_X` ("reading, copying, tailing, querying or exporting the
  incumbent's data, containers, logs, volumes or database... prohibited, no
  authorization form exists") applies to the second product's logger
  workload without exception for the duration this host is shared. No check
  this contract names (§9) may read, list, or select that workload's pods,
  namespace, Service, or data by any selector, broad or narrow.

**Whether `ui2-replay` and the production `ui2` namespace will ever share one
cluster is `UNKNOWN` (U-1)**, unresolved by this movement's own reading —
the baseline this movement was dispatched against describes production `ui2`
as remaining live, without stating where. §8 (the NetworkPolicy clauses) is
written to hold under either answer: it assumes nothing about what else the
cluster contains and denies by default rather than by naming an absent peer.

## 4. Namespace and labels (resolves the "distinct namespace" requirement)

**Decision: `ui2-replay`**, a namespace distinct in name from the production
`ui2` namespace `UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
§5.2 and `UI2_0_C_POSTGRESQL_NETWORKPOLICY_CONTRACT.md` §2 already fix.

**Decision: `app.kubernetes.io/part-of: nexus-ui2-replay`**, a label value
distinct from production's `nexus-ui2` (`UI2_0_C_POSTGRESQL_NETWORKPOLICY_CONTRACT.md`
§2), not merely a different namespace carrying the same label value. Two
namespaces already isolate a `podSelector`-only NetworkPolicy (a peer
selector without a namespace selector matches only within the policy's own
namespace, per §8 below), so a shared label value would not by itself create
a cross-namespace hole. The distinct value is chosen for a different reason:
an operator running `kubectl get pods -A -l app.kubernetes.io/part-of=nexus-ui2`
must not silently enumerate the replay namespace's pods alongside
production's, and a future NetworkPolicy or RBAC rule written against
`nexus-ui2` must not accidentally admit `ui2-replay` by label match alone.
`app.kubernetes.io/component` values (`database`, `service`) are reused
unchanged, since they classify a workload's role within its own namespace
and carry no cross-namespace authority.

**NS-1.** No manifest under the `ui2-replay` set names the production
namespace `ui2`, the production Service names `ui2-db` or `ui2-service`, or
any production Secret name, in any field — not a `ConfigMap` value, not a
`NetworkPolicy` peer selector, not an `Ingress`/`Route` backend, not an
environment variable default. This is checked by grep in §9.

## 5. Image selection (resolves "image selection")

**Decision: the identical image, no fork.** The `ui2-replay` namespace's
`service`-role Deployment references the same digest-pinned OCI image
`UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md` §3
defines for production — same `ui2/Containerfile`, same two-stage build, same
arbitrary-UID filesystem model, same entry point (EP-1 through EP-4). No
second `Containerfile`, no second build path, and no image variant built for
replay specifically. B1-01C's own PORT-2 already allows the image *reference*
(the tag or digest deployed) to be a per-stage value; this contract adds no
new exception to that rule; it merely deploys the same artifact into a second
namespace.

**IMG-REPLAY-1.** The `ui2-replay` Deployment's pod template sets the same
`OS-1` through `OS-10` construction rules B1-01C §5.1 fixes for production —
`runAsNonRoot`, no fixed UID/GID, no `hostPath`, no privilege escalation, no
host networking, resource requests/limits, dropped capabilities, seccomp,
`readOnlyRootFilesystem`, no `NodePort`/`LoadBalancer`, no moving image tag.
Nothing about running in a second namespace relaxes any of them.

## 6. Data source and the synthetic-first / anonymized-later boundary

**Decision: v1 is synthetic-only.** The `ui2-replay` service Deployment
activates the `mockup-palace` Spring profile `UI2_0_C8_MOCKUP_PALACE_TEST_ENVIRONMENT_CONTRACT.md`
§5 already names, unchanged — no new profile name is introduced, per that
contract's own finding that nothing in `ui2/` collides with it. The database
it connects to is `ui2-replay`'s own, freshly initialized, empty-first-state
PostgreSQL instance (§7 below), seeded exclusively by C8's own seed-loader
mechanism (C8 §3, §7) once that mechanism's own successor (C8 §9 item 1) is
separately implemented. This contract adds no seed content beyond what C8
already scoped; it only fixes where that seeded database runs.

**DATA-1.** No manifest, init container, or startup script in the
`ui2-replay` set may establish a network connection, DNS reference,
`secretKeyRef`, or volume mount pointing at the production `ui2` namespace's
database, Secret, or `PersistentVolumeClaim`, under any profile, in any
release. This is the load-bearing invariant `.nexus/WORKER.md` states as "no
raw production data in replay server" and "no cross-namespace production
data or credential access," and it is absolute regardless of which seed mode
(synthetic or future anonymized) is active.

**DATA-2. Future production-replay input (not authorized here).** A later
movement may add a second mode that loads a **privacy-gated, HMAC-anonymized
package** — an offline artifact produced outside this namespace's runtime,
following the domain-separated pseudonymization pattern
`docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` (FROZEN, Phase A) already
establishes for an offline export, and the field-classification discipline
`UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md` §5 already fixes for what may
ever be pseudonymized versus excluded — into `ui2-replay`'s own database
through a bounded import path this contract does not design. What this
contract fixes now, as a forward boundary binding on that future movement, is
the shape it must **not** take: it must never be a live connection, tunnel,
replication stream, or federated query against the production database; it
must never mount the production `PersistentVolumeClaim`, snapshot, or backup
artifact directly; and the package's own generation, transport, and privacy
review are that future movement's own contract to write and its own Product
Owner decision to authorize; this document authorizes none of it.

**DATA-3.** Whichever mode is active, `ui2-replay`'s own database never
receives the production installation's per-installation HMAC key
(`UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md` §5's durable, per-installation
key) — the pseudonymization key `ui2-replay` observes, if any, belongs to
`ui2-replay`'s own installation identity, never a copy of production's, so
that a pseudonymous label observed here can never be reversed by combining it
with production's own live key custody.

## 7. Database, Secret, and PersistentVolumeClaim (resolves "distinct DB, secrets, PVC")

**Decision: a full, independent PostgreSQL 16 workload**, constructed exactly
as `UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md` §6
fixes for production (`StatefulSet`, one replica, same arbitrary-UID model
`DB-3`, same Flyway `V1`-`V7` migration path `MIG-1` through `MIG-4`, same
clean-empty-first-state discipline `FIRST-1` through `FIRST-3`), with every
named object distinct:

| Object | Name | Namespace |
| --- | --- | --- |
| Database `StatefulSet` | `ui2-replay-db` | `ui2-replay` |
| Database `Service` | `ui2-replay-db` | `ui2-replay` |
| Database `PersistentVolumeClaim` | the StatefulSet's own volume claim template, a distinct claim, never `volumeName`-pinned to production's claim | `ui2-replay` |
| Database `Secret` | `ui2-replay-db-credentials` | `ui2-replay` |
| Service-role `Deployment` | `ui2-replay-service` | `ui2-replay` |
| Service-role `Service` | `ui2-replay-service` | `ui2-replay` |

**DB-REPLAY-1.** The `ui2-replay-db` Secret is generated in the cluster at
creation time, following `SEC-1` through `SEC-7` unchanged: no credential
value in any tracked file, no shared value with the production Secret, and
rotation is a Secret update plus pod restart that changes no tracked file.
There is exactly one credential pair per role (`ui2_migrate`, `ui2_app`, per
B1-01C `MIG-2`), scoped to `ui2-replay-db` alone.

**DB-REPLAY-2.** The `PersistentVolumeClaim` omits `storageClassName` (the
cluster default applies, per B1-01C §5.2) and omits `volumeName`; nothing in
this contract names a specific volume, and no manifest may pin one that
belongs to production's claim.

**DB-REPLAY-3.** The `mockup_palace_marker` guard `UI2_0_C8_MOCKUP_PALACE_TEST_ENVIRONMENT_CONTRACT.md`
§4 already fixes is not optional here — it is the load-bearing check that
stops the `mockup-palace` profile from ever starting against any database
that is not a seed instance, including, by construction, production's. The
`ui2-replay-service` Deployment must refuse to start (never skip, pass, or
serve) if that marker query fails, exactly as C8 §4 already requires.

## 8. Network isolation (resolves "distinct NetworkPolicy")

**Decision: reuse `UI2_0_C_POSTGRESQL_NETWORKPOLICY_CONTRACT.md`'s frozen
shape, applied inside `ui2-replay` with that namespace's own labels, plus one
additional service-side policy the production contract did not need.**

`deploy/ui2-replay/46-database-networkpolicy.yaml` (database isolation,
identical construction to the frozen production policy, `nexus-ui2-replay`
labels):

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: ui2-replay-db-isolation
  namespace: ui2-replay
  labels:
    app.kubernetes.io/part-of: nexus-ui2-replay
    app.kubernetes.io/component: database
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/part-of: nexus-ui2-replay
      app.kubernetes.io/component: database
  policyTypes: [Ingress, Egress]
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app.kubernetes.io/part-of: nexus-ui2-replay
              app.kubernetes.io/component: service
      ports:
        - protocol: TCP
          port: 5432
  egress: []
```

`deploy/ui2-replay/56-service-networkpolicy.yaml` (service-side default
deny, new relative to the production contract because `ui2-replay`'s service
role must not reach any workload beyond its own database and DNS — there is
no `worker` role here to admit, unlike production's `ui2-db-isolation`):

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: ui2-replay-service-isolation
  namespace: ui2-replay
  labels:
    app.kubernetes.io/part-of: nexus-ui2-replay
    app.kubernetes.io/component: service
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/part-of: nexus-ui2-replay
      app.kubernetes.io/component: service
  policyTypes: [Egress]
  egress:
    - to:
        - podSelector:
            matchLabels:
              app.kubernetes.io/part-of: nexus-ui2-replay
              app.kubernetes.io/component: database
      ports:
        - protocol: TCP
          port: 5432
    - to: []
      ports:
        - protocol: UDP
          port: 53
        - protocol: TCP
          port: 53
```

**NP-REPLAY-1.** Both policies follow `UI2_0_C_POSTGRESQL_NETWORKPOLICY_CONTRACT.md`
`NP-1` through `NP-4` unchanged in kind: a `podSelector` without a namespace
selector is restricted to the policy's own namespace by Kubernetes'
NetworkPolicy semantics, so neither policy above can itself admit a peer from
production `ui2` or from the incumbent second product's workload — no
NetworkPolicy in this set ever names a `namespaceSelector`. This holds
regardless of U-1 (§3): if `ui2-replay` and `ui2` ever share one cluster, no
change to either namespace's policy is required for this boundary to keep
holding, because neither policy grants a cross-namespace peer today.

**NP-REPLAY-2.** The service-side policy's DNS egress (`UDP`/`TCP` 53) is the
only egress this contract permits beyond the database; it is required
because the Deployment must resolve its own database Service's DNS name.
No other egress is granted in v1 — not to a container registry, not to an
artifact source for a future anonymized package. A future movement adding
DATA-2's import path must add its own scoped egress rule then, evidenced by
that movement's own need, never widened here in advance.

**NP-REPLAY-3.** As with the production contract, an API object existing is
not evidence of enforcement (`UI2_0_C_POSTGRESQL_NETWORKPOLICY_CONTRACT.md`
§3's closing paragraph, restated because it applies with the same force
here): the CNI's actual enforcement capability on `HOST-A` is unverified
until the successor's real-cluster checks (§10) prove it, and PRE-1 (§3)
means those checks cannot run before `HOST-A` reaches `HOST_W1`.

## 9. Ingress exposure (resolves "bounded ingress")

**Decision: `ClusterIP`-only Service plus one `Ingress` (or `Route`, per
B1-01C PORT-1's single documented substitution) fronting only the
`ui2-replay-service` Service, with no anonymous route to any capability
beyond what `role:replay_viewer`'s exclusive session already permits.**

**ING-1.** No `NodePort`, no `LoadBalancer` Service (`OS-9`, reused). The
`Ingress`/`Route` object is the only external reach, exactly as B1-01C §5.2
fixes for production.

**ING-2.** The host name and TLS termination are per-environment **values**
(B1-01C `PORT-2`), never hardcoded into a tracked manifest as a literal
production-adjacent value, and never disclosed in this document —
`HOST-A`'s network address and reachability boundary are `AGENTS.md`
sensitive-identity-reporting-law material the operator's own credential
store holds, consistent with `HOST_REGISTER.md`'s own refusal to carry an
address, hostname, port, or fingerprint.

**ING-3.** Authentication is enforced by `role:replay_viewer`'s own
activation and session mechanism (`UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md`
§3, §8) at the application layer — the `Ingress`/`Route` object itself adds
no separate authentication mechanism this contract designs, and does not
weaken C9's requirement that every non-surface-listed action refuse
regardless of network reachability. Whether `HOST-A`'s own network position
(firewall, VPN, or public reachability) additionally bounds who can reach the
`Ingress` at all is **`UNKNOWN` (U-2)** — this contract has no evidence of
`HOST-A`'s network topology, consistent with §3's finding that this
movement performed no host reconnaissance. The human operator who controls
`HOST-A`'s network boundary states that boundary before the successor rolls
out; this contract does not assume it is closed merely because it is
unstated.

## 10. Identity for the readonly viewer (resolves "readonly user" access)

**Decision: reuse `UI2_0_C8_MOCKUP_PALACE_TEST_ENVIRONMENT_CONTRACT.md` §6's
embedded, in-memory UnboundID directory unchanged as the identity fixture,
with its seeded synthetic operator DN bound to `role:replay_viewer` instead
of `role:viewer`.** C8 §6 already fixes the mechanism (an `InMemoryDirectoryServer`
bound to `127.0.0.1` on an ephemeral port, started and stopped within the
`mockup-palace` profile's own process lifecycle, exercising `C3` §2's real
bind-and-resolve path) and already fixes that the seeded identity is bound
through the ordinary `role_bindings`/`RbacEvaluator` path, never a parallel
"test mode" branch. This contract makes exactly one parameter decision C8
left open by scoping to `role:viewer`: for the `ui2-replay` deployment
specifically, the seeded binding is `role:replay_viewer`, because the whole
purpose of this namespace is to exercise the role C9 defines, and a
`role:viewer` binding would grant a broader surface (`C3` §4.1's full
"read every projection UI 2.0 ships") than the anonymized, single-surface
session this environment exists to test. No second identity is seeded for
v1; a human browsing `ui2-replay` therefore always sees the projected,
pseudonymized surface, never an unprojected one, which is itself part of
this contract's isolation guarantee — there is no path on this namespace
that renders a raw value even to its own test operator.

**RBAC-REPLAY-1.** This decision does not reopen C9 §5's frozen v1 initial
surface (`DeviceRegistrationController`'s two read-only routes) or its field
classification table. `ui2-replay` exercises exactly that surface once C9's
own `RoleToken.REPLAY_VIEWER` successor (C9 §11 item 2) is implemented; this
contract authorizes no expansion of it.

## 11. Security preconditions

Restating `UI2_0_C_POSTGRESQL_NETWORKPOLICY_CONTRACT.md` §4's precondition
discipline, applied to a new namespace on a shared host rather than a policy
addition to an existing one — the invariant is stronger here, not weaker,
because `HOST-A` hosts a second product's workload:

- Before any target rollout, the cluster owner (the Product Owner, per
  `HOST_REGISTER.md`'s "Credential holder" column for `HOST-A`) attests that
  the dedicated non-`sudo` agent account exists, is not a member of
  `docker`/`adm`/`wheel`, and that the kubeconfig handed to the agent is
  scoped to the `ui2-replay` namespace alone — the `HOST_W1` precondition
  §3 (PRE-1/PRE-2) names.
- No check this contract's successor runs may select, list, or read the
  incumbent second product's namespace, Service, Pod, or data by any
  selector — `HOST_X` (§3, PRE-3) forbids it with no authorization form, and
  a broad or wildcard selector used "just to confirm isolation" is exactly
  the kind of query `HOST_X` prohibits, not an exception to it.
- No production `ui2` Secret, PVC, or database is read, connected to, or
  enumerated by any successor check. Where a check must prove a *denial* of
  reachability (§10 of the production NetworkPolicy contract's own pattern,
  reused in spirit here), it proves denial from a disposable, owner-approved
  synthetic peer, never from a live production or incumbent-workload pod.
- If the cluster owner's attestation, the register's ceiling, or the
  network-topology evidence `UNKNOWN` (U-2) cannot be established before a
  target rollout, the outcome is `BLOCKED`, and the `ui2-replay` namespace is
  not created. This mirrors the production contract's own fail-closed
  posture (§4's "outcome is BLOCKED and the target database stays
  unavailable") applied to namespace creation instead of policy rollout.

## 12. Rollout order and fail-closed behavior (human-run)

1. Obtain the Product Owner's explicit authorization for the specific
   bounded rollout window, separate from this freeze. Confirm `HOST_W1`'s
   four preconditions (§3) are met and recorded in `HOST-A`'s own `EV-1`
   ledger, per `PO_DECISION_RECORD_2026_09_15A...`'s own ledger requirement
   — this contract's freeze is not that ledger entry.
2. The human creates the `ui2-replay` namespace and the scoped kubeconfig
   (`HOST_W2`, PRE-2); the agent prepares the exact command text and does
   not execute it.
3. Within the scoped namespace, apply objects in dependency order: `Namespace`
   already exists (step 2) → `ConfigMap`/`Secret` → `PersistentVolumeClaim`
   → database `StatefulSet`/`Service` → both `NetworkPolicy` objects (§8) →
   run the scratch allow/deny matrix (§10 of the production contract's
   pattern, adapted to `ui2-replay`'s own labels) against disposable
   resources before the service Deployment starts, exactly as the frozen
   PostgreSQL contract's own §5 step 2 requires for its policy.
4. Start the service Deployment only after the database reports ready and
   the scratch NetworkPolicy checks pass. Confirm the `mockup_palace_marker`
   guard (§7 `DB-REPLAY-3`) passes before declaring the environment usable —
   a missing or failing marker check is a start-up failure, never a
   degraded-but-serving state.
5. On any failed or missing check, stop the service Deployment and the
   database, leave the NetworkPolicies installed, and report `BLOCKED` or
   `PARTIAL` with sanitized evidence (no raw address, credential, or
   incumbent-workload detail). Never recover availability by widening a
   NetworkPolicy, adding a `NodePort`, or granting the agent account a group
   membership `HOST_REGISTER.md` does not list.

## 13. Rollback (human-run, fully specified)

Because every object this contract names is new and confined to a namespace
nothing else depends on, rollback is namespace deletion, not an in-place
policy edit:

1. The human scales the `ui2-replay-service` Deployment to zero, confirms no
   session holds an active `role:replay_viewer` activation (per C9's session
   model, so no in-flight read is interrupted mid-response), then scales the
   `ui2-replay-db` `StatefulSet` to zero.
2. The human deletes the `ui2-replay` namespace. Namespace deletion is
   `HOST_W2` (a cluster-scoped action), so the agent prepares the exact
   `kubectl delete namespace ui2-replay` command and its confirmation output
   expectations; the human executes it. Deletion cascades every object this
   contract created — `Deployment`, `StatefulSet`, `Service`,
   `PersistentVolumeClaim`, `Secret`, `ConfigMap`, both `NetworkPolicy`
   objects, and the `Ingress`/`Route` — because none of them was created
   outside this namespace.
3. Nothing in this rollback touches the production `ui2` namespace, the
   incumbent second product's workload, or any `HOST-A` account, package, or
   configuration outside the deleted namespace. A rollback that reaches
   further than the `ui2-replay` namespace is not this rollback.
4. After deletion, `HOST-A`'s tier ceiling and the incumbent workload's
   state are unchanged by this contract's own action; only the Product Owner
   (per `HOST_REGISTER.md`'s "Credential holder" column) changes the
   register's ceiling itself.

## 14. Successor implementation sequence (non-binding, ordered)

Not authorized by this document — named so a Product Owner dispatch has a
concrete next movement to approve, gated by §3's preconditions:

1. **Manifest set** under `deploy/ui2-replay/`: `00-namespace.yaml` (applied
   only per §12 step 2, `HOST_W2`), `10-configmap.yaml`, `20-secret.yaml`
   (keys only, `SEC-2`), `30-database-pvc.yaml`, `40-database-statefulset.yaml`,
   `45-database-service.yaml`, `46-database-networkpolicy.yaml` (§8),
   `50-service-deployment.yaml` (`mockup-palace` profile, `replicas: 1`),
   `55-service-service.yaml`, `56-service-networkpolicy.yaml` (§8),
   `60-ingress.yaml` (§9). Naming and numbering follow `deploy/ui2/`'s
   existing convention so the two sets read as siblings, never as one
   overloaded set.
2. **Conformance tests** in a new `tests/test_ui2_replay_deployment_manifests.py`,
   following `tests/test_ui2_deployment_manifests.py`'s existing parser and
   style: distinct namespace/labels (§4, `NS-1`'s grep), same image
   digest as production (§5), both NetworkPolicies' exact selectors and
   ports (§8), no `NodePort`/`LoadBalancer` (§9), and — the check specific to
   this contract — that no tracked file under `deploy/ui2-replay/` contains
   the string `ui2-db`, the literal namespace `ui2` as a value (not as a
   sibling path component), or any production Secret name.
3. **C8's seed-loader successor** (C8 §9 item 1), extended by exactly the
   parameter §10 names: the seeded identity binding is `role:replay_viewer`
   for a `ui2-replay` deployment. No other change to C8's seed scope.
4. **C9's `RoleToken.REPLAY_VIEWER` successor** (C9 §11 item 2), deployed
   into `ui2-replay` once available; this contract's manifests carry no
   assumption about its implementation shape beyond the role token name.
5. **Real-environment validation**, gated on `HOST-A` reaching `HOST_W1`
   (§3) and on the Product Owner's separate rollout authorization (§12 step
   1) — never claimed from repository tests alone, per `AGENTS.md`'s
   evidence laws and consistent with `UI2_0_C_POSTGRESQL_NETWORKPOLICY_CONTRACT.md`
   §7's own DoD split between `AUTOMATED_VALIDATED` and `REAL_ENV_VALIDATED`.

**Definition of Done for the successor:** exact manifests and conformance
tests delivered and passing; `HOST-A`'s `HOST_W1` preconditions attested and
ledgered by the Product Owner; the namespace, database, and NetworkPolicy
real-cluster matrix (adapted from `UI2_0_C_POSTGRESQL_NETWORKPOLICY_CONTRACT.md`
§6 to `ui2-replay`'s own labels) passes with fresh connections; the
`mockup_palace_marker` guard proven fail-closed (a deliberately broken guard
must trip a test, not merely be absent); the seeded `role:replay_viewer`
identity proven to render only C9 §5's frozen surface and no raw value,
using C9 §11 item 3's adversarial leak test suite against this namespace's
own deployment; rollback (§13) rehearsed once on disposable resources before
being trusted for a real one. Progress is `IMPLEMENTED` then
`AUTOMATED_VALIDATED`; `REAL_ENV_VALIDATED`/`DONE` requires the `HOST-A`
evidence this document's own freeze does not and cannot supply.

## 15. `UNKNOWN` register

| Id | `UNKNOWN` | What settles it |
| --- | --- | --- |
| U-1 | Whether `ui2-replay` and production `ui2` ever share one cluster | a Product Owner infrastructure decision; §8's policies hold under either answer, so this does not block the successor |
| U-2 | `HOST-A`'s network topology and who can reach an `Ingress`/`Route` exposed on it | the human operator's own network configuration, stated before rollout per §9 `ING-3` |
| U-3 | Whether `HOST-A`'s eventual CNI enforces NetworkPolicy at all | the successor's own real-cluster deny/allow matrix (§14 item 5), not this document |
| U-4 | The exact resource request/limit magnitudes for `ui2-replay`'s workloads | one observed run under representative load, per `UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md` U-8's identical posture |
| U-5 | `DeviceRegistrationController`'s `facts` field source table | carried unresolved from `UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md` §7 — this contract adds no new instance of it |

## 16. What this contract defers, and what it does not prove

**Deferred:** the future HMAC-anonymized production-replay-package import
path (§6 `DATA-2`); C9's `RoleToken.REPLAY_VIEWER` implementation; C8's
seed-loader implementation; any search/cache/export surface (none exists in
`ui2/service` today, per C9 §9's own finding, which this contract does not
re-derive); `HOST-A` becoming a dedicated neXus host (`HOST_REGISTER.md`'s
own stated future intent, not this contract's to decide or accelerate).

**What freezing this contract does not prove:** it proves the `ui2-replay`
namespace, database, Secret, PVC, NetworkPolicy, and identity model
described is *constructible* from contracts and code that exist today. It
proves nothing about `HOST-A` itself — no manifest was applied, no namespace
exists, and every check named in §12/§14 is unrun, because `HOST-A` has not
reached `HOST_W1` at the time of this freeze (§3). It is not evidence that
the incumbent second product's workload is unaffected by a future rollout;
that is exactly what the successor's fail-closed rollout order (§12) and the
`HOST_X` prohibition (§3, §11) exist to guarantee procedurally, not what this
document's freeze can certify in advance.

## 17. Cross-references

- `AGENTS.md` — host action boundary, identity law, evidence laws,
  UNKNOWN/fail-closed law.
- `docs/design/HOST_REGISTER.md` (FROZEN).
- `docs/design/PO_DECISION_RECORD_2026_09_15A_THE_DEVELOPMENT_HOST_AND_WHAT_AN_AGENT_MAY_DO_ON_IT.md`
  (FROZEN).
- `docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
  (FROZEN).
- `docs/design/UI2_0_C_POSTGRESQL_NETWORKPOLICY_CONTRACT.md` (FROZEN).
- `docs/design/UI2_0_C8_MOCKUP_PALACE_TEST_ENVIRONMENT_CONTRACT.md` (FROZEN).
- `docs/design/UI2_0_C9_PRODUCTION_REPLAY_ROLE_CONTRACT.md` (FROZEN).
- `docs/design/PRIVATE_REPLAY_ARCHITECTURE.md` (FROZEN, Phase A scope only)
  — evidence and precedent for the deferred future package (§6 `DATA-2`),
  not authority for this contract's live namespace boundary.
- `deploy/ui2/` — the existing production manifest set and naming convention
  this contract's successor mirrors under `deploy/ui2-replay/`, never
  amends.
- `tests/test_ui2_deployment_manifests.py` — the existing test style the
  successor's `tests/test_ui2_replay_deployment_manifests.py` follows.
