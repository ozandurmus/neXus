# UI 2.0 — C-series PostgreSQL NetworkPolicy contract

## Status

**FROZEN — PRODUCT OWNER APPROVED, 2026-09-15.** NXS-LOCAL-0235's
approved task explicitly authorizes writing and freezing this contract before
implementation. This movement produces this document only. Manifest delivery
and real-cluster validation are the separate successor in §7.

## 1. Scope and authority

Authority: `AGENTS.md` and
`docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
(FROZEN), subject to the following explicit successor amendment authorized by
the Product Owner's NXS-LOCAL-0235 directive.

**Scoped amendment.** B1-01C §13 defers NetworkPolicy and describes it as a
platform-conditional object prohibited by PORT-3 until required. This successor
closes that deferral and extends §5.2's object set with one NetworkPolicy,
identical on all supported in-cluster PostgreSQL stages. PORT-1's sole
Ingress/Route substitution and PORT-3's prohibition of platform-conditional
construction remain binding. This is an explicit change to the deferred
decision, not an assertion that B1-01C already permitted implementation.

B1-01C EP-2/§13 also defer worker and scheduler workloads, whereas today's
manifest set contains a worker. The dispatch's claim of existing scheduler
labels disagrees with the manifests: no scheduler or separate migration Job is
present. Source establishes the selectors of existing workloads, not authority
to add a workload. This contract permits database traffic for the already
manifested service and worker; it authorizes no worker implementation, scheduler
workload, new migration mechanism, or expansion of either role's behavior.

In scope: policy decisions for the existing in-cluster PostgreSQL workload,
manifest conformance tests, rollout, fail-closed real-cluster checks, and rollback.
Out of scope: changes to application code, schema, Secrets, database contents,
PVCs, runtime manifests in this movement, canonical project state, host/k3s
access, CNI installation/configuration, new RBAC/admission objects, external
databases, device traffic, and new device commands or vendor semantics.

## 2. Current manifest evidence

All paths below are repository evidence, not deployed-state assertions.
`P` means the exact key `app.kubernetes.io/part-of`; `C` means the exact key
`app.kubernetes.io/component`. Both label constraints are mandatory together.

| Evidence | Exact binding |
| --- | --- |
| `deploy/ui2/00-namespace.yaml` | Namespace `ui2`; namespace label `P=nexus-ui2` |
| `deploy/ui2/40-database-statefulset.yaml` | StatefulSet `ui2-db`, namespace `ui2`; selector and pod template `P=nexus-ui2`, `C=database`; one replica; container port `postgresql`, numeric port 5432 |
| `deploy/ui2/45-database-service.yaml` | Service `ui2-db`, namespace `ui2`, type `ClusterIP`; selector `P=nexus-ui2`, `C=database`; port 5432 targets named port `postgresql` |
| `deploy/ui2/50-service-deployment.yaml` | Deployment `ui2-service`, namespace `ui2`; selector and pod template `P=nexus-ui2`, `C=service`; role argument `service`; HTTP port `http` = 8080; startup/readiness/liveness use `/healthz` on `http` |
| `deploy/ui2/52-worker-deployment.yaml` | Deployment `ui2-worker`, namespace `ui2`; selector and pod template `P=nexus-ui2`, `C=worker`; role argument `worker`; database application and migration credential-file references; no network readiness probe |
| `deploy/ui2/10-configmap.yaml` | Database port is the string `"5432"`; database host references the database Service; initialization hook uses the local PostgreSQL socket |
| `deploy/ui2/55-service-service.yaml` | HTTP Service selects only `P=nexus-ui2`, `C=service`, port 8080; it does not front PostgreSQL |

Database readiness and liveness execute `pg_isready` locally on loopback port
5432. Initialization uses the mounted start hook and local socket. Neither
needs remote DNS or a new outbound connection. B1-01C MIG-2/MIG-3 put Flyway
at service startup, before readiness, with database roles kept separate.
NetworkPolicy identifies pods, not database usernames: both runtime and startup
migration traffic from allowed pods use the same TCP permission. The worker's
existing migration-file references receive that same permission; no migration
Job or distinct migration label is invented.

## 3. Frozen policy construction

The successor adds exactly `deploy/ui2/46-database-networkpolicy.yaml`:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: ui2-db-isolation
  namespace: ui2
  labels:
    app.kubernetes.io/part-of: nexus-ui2
    app.kubernetes.io/component: database
spec:
  podSelector:
    matchLabels:
      app.kubernetes.io/part-of: nexus-ui2
      app.kubernetes.io/component: database
  policyTypes: [Ingress, Egress]
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app.kubernetes.io/part-of: nexus-ui2
              app.kubernetes.io/component: service
        - podSelector:
            matchLabels:
              app.kubernetes.io/part-of: nexus-ui2
              app.kubernetes.io/component: worker
      ports:
        - protocol: TCP
          port: 5432
  egress: []
```

