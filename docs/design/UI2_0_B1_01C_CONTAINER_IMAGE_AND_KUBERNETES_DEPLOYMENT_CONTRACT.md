# UI 2.0 — B1-1c container image and Kubernetes deployment contract

## Status

**DRAFT — 2026-09-12. NOT IMPLEMENTATION AUTHORITY.** No container image,
Containerfile, manifest, chart or script may be created on the strength of this
document while it carries this status. Applying a status is the Product Owner's
act, not an agent's; this movement wrote the contract and deliberately did not
freeze it.

This is the successor contract that
`docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §6 requires before a
UI 2.0 container image or role entry point may be implemented — the row whose
gate reads "a successor contract written against the Red Hat/OpenShift runtime
actually in use, not Docker; must resolve the `restricted-v2` arbitrary-UID
constraint, which defeats a fixed numeric UID and an `fsGroup`". It answers that
gate and no other.

`docs/design/UI2_0_B1_01B_CI_WORKFLOW_CONTRACT.md` is the precedent for the
shape: the same §6 gate, answered by a successor contract that creates no
artifact itself. This document follows it — §§3-8 decide what the artifacts must
be, §11 states how each decision is checked, and the movement that implements
them is a separate one.

The runtime this contract is written against is fixed by
`docs/design/PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md` (FROZEN):
plain Kubernetes driven with `kubectl`, one OCI image carried unchanged across
three stages, and no host container tool in any path.

## 1. Scope and authority

**In scope.** The OCI image for the UI 2.0 Java service — its base image family,
multi-stage shape, arbitrary-UID filesystem model, entry point, and prohibited
contents; the path by which that image is produced without host container
tooling; the Kubernetes manifest set that runs it; how PostgreSQL 16 is provided
in the cluster and how the required clean, empty first state is reached and
reset; the path by which the database credential reaches the service; and the
portability rules that carry the same image and the same manifests across the
three stages.

**Out of scope, and not authorized here.** Any Line-1 change; any device
contact or vendor data collection; any schema content (B1-2 owns it); the
frontend product application (B1-7/B1-9); the CI workflow (B1-1b owns it); the
Gradle build, module map, dependency-direction rules and test harness (B1-1a
§§2-5 own them and are cited, never restated). `worker` and `scheduler`
workloads are out of scope — see §3.6 and §13.

Authority above this contract: `AGENTS.md`;
`docs/design/UI2_0_BASELINE_CONTRACT.md`; the frozen `C1`-`C4` contracts;
`docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md`;
`docs/design/PO_DECISION_RECORD_2026_09_12.md` §4 (the required first state);
`docs/design/PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md` (the runtime).
Where this contract appears to conflict with any of them, they win and the
conflict is a reportable contradiction, not a local reconciliation.

**Paths this contract fixes.** The image definition is `ui2/Containerfile`. The
manifest set is `deploy/ui2/`. Two reasons, both load-bearing:

- `ui2/Containerfile` is the exact path
  `UI2_0_B1_01B_CI_WORKFLOW_CONTRACT.md` §6.4's tripwire watches. Putting the
  file anywhere else would make the tripwire silently stop covering its subject
  — the "green because it skipped its hardest step" failure mode that contract
  exists to prevent, inverted. The file goes where the tripwire looks, and the
  tripwire is replaced openly (§9), never evaded.
- The manifests are cluster artifacts, not Gradle build inputs. Keeping them
  outside `ui2/` leaves B1-1a §3's `settings.gradle.kts` no-traversal assertion
  and the `dir10` repository-half inspection untouched.

## 2. What this contract decides, in one paragraph

One image, built from the repository by a builder that runs inside the cluster,
whose writable and readable paths are owned by group 0 so that it runs correctly
under a UID the platform assigns at admission time and the image cannot know.
One manifest set, constructed so that every constraint the corporate platform's
`restricted-v2` security context constraint imposes is already satisfied on the
local cluster where nothing imposes it. One substitution — `Route` for `Ingress`
— between the local manifest set and the corporate platform. A database that
starts empty, is migrated by the existing Flyway V1-V7 at first start, and is
reset to empty by destroying its volume through the cluster API. A credential
that exists only as a cluster `Secret`, generated at creation time, never
written to a tracked file.

## 3. The image

### 3.1 Base image family

- Both stages use the **Red Hat Universal Base Image 9** family from
  `registry.access.redhat.com`, which needs no pull credential:
  the `ubi9` OpenJDK **21** *builder* image for the build stage and the `ubi9`
  OpenJDK **21** *runtime* image for the final stage. Java 21 is B1-1a §4's
  binding toolchain version; a major-version change is that contract's to make,
  not this one's.
- The family is chosen for one reason and it is not vendor preference: these
  images are already built to run as an arbitrary assigned UID with group 0
  owning their writable paths, so the model of §3.3 is the family's own model
  rather than a local retrofit. A base image whose entry point assumes it owns
  its files by UID is not admissible here (see §6.2 for the same test applied
  to the database image).
- Both base images are referenced **by digest**, not by a floating tag. A tag
  that moves changes what was proved without changing a tracked file.
- Evidence that this family is reachable and buildable on the current cluster is
  recorded in `PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md` §2 (an
  OpenJDK-21-runtime derivative was built in-cluster and exported; the registry
  answered `200`). That is provenance for the choice, not proof of any clause
  below.

### 3.2 Multi-stage shape

Two stages, in one `ui2/Containerfile`:

| Stage | Carries | Produces | May appear in the final image |
| --- | --- | --- | --- |
| `build` | JDK 21, the committed Gradle wrapper, the `ui2/` sources | the packaged service artifact | no |
| `runtime` | a JRE only | the image that ships | yes |

- **The builder stage carries the JDK, so no host JDK is required.** This is the
  property, not a convenience:
  `PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md` §2 records that `java` is
  absent from the current host and that the path must not need it. A build step
  that resolves a JDK from the host, or a Gradle toolchain auto-provisioning
  download performed outside the builder stage, fails this clause.
- Gradle is invoked only through the committed wrapper, validated by its
  committed `distributionSha256Sum` (B1-1a §4). The builder stage passes none of
  `--write-verification-metadata`, `--write-locks`, `--update-locks`,
  `--refresh-dependencies`, or a lenient/off dependency-verification flag —
  identical in intent to B1-1b §4, applied to the image build rather than to CI.
- The builder stage must carry **every** toolchain the packaging task needs and
  must resolve **none** from the host. Whether that set includes Node 22.x
  depends on whether the packaged artifact embeds frontend build output; that is
  `UNKNOWN` (U-1).
- No build stage artifact reaches the final image except the packaged service
  artifact and its declared runtime dependencies. No Gradle cache, no source
  tree, no `.git`, no lockfile, no verification metadata, no test fixture, no
  package-manager cache.

### 3.3 The arbitrary-UID and group-0 filesystem model

This is the clause the whole contract exists for. The platform assigns the
container's UID at admission time from a range allocated per namespace
(`openshift.io/sa.scc.uid-range`); that UID is not knowable when the image is
built, has no entry in the image's `/etc/passwd`, and is not the UID that owns
any file the build created. What is invariant is the **primary group: 0**.
Therefore ownership by group 0, not by any UID, is the only durable basis for
access.

Binding construction rules, each stated so it can be checked:

- **FS-1.** Every path the running process **reads** — the packaged artifact,
  its dependencies, its configuration, and every directory on the path to them —
  is readable and traversable by group 0. A path readable only by its owning UID
  is a defect, because the running UID is not that owner.
- **FS-2.** Every path the running process **writes** is owned by group 0 and
  carries the owner's permission bits on the group (`g=u`). No writable path
  relies on UID ownership.
- **FS-3.** The set of writable paths is **closed and declared in the image
  documentation**: the JVM temporary directory and the declared `HOME`. Nothing
  else in the image is writable.
- **FS-4.** Every container in the manifest set sets
  `readOnlyRootFilesystem: true`, and each declared writable path of FS-3 is
  supplied as an `emptyDir` mount. An image-layer path is never a writable path
  at runtime.
- **FS-5.** `HOME` is set explicitly in the image to a path satisfying FS-2. No
  code path may require a `/etc/passwd` entry for the running UID; a lookup of
  the running UID **will** fail and must not be load-bearing. Whether anything
  on the service classpath performs such a lookup in a way that fails is
  `UNKNOWN` (U-2), and IMG-UID-2 below is the check that settles it.
- **FS-6.** The image's final `USER` is the base family's own **numeric**
  non-root id, never a user *name*. It is a default the platform overrides, and
  it is required to be numeric for a second reason: a manifest setting
  `runAsNonRoot: true` without a `runAsUser` cannot be validated against an
  image that declares a non-numeric user, and the container will not start.
- **FS-7.** No manifest sets `runAsUser`, `runAsGroup` or `fsGroup`. They are
  omitted, not set to a value this contract believes safe. On the local cluster
  the image's own numeric `USER` then applies; on the corporate platform the
  SCC-assigned UID applies. Omission is what makes one manifest correct in both
  places.

**The testable clause for AC-2.** The model holds if and only if both checks
pass:

- **IMG-UID-1** (static, on the image). Every path of FS-3 is owned by group 0
  and is group-writable, and the packaged artifact and its dependency paths are
  group-0 readable. Runnable as a one-shot pod that runs a `find` over the
  declared paths and fails on any path that does not satisfy it.
- **IMG-UID-2** (runtime, the one that actually proves it). The pod is admitted
  and reaches readiness with `runAsUser` set to an arbitrary UID that appears in
  no image file and is not the image's own `USER`, with `runAsGroup` unset so
  the primary group is 0. The service starts, writes to each declared writable
  path, connects to the database, and passes its readiness probe. A pod that
  starts only when `runAsUser` is unset has not satisfied this contract.

IMG-UID-2 is written for a cluster that imposes nothing, which is the harder
direction: the local cluster will not assign an arbitrary UID on its own, so the
check assigns one deliberately. This is what distinguishes a model that
satisfies `restricted-v2` **by construction** from a model that merely runs on a
permissive cluster.

### 3.4 Why a fixed numeric UID and an `fsGroup` do not satisfy `restricted-v2`

Both are named in B1-1a §6 as defeated. The reasons are different and neither is
a matter of degree.

**A fixed numeric UID.**

1. In a manifest, `runAsUser: <literal>` is validated by `restricted-v2` against
   the namespace's allocated UID range. The range is assigned per namespace and
   is not knowable when the manifest is written, so any literal is a guess that
   the platform rejects at admission whenever it guesses outside the range. A
   manifest that is admitted on one namespace and rejected on the next is not a
   portable manifest.
2. In the image, `USER <literal>` is only a **default**. The assigned UID
   replaces it. Every file the build made writable to that literal alone becomes
   unwritable, and the failure appears at runtime as a permission error on a path
   that looked correct in every local test.
3. The literal's group membership does not travel either: the assigned UID is
   not a member of any group the image declares. Group 0 is the only group
   membership the platform guarantees, which is why §3.3 is built on it.

**An `fsGroup`.**

1. `restricted-v2` constrains `fsGroup` to the namespace's allocated
   supplemental-group range, so an `fsGroup` literal is the same guess as a
   `runAsUser` literal, with the same admission-time rejection.
2. More fundamentally, `fsGroup` is a **volume** mechanism. It influences the
   ownership applied to a volume when the volume is mounted. It cannot change the
   ownership or the permission bits of a path baked into an image layer — and
   the image's own read paths, which FS-1 governs, are exactly such paths. An
   `fsGroup` therefore cannot repair an image whose files are owner-readable
   only; it can only affect the mounts.
3. Its effect is volume-plugin dependent, so even where it would help it is not a
   property the image can rely on across three different storage back ends.

The conclusion the contract draws: **the image's filesystem must be correct at
build time**, and the manifest must request nothing. §3.3 is that, and §3.3 is
why one image and one manifest set can cross all three stages.

### 3.5 What the image must NOT contain

Absence is a construction rule here, and each entry is checkable on the built
image (§11):

- No Python interpreter and no Python source. B1-1a §4 forbids the `ui2/` build
  invoking Python; `DIR-8`/`DIR-10`'s image halves extend that to the image.
- No Line-1 source, no Line-1 path, no Line-1 console asset.
- No JDK compiler, no Gradle, no Gradle cache, no Node, no npm, no package
  manager cache, no source tree, no `.git`.
- No credential, no DSN, no private key, no host key, no certificate private
  material, no `.netrc`, no cloud or registry credential file.
- No SSH client and no SSH key material. The product's device transports are not
  part of this image's scope and an unused client is an unnecessary boundary.
- No vendor data, no collected evidence, no fixture data, no file under `data/`,
  `output/` or `logs/`.
- No `sudo`, no new setuid or setgid binary introduced by the build, no
  capability-bearing file added by the build.
- No shell-based entry point that interpolates a credential into a command line.
- No test code and no test dependency on the runtime classpath.

### 3.6 Entry point

B1-1a §6 lists "role entry points" as NOT BUILT and gates them on "the
deployment successor above", which is this document. This contract decides only
what the first deployment needs and defers the rest:

- **EP-1.** One image, one entry point. The workload role is selected by an
  argument, not by a separate image, a separate tag, or an environment variable
  interpreted by a shell.
- **EP-2.** The role this contract authorizes is the `service` role, and it is
  the only workload in §5's manifest set. `worker` and `scheduler` are
  **deferred**: B1-1a §2 records that those modules carry one Java source each,
  and the required first state (§6.3) needs neither. Adding their workloads is a
  later movement under its own contract clause, not an extension of this one.
- **EP-3.** The entry point is the JVM directly with the packaged artifact. No
  shell wrapper is required, and if one is introduced it must not be the place a
  credential is assembled (§7).
- **EP-4.** The process must terminate on `SIGTERM` within the Deployment's
  termination grace period without leaving the database mid-migration.

## 4. The build path

Stated as properties the path must have, so that a change of cluster does not
invalidate the contract. No clause below names a build tool's command line,
because the tool is a property of the cluster and the contract is not.

- **BP-1.** The image is produced by a builder that runs **inside the cluster**,
  taking the repository as its build context. No host build daemon, no host
  container tool, no host JDK and no host Node participates at any stage. The
  Product Owner withdrew host container tooling
  (`PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md` §1); this clause is that
  withdrawal expressed as a construction rule.
- **BP-2.** No tracked file may invoke a host container tool, and no acceptance
  check of this contract may require one. Proved by absence (§11 check 3), not
  by convention.
- **BP-3.** The build is driven through the cluster API with the client the
  Product Owner fixed (`kubectl`), or by the cluster's own image-build facility
  reached through it. A cluster client is not a host container tool; the
  distinction this contract holds is between *driving a cluster* and *running a
  container engine on the workstation*.
- **BP-4.** The produced image is addressable by the Deployment's `image` field
  from inside the cluster without a push to an external registry at stage 1. At
  stages 2 and 3 the same image may instead be produced in CI and pulled from a
  registry. That is a change of **where the build runs**, never of **what is
  built** — the `Containerfile`, its base digests and its resulting filesystem
  are identical.
- **BP-5.** The build is repeatable from the repository alone: pinned base
  digests (§3.1), the committed Gradle wrapper, the committed version catalog,
  the eleven committed lockfiles and the committed verification metadata (B1-1a
  §4). A build that must reach outside those inputs to succeed is not this build.
- **BP-6.** The builder needs egress to the dependency repositories the Gradle
  and frontend resolution require. Whether that egress exists from inside the
  cluster's builder is `UNKNOWN` (U-3): the recorded evidence covers container
  registry reachability only.
- **BP-7.** The image is tagged by an immutable identifier derived from the
  commit it was built from. No manifest references `:latest`, and no two
  different filesystems ever share a tag.

## 5. The manifest set

Seven kinds, all under `deploy/ui2/`, each named below with what it must and
must not do. The OpenShift-safety rules are stated once as `OS-1`-`OS-10` and
applied per kind, because they are one construction discipline rather than ten
per-file conventions.

### 5.1 OpenShift-safety construction rules

| Rule | Requirement | How it is checked |
| --- | --- | --- |
| `OS-1` | No container runs as root: every container sets `runAsNonRoot: true` | grep over `deploy/ui2/`; a pod admitted and running |
| `OS-2` | No fixed UID or GID: no manifest sets `runAsUser`, `runAsGroup` or `fsGroup` (FS-7) | grep matches nothing |
| `OS-3` | No `hostPath` volume | grep matches nothing |
| `OS-4` | No `privileged: true`, and `allowPrivilegeEscalation: false` on every container | grep for the first matches nothing; the second is present on every container |
| `OS-5` | No host networking: no `hostNetwork`, `hostPID`, `hostIPC`, `hostPort` | grep matches nothing |
| `OS-6` | Resource **requests and limits** for cpu and memory on every container | every container has all four |
| `OS-7` | `capabilities.drop: ["ALL"]` and `seccompProfile.type: RuntimeDefault` on every container | present on every container |
| `OS-8` | `readOnlyRootFilesystem: true` on every container, with declared writable paths as `emptyDir` (FS-4) | present on every container |
| `OS-9` | No `NodePort` and no `LoadBalancer` Service; external reach is through the ingress object only | grep matches nothing |
| `OS-10` | No image referenced by a moving tag; every `image:` is a digest or an immutable commit-derived tag (BP-7) | no `:latest`, no bare tag |

A manifest that satisfies `OS-1`-`OS-10` is admissible under `restricted-v2`
without amendment. That is the whole portability argument, and it is why these
rules are applied on a local cluster that imposes none of them.

### 5.2 The kinds

| Kind | Purpose | Binding clauses beyond `OS-1`-`OS-10` |
| --- | --- | --- |
| `Namespace` | the namespace the set is applied into | carries no quota or limit-range assumption; its applicability on the corporate platform is `UNKNOWN` (U-4) |
| `ConfigMap` | non-secret service configuration: database host, port, database name, log level, the ingress host name | **never** a credential, a DSN containing a credential, a device address, a device name or a serial |
| `Secret` | the database credential, as `Opaque` | not the source of the live value — §7 governs it entirely |
| `PersistentVolumeClaim` | the database data directory | `storageClassName` is **omitted** so the cluster default applies; no `hostPath`; no `volumeName` pinning a pre-created volume |
| `Deployment` | the `service` role workload (EP-2) | `replicas: 1` at stages 1 and 2 (§6.4); readiness gated on completed migration; `SIGTERM`-clean (EP-4) |
| `Service` | in-cluster reach to the workload | `ClusterIP` only (`OS-9`); one for the service, one for the database |
| `Ingress` | external reach on the local cluster and on the Ingress-based stage | the host name is a per-stage **value**, not a manifest difference (§8); replaced by `Route` on the corporate platform, and by nothing else (§8) |

The database workload's own kind is decided in §6.

## 6. The database, the migrations, and the clean empty first state

### 6.1 PostgreSQL 16 in the cluster

- **DB-1.** PostgreSQL **16** is the version, in the cluster, as its own
  workload with its own `Service`, its own `Secret` and the `PersistentVolumeClaim`
  of §5.2. B1-1a §5 clause 5 makes another major version not evidence for that
  contract, so 16 is not a range.
- **DB-2.** The database workload is a `StatefulSet` with one replica, not a
  `Deployment`: a stable identity and a single, non-shared claim on the data
  directory is what keeps two pods from opening the same data directory during a
  rollout. This is the only kind outside §5.2's list, and it is named here
  rather than there because it belongs to the database, not to the service.
- **DB-3.** The database image must satisfy the **same** arbitrary-UID model as
  §3.3 — writable data directory owned by group 0 with `g=u`, no assumption that
  a fixed UID owns it, and no ownership change performed at start-up. An image
  whose entry point changes ownership of its data directory, or requires a
  specific UID to own it, is not admissible, because it cannot be admitted under
  `restricted-v2` at stage 3 without a manifest exception — and a manifest
  exception is the thing this contract exists to avoid.
- **DB-4.** The concrete image is `UNKNOWN` (U-5). The widely used
  community PostgreSQL image is expected to fail DB-3 for the reason DB-3 names;
  the Red Hat and `sclorg` PostgreSQL 16 families are expected to satisfy it.
  Neither expectation is evidence. The check that settles it is a pod admitted
  with `runAsUser` set to an arbitrary UID and `runAsGroup` unset, initializing
  an empty data directory and accepting a connection (§11 check 9).

### 6.2 How the migrations run

- **MIG-1.** The migrations are the existing Flyway migrations **V1 through V7**
  already in the repository at `service/src/main/resources/db/migration/`
  (B1-1a §2), applied by `FlywayMigrationRunner` in `persistence`. This contract
  adds no migration, changes no migration, and renames none. Schema content is
  B1-2's.
- **MIG-2.** They run with the `ui2_migrate` role's DSN; the service's own
  runtime connections use `ui2_app`. This is B1-1a §5 clause 3's role split,
  carried from the test harness into the deployment unchanged. Two roles, two
  credentials, one Secret path (§7).
- **MIG-3.** Migration runs at service start-up, before the workload reports
  ready. The readiness probe must not pass until migration has completed, so a
  connection-level probe alone is insufficient — a TCP probe would pass while
  the schema was still being created. The concrete readiness endpoint is
  `UNKNOWN` (U-6).
- **MIG-4.** A failed migration fails the start-up. The service must not start
  against a partially migrated schema, must not skip a migration, and must not
  repair one. `AGENTS.md` "UNKNOWN / fail-closed law" is the reason: a service
  that starts anyway converts a known-bad state into an unknown one.

### 6.3 The clean, empty first state

`PO_DECISION_RECORD_2026_09_12.md` §4 requires a clean, empty database with the
UI and its menus visible and nothing pre-populated. This contract's part of that
is the database half:

- **FIRST-1.** First start means an **empty data directory** on a newly bound
  claim. The database workload initializes an empty cluster, creates the
  `ui2` database and the `ui2_migrate` and `ui2_app` roles, and nothing else.
- **FIRST-2.** Flyway then applies V1-V7 and stops. **Nothing is seeded**: no
  demo device, no demo user, no sample evidence, no fixture row. A migration
  that inserts product data would breach this clause and is B1-2's to prevent at
  the schema level; this contract forbids the deployment adding any.
- **FIRST-3.** The only rows that exist after a first start are those the
  migrations themselves create as schema metadata (the Flyway history table and
  anything a migration creates as structure). An acceptance check states this as
  an observable count, not as an intention (§11 check 10).

### 6.4 Reset to empty

- **RESET-1.** The reset is performed **entirely through the cluster API**:
  the database workload is scaled to zero, its `PersistentVolumeClaim` is
  deleted, the claim is re-created, and the workload is scaled back up. The next
  start is a first start by FIRST-1, and Flyway re-applies V1-V7 from nothing.
- **RESET-2.** No host database client, no host container tool, no host
  filesystem access to the volume, and no interactive session inside a pod
  running ad-hoc SQL is part of the reset. Destroying the claim is the reset; a
  `DROP`/`TRUNCATE` script is not, because it leaves the database's own state
  (roles, extensions, sequences, history table) in a condition the first start
  never produced.
- **RESET-3.** The service workload is restarted after a reset, because it holds
  connections to a database that no longer exists and its migration state is
  start-up state. With `replicas: 1` (§5.2) that restart is also what keeps two
  instances from migrating the same empty database concurrently; at more than
  one replica a concurrent-migration guard is required and is deferred (§13).

## 7. The secret path

The requirement is absolute: **no credential value in any tracked file**, at any
point, in any encoding.

- **SEC-1.** The repository tracks the Secret's **contract**, not its content:
  its name, its `Opaque` type, and its key names (the migrate role's user and
  password, and the application role's user and password). Those names are
  stated in this document and in the `ConfigMap`/`Deployment` references that
  consume them.
- **SEC-2.** If a Secret manifest file is tracked at all, it carries **no**
  `data` and no `stringData` — keys only, or nothing. It is never the source of
  the live value. A tracked file containing a base64-encoded value is a tracked
  credential; the encoding is not a protection and the privacy gate treats it as
  a finding.
- **SEC-3.** The live Secret is created **in the cluster**, from a value
  generated at creation time. The generated value is never written to a tracked
  file, never echoed to a terminal, never placed in a shell command line as a
  literal (a command line is visible to the process list and to shell history),
  never written to a build log, and never included in a session report, a
  handover, a commit message or a PR description.
- **SEC-4.** The service receives the credential **only** by reference: the
  container's environment entries for the user and password are
  `secretKeyRef`s, and the non-secret half of the connection — host, port,
  database name — comes from the `ConfigMap`. A complete DSN containing the
  credential is assembled **in the process**, never in a manifest, a ConfigMap,
  an image layer, an argument, or a log line.
- **SEC-5.** No log line, exception message, probe output, failure message,
  artefact or metric may contain the credential or the assembled DSN. This is
  B1-1a §5 clause 4 carried from the test fixtures into the deployment.
- **SEC-6.** Rotation is a Secret update followed by a pod restart. No tracked
  file changes when a credential rotates — which is the operational test of
  whether SEC-1 through SEC-4 were actually implemented.
- **SEC-7.** The database workload's own credential follows the same path.
  There is no second mechanism and no "development-only" exception; a
  development-only exception is how a credential reaches a tracked file.

## 8. Portability across the three stages

`PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md` §1 fixes one image and one
manifest set across three runtimes. This section states what that means
precisely enough to be checkable.

| Stage | Runtime | Where the image comes from | Manifest difference |
| --- | --- | --- | --- |
| 1 — this machine | local single-node Kubernetes | built inside the cluster from the repository (BP-1) | none |
| 2 — Ubuntu server, later | Kubernetes | built in CI or in-cluster; pulled from a registry (BP-4) | none |
| 3 — corporate platform | OpenShift | the same image, pulled from a registry | `Route` replaces `Ingress` |

- **PORT-1.** The single documented difference between the local manifest set
  and the corporate platform is that a `Route` object replaces the `Ingress`
  object. **Nothing else differs**: not the image, not the `Containerfile`, not
  the security context, not the resource declarations, not the volume model, not
  the Secret path, not the number of objects otherwise.
- **PORT-2.** Per-stage **values** are not manifest differences, and the
  distinction is what makes PORT-1 true rather than aspirational. The values
  that may differ per stage are exactly: the ingress or route host name, the
  storage size of the claim, the resource request and limit magnitudes, the
  image reference, and the Secret's content. Each is a value in a `ConfigMap`,
  a Secret, or a single field — never a structural change, never a conditional
  manifest, never a second copy of a file.
- **PORT-3.** No manifest may carry a platform-conditional construct: no
  templating that emits different object kinds per platform, no annotation that
  only one platform honours and that changes behaviour where it is honoured, no
  commented-out alternative block. The `Route` is a separate file applied
  instead of the `Ingress` file, and that substitution is the one recorded in
  PORT-1.
- **PORT-4.** The reason the local cluster is held to `restricted-v2`'s
  constraints, though it imposes none of them, is stated here so it is not
  optimized away later: a constraint that is only satisfied where it is
  enforced is discovered at stage 3, on the platform where a failed admission
  is least recoverable. §3.3, §5.1 and DB-3 are enforced at stage 1 for that
  reason.

## 9. Effect on B1-1b's deferred register and tripwire — on freeze only

`UI2_0_B1_01B_CI_WORKFLOW_CONTRACT.md` §6.4 states that when the image contract
freezes, it "amends or replaces this tripwire as part of its own freeze —
removing it is that contract's act, not a movement's convenience". This section
states what that act would be. **This movement makes no edit to B1-1b or to
B1-1a**, and while this document is DRAFT the tripwire stands exactly as
written, so the appearance of `ui2/Containerfile` today correctly fails the
B1-1b job.

On freeze, and only on freeze, the following would take effect:

1. The §6.4 tripwire's existence check on `ui2/Containerfile` is replaced by a
   **conformance** check: the file must exist, must be the two-stage shape of
   §3.2, and must satisfy the image checks of §11. The tripwire is not deleted;
   its subject moves from "this must not exist" to "this must be correct". The
   tripwire's other half — that no image-build invocation appears in the
   workflow — is decided by whether CI builds the image, which is B1-1b's
   question and not answered here.
2. Four rows of the §6.3 deferred register become answerable and must be moved
   out of it by the movement that implements them, not by this contract:
   container image build, image filesystem inspection, the `dir8` image half and
   the `dir10` image half. §3.5 is what the `dir8`/`dir10` image halves are
   checked against.
3. The "Arbitrary-UID / `restricted-v2` conformance" row stays deferred for the
   corporate platform and becomes **partially** provable at stage 1 by
   IMG-UID-2. A pass of IMG-UID-2 on the local cluster is evidence that the
   image's model is correct by construction; it is **not** evidence about the
   corporate platform's SCC, which is a real-environment matter (§13).

## 10. `UNKNOWN` register

Every clause this contract could not decide on available evidence, with what
would settle it. `AGENTS.md` requires explicit `UNKNOWN` over invented
certainty; a plausible value here would be a guess that looks like a decision.

| Id | `UNKNOWN` | What settles it |
| --- | --- | --- |
| U-1 | Whether the builder stage must carry Node 22.x — i.e. whether the packaged service artifact embeds frontend build output | the declared inputs of the packaging task in `ui2/`; if it consumes frontend output, the builder carries Node, otherwise it must not |
| U-2 | Whether anything on the service classpath performs a passwd lookup of the running UID in a way that fails when the UID has no entry | IMG-UID-2 with an arbitrary UID absent from the image's own files |
| U-3 | Whether the in-cluster builder has egress to the dependency repositories Gradle and the frontend resolution need | one observed dependency-resolution step completing inside the builder; the recorded evidence covers container registries only |
| U-4 | Whether the `Namespace` manifest may be applied on the corporate platform, or whether the project is pre-provisioned by the platform team | the corporate platform's project-request policy. Recorded as an open **precondition**, not as a second manifest difference — PORT-1 stands |
| U-5 | The concrete PostgreSQL 16 image that satisfies DB-3 | a pod admitted with an arbitrary `runAsUser` and `runAsGroup` unset, initializing an empty data directory and accepting a connection |
| U-6 | The readiness endpoint that is unhealthy until migration completes (MIG-3) | whether the `service` module exposes a health endpoint, and whether its readiness state is gated on migration completion |
| U-7 | Whether the corporate platform supplies the database in-cluster by this manifest set or as an external service | a corporate platform directive. Either works without a service-manifest change because SEC-4 makes the connection a Secret-and-DNS matter; this contract does not choose for the Product Owner |
| U-8 | The resource request and limit magnitudes | one observed run under representative load; PORT-2 makes them per-stage values, so the structure does not wait on the numbers |

## 11. Acceptance checks

Runnable from the repository root. None names an absolute filesystem path, a
developer account, or a Python interpreter for the Java build — the shape B1-1a
§10 item 4 records as a predecessor defect. Checks 1-6 are repository checks;
checks 7-14 require the cluster and are the ones that actually prove the model.

1. `ui2/Containerfile` exists, declares exactly two stages, and its final stage
   is the runtime base image family of §3.1 referenced by digest:
   `grep -c -E '^FROM ' ui2/Containerfile` reports `2`, and
   `grep -n -E '^FROM .*@sha256:' ui2/Containerfile` matches both.
2. `grep -n -E 'USER +[A-Za-z]' ui2/Containerfile` matches nothing: the final
   `USER` is numeric (FS-6).
3. `grep -R -n -E 'docker |podman |buildah|nerdctl|/var/run/docker.sock' ui2/Containerfile deploy/ui2 --include='*'`
   matches nothing: no host container tool appears in any tracked file of the
   path (BP-2).
4. `grep -R -n -E 'runAsUser|runAsGroup|fsGroup|hostPath|hostNetwork|hostPID|hostIPC|hostPort|privileged: true|NodePort|LoadBalancer|:latest' deploy/ui2`
   matches nothing (`OS-2`, `OS-3`, `OS-4`, `OS-5`, `OS-9`, `OS-10`).
5. Every container in `deploy/ui2` declares `runAsNonRoot: true`,
   `allowPrivilegeEscalation: false`, `readOnlyRootFilesystem: true`,
   `capabilities.drop: ["ALL"]`, `seccompProfile.type: RuntimeDefault`, and all
   four of cpu/memory request and limit (`OS-1`, `OS-4`, `OS-6`, `OS-7`,
   `OS-8`).
6. The repository privacy gate reports zero new findings against `origin/main`,
   invoked through the repository's own documented command; and no tracked file
   under `deploy/ui2` contains a `data:` or `stringData:` key (SEC-2).
7. **IMG-UID-1.** A one-shot pod from the built image reports, for each declared
   writable path of FS-3, group-0 ownership and group-write permission, and
   reports group-0 readability for the packaged artifact and its dependency
   paths. The pod exits non-zero on any path that fails.
8. **IMG-UID-2.** The service pod is admitted and reaches readiness with
   `runAsUser` set to an arbitrary UID that appears in no image file and differs
   from the image's own `USER`, and with `runAsGroup` unset. It writes to each
   declared writable path, connects to the database, and passes its readiness
   probe. The same manifest with the security context omitted entirely also
   reaches readiness (FS-7).
9. **DB-3.** The database pod is admitted under the same arbitrary-UID
   condition, initializes an empty data directory, and accepts a connection
   (settles U-5).
10. **First state.** After a first start, the `ui2` database contains the
    schema of V1-V7 and **no product rows**: Flyway reports seven applied
    migrations, and a row count over every product table of the schema is zero
    (FIRST-2, FIRST-3).
11. **Reset.** After the §6.4 reset performed only through the cluster API,
    check 10 passes again unchanged, and no host database client and no host
    container tool was used (RESET-1, RESET-2).
12. **Migration gating.** With the database workload scaled to zero, the service
    pod does **not** report ready and does not serve; it reports a start-up
    failure rather than starting against no schema (MIG-4). With a migration
    deliberately made to fail, the pod does not become ready.
13. **Secret path.** Rotating the Secret's value and restarting the pod changes
    no tracked file (`git status --porcelain` is clean), the service reconnects,
    and `grep -R -n -E 'secretKeyRef' deploy/ui2` shows the credential reaching
    the container only by reference (SEC-4, SEC-6). No pod log line, probe
    output or event contains the credential or an assembled DSN (SEC-5).
14. **Image contents.** The built image contains no Python interpreter, no
    Gradle or Node, no source tree, no `.git`, no SSH client or key material, no
    credential file, and no new setuid or setgid binary (§3.5), reported as a
    per-entry pass over the image filesystem.
15. `git diff --check origin/main...HEAD` is clean.

## 12. Cross-references

- `docs/design/UI2_0_B1_01A_PLATFORM_SKELETON_CONTRACT.md` §§2, 4, 5, 6, 7, 9,
  10 — the gate this contract answers, and the build, harness and module facts
  it cites rather than restates.
- `docs/design/UI2_0_B1_01B_CI_WORKFLOW_CONTRACT.md` §§4, 5, 6.3, 6.4, 10 — the
  precedent for the shape, the deferred register, and the tripwire §9 addresses.
- `docs/design/PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md` §§1, 2, 3, 4
  — the runtime, the withdrawal of host container tooling, and the
  OpenShift-safety construction rule.
- `docs/design/PO_DECISION_RECORD_2026_09_12.md` §4 — the required first state.
- `docs/design/UI2_0_BASELINE_CONTRACT.md` §§1, 2, 4.
- `docs/design/UI2_0_C1_PLATFORM_SCHEMA_CONTRACT.md` §§2, 3.5, 6.
- `docs/design/UI2_0_B1_02_SCHEMA_V1_CONTRACT.md` — schema content of V1-V7.
- `PRIVACY_AND_DATA_HANDLING.md`, "UI 2.0 database".
- `docs/design/UI2_0_B1_01_SKELETON_CI_DOCKER_CONTRACT.md` §7 — SUPERSEDED,
  never frozen, historical only: cited for provenance of the distroless image
  sketch this contract replaces, and never as authority for any clause here.

## 13. What this contract defers, and what it does not prove

**Deferred, with the reason each cannot be decided here:**

- `worker` and `scheduler` workloads (EP-2). The required first state needs
  neither, and both modules carry one Java source each.
- More than one service replica, and with it the concurrent-migration guard
  (RESET-3, MIG-3). A migration performed by a starting pod is safe at one
  replica and unproven above it; the shape a later movement needs is a
  migration step that completes before any serving pod starts.
- Backup, restore and retention for the database volume. The reset of §6.4
  destroys data by design and is not a backup story.
- Horizontal autoscaling, network policy, pod disruption budgets, quotas and
  limit ranges. Each is a platform-conditional object and PORT-3 forbids
  introducing one before the platform requires it.
- Observability: metrics, tracing, log shipping. SEC-5 constrains what may
  appear in a log; nothing here decides where logs go.
- Any SBOM claim. B1-1b §6.3 records the generator as `UNKNOWN`; this contract
  adds no SBOM and claims none.
- Any image or workload for Line 1. Out of scope by §1.
- Whether CI builds the image (§9 item 1). That is B1-1b's question.

**What freezing this contract would not prove:**

- It would prove that the image and manifest model described is *constructible*
  from artifacts that exist today. It would prove nothing about any artifact,
  because this movement creates none — there is no `ui2/Containerfile` and no
  `deploy/ui2/` at the time of writing, and every check in §11 is therefore
  unrun.
- A pass of IMG-UID-1 and IMG-UID-2 on the local cluster proves the image's
  filesystem model is correct by construction. It is **not** evidence about the
  corporate platform: the SCC that would admit the pod there has not been
  observed, and `AGENTS.md` "Evidence laws" makes a local observation no
  substitute for the environment that matters.
- Nothing here is real-environment evidence for UI 2.0. B1-1a §9 stands:
  `ui2/` has no real-environment evidence of any kind and `REAL_ENV_VALIDATED`
  is unreachable for every B1 row until device contact is separately
  authorized. A green deployment is not device evidence.
- The eight `UNKNOWN`s of §10 are open. None of them blocks the structure this
  contract decides, and none of them may be closed by assertion.