**NP-1.** Select only the database pods. Deny ingress except these two exact
actor selectors on TCP 5432. A peer `podSelector` without a namespace selector
is restricted to the policy's namespace. No namespace-wide peer, `ipBlock`,
empty selector, namespace-selector alternative, port range, or extra port is
permitted. ClusterIP alone is not the isolation boundary.

**NP-2.** Deny database-initiated egress with explicit `Egress` and `egress: []`.
Responses to permitted inbound connections do not need an outbound allow rule.
No database DNS exception is granted. No evidence in the current manifests
requires remote database DNS, replication, metrics export, or backup egress.
If cold-start validation reveals such a dependency, stop and revise this
contract using evidence; do not broaden the policy as an operational workaround.

**NP-3.** Service and worker ingress/egress remain outside this policy. Their
existing DNS resolution and database connections must continue, including DNS
UDP/TCP 53 where used by the existing resolver. No namespace-wide egress deny
or guessed CoreDNS/OpenShift resolver selector is introduced. Build-pod egress,
HTTP ingress/probes, mounted Secrets, volumes and existing device transports
are unaffected by construction; no new device contact is authorized for tests.

**NP-4.** Scheduler and separate migration Jobs are denied by default. A future
actor requires a separately approved successor that binds its actual manifest
labels and migration ordering. Development/test pods have no standing access
exception. Only owner-controlled, temporary validation actors may use the
existing service/worker selectors under §6; none mounts database credentials
for a transport-only test.

The selector, additive-policy, reply-traffic and enforcement semantics follow
the official [Kubernetes NetworkPolicy documentation](https://kubernetes.io/docs/concepts/services-networking/network-policies/).
Policies combine their permissions: an overlapping policy can widen access.
An API object alone proves no enforcement. Node-origin traffic is exempt and
`hostNetwork` behavior depends on the plugin; standard NetworkPolicy is not a
node firewall. These limits are required inputs to §4, not hidden exceptions
to a claim of complete isolation.

## 4. Security preconditions and evidence limits

The invariant is that no workload outside explicit UI2 actors reaches
PostgreSQL. NetworkPolicy supplies the ordinary pod-network boundary; cluster
ownership/admission controls must prevent workloads from bypassing it.

Before any target rollout, the successor requires the cluster owner to attest
that untrusted principals cannot create/relabel UI2 actor or database pods,
change their controllers/namespace/policies, grant themselves privileged or
host-network workloads, access UI2 Secrets, or use exec/port-forward/proxy paths
to the database or an allowed actor. No outside workload may use a node-network
bypass to PostgreSQL. UI2 actors and node administrators remain trusted; a
compromised allowed actor is not isolated from its own database permission.
RBAC/admission or node-boundary remediation needs its own authorization and is
not silently added to this successor.

The cluster owner supplies the CNI's enforcement capability and relevant
control attestations; the successor independently proves ordinary-pod denial
in an approved synthetic environment. Do not query another product's workloads,
data or Secrets. If the trust controls, effective overlapping policies, or
enforcement cannot be established, outcome is BLOCKED and the target database
stays unavailable. No workload-isolation claim is made from labels alone.

There are no undecided load-bearing policy choices in this contract. Actual
CNI enforcement, admission and deployed readiness are **UNVERIFIED** until the
successor produces evidence; freeze does not certify a cluster or close any
unrelated B1-01C UNKNOWN. An external-database stage requires a new contract,
not an exception that opens this in-cluster database.

## 5. Rollout order and fail-closed behavior

1. Obtain separate authorization for bounded cluster-API validation/rollout in
   an owner-controlled synthetic environment. This contract freeze is not that
   authorization. Record the manifest revision, owner attestations, policy
   overlap result and intended rollback revision without operational identities.
2. Run §6 first against disposable owner-controlled resources, with no product
   data or credentials. Confirm both allowed connectivity and denied controls
   with new connections before deploying the target. Policy acceptance, or a
   timeout when the listener is unhealthy, is insufficient evidence.
3. For first deployment, create the namespace and policy before any database or
   actor pods. For an existing deployment, stop actors and scale the database
   to zero before introducing the policy; preserve its claim and Secrets.
   Existing connections are not evidence of post-policy denial. Apply through
   the existing approved cluster API; no host runtime or k3s command participates.
4. Start the database only after policy observation and the scratch enforcement
   checks pass. Prove the target deny/allow matrix before starting application
   actors. Start service then worker, retaining existing replica counts and
   migration/readiness behavior. Observe fresh permitted connections, startup
   migration completion and service `/healthz` readiness. No SQL repair, seeding,
   Secret rotation, or concurrent-migration redesign is authorized.
5. On a failed/missing check, stop actors and database, keep policy installed,
   and report BLOCKED or PARTIAL with sanitized evidence. Never recover
   availability by adding a blanket allow or deleting a live database's policy.

Policy propagation can be asynchronous. Scratch success must precede target
startup, and target checks must be repeated with fresh connections after a
database-pod restart. This contract promises validated steady-state isolation;
it does not claim the API alone guarantees instantaneous dataplane convergence.

## 6. Successor acceptance and real-cluster validation

Add conformance coverage to `tests/test_ui2_deployment_manifests.py`, following
its existing parser and test style. Check the exact object, namespace, database
selector, two same-namespace peer selectors, TCP 5432, explicit policy types,
empty database egress, and absence of broadening rules. Bind against the current
StatefulSet/Deployment pod templates and database Service port/targetPort so
label/port drift fails the check. Verify no other delivered policy widens the
database boundary and the shared manifest set includes the policy on every
in-cluster stage without a platform-specific variant.

The authorized successor supplies one bounded, repeatable cluster-API harness
or runbook using approved immutable probe images and disposable resources.
Use new TCP connections with a maximum five-second timeout, close immediately,
and emit verdicts/counters only. A denial must coincide with an allowed control
against the same healthy listener. Connection failure from an absent endpoint
or source-egress block does not prove database ingress enforcement.

| Real-cluster check | Required result |
| --- | --- |
| Same-namespace validation pod with both service labels; repeat with both worker labels | Fresh TCP connection to database Service and directly to its backing pod succeeds on 5432 |
| Same namespace: unlabeled, only part-of label, only component label, or different component | Fresh Service and backing-pod TCP connections denied while allowed controls succeed |
| Different disposable namespace with identical service or worker labels | Fresh Service and backing-pod TCP connections denied; source can reach its own healthy control listener |
| Scratch database-selected pod initiating connections to an owner-controlled listener, including TCP/UDP DNS test traffic | New egress denied while a non-selected control reaches that listener; permitted inbound replies still succeed |
| Service and worker DNS resolution via existing resolver | Resolution succeeds; test UDP and TCP resolver transport where supplied; no resolver addresses logged |
| Target database local initialization/readiness/liveness | Cold start and restart succeed with deny-egress; local socket/loopback probes stay functional |
| Actual service/worker startup in an approved empty synthetic deployment | Existing migration startup succeeds; service becomes ready after migration, worker starts and performs its existing database path; no device job submitted |
| Database unavailable or existing migration startup fails | Service never becomes ready/serves a partially migrated schema; no repair or credential bypass |
| Database-pod replacement with policy retained | Fresh deny/allow matrix and actor readiness pass again |
| Scoped rollback rehearsal | Database unavailable during any policy removal/replacement; last validated policy restored before database/actors restart; matrix passes again |

Empty/cold-start and migration-failure experiments use disposable synthetic
storage only, never reset a populated target claim or alter its schema. Preserve
existing migrations unchanged. Probe images must actually support the asserted
TCP/UDP checks; an unsupported probe is UNVERIFIED, not a passing denial.
Multi-node environments require same-node and cross-node matrix coverage; a
single-node result carries only single-node evidence. Resolve any namespace,
Service or pod addresses locally in harness memory, emit no raw values, raw
responses, DSNs or credentials, and clean up temporary validation resources.

## 7. Exact implementation/validation successor and rollback

**Next movement: IMPLEMENTATION, feature implementation / medium.** Deliver
only `deploy/ui2/46-database-networkpolicy.yaml`, focused additions to
`tests/test_ui2_deployment_manifests.py`, and the smallest existing deployment
runbook/harness update needed for §§5-6. Locate and inspect those implementation
files/tests before editing; do not invent a parallel deployment or credential
path. Do not change the selectors, workloads, ports, Secrets, application code,
schema or device paths. State updates belong to that authorized successor and
use the repository queue mechanism; this contract movement updates none.

**Next validation checkpoint: VALIDATION, validation and testing / medium.**
Run the focused manifest checks, authority/reference checks, privacy gate and
diff check; expand required regression according to the successor's actual
blast radius. Then, under separate cluster authorization, execute §6 including
rollback. Escalate to high only if a security-precondition or CNI-semantic
conflict appears; stop rather than improvising a policy exception.

**Rollback.** Save the last validated policy revision. On failure, stop actors
and database; retain `ui2-db-isolation` with the same database selector and
`policyTypes`, replacing ingress with `[]` and retaining `egress: []` if needed
to remove actor permission. Restore the last validated restrictive policy
before restarting anything. If reverting the introducing commit requires policy
deletion, keep the database at zero replicas throughout; restoring the original
unisolated live deployment is not an authorized rollback. Claims, Secrets and
database contents remain intact. The rollback is rehearsed on synthetic
resources and never restores a blanket allow.

Definition of Done: exact manifest and conformance checks delivered; all
required repository gates pass; trust/enforcement preconditions established;
the applicable §6 matrix, actual migration/readiness, restart and rollback pass
in the authorized target environment; cleanup and sanitized evidence recorded;
durable successor state and handover updated. Progress is IMPLEMENTED then
AUTOMATED_VALIDATED; do not claim REAL_ENV_VALIDATED or DONE for enforcement
from repository tests. Deployment evidence does not certify vendor/device
behavior or advance unrelated B1/device gates.
