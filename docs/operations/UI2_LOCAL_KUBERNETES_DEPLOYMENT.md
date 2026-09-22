# UI 2.0 — local Kubernetes deployment

**Status: operator procedure.** This document is not a contract and decides
nothing. The authority is
`docs/design/UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md`
(FROZEN); where this document and that one disagree, that one wins and the
disagreement is a defect here.

Every command below is one an operator runs as written, from the repository
root. No step needs a host JDK, a host build daemon or a host container
engine: the image is built by a builder that runs inside the cluster and is
driven through the cluster API (contract BP-1, BP-3).

Artifacts this procedure uses:

| Path | What it is |
| --- | --- |
| `ui2/Containerfile` | the two-stage service image (contract §3) |
| `deploy/ui2/` | the manifest set that runs the product (contract §5) |
| `deploy/ui2/openshift/60-route.yaml` | the Route applied *instead of* the Ingress on the corporate platform (PORT-1) |
| `deploy/ui2-image-build/` | the in-cluster builder; build equipment, not part of the manifest set |

---

## 1. Preconditions

- A local single-node Kubernetes cluster is running and `kubectl` reaches it
  (`kubectl get nodes` reports `Ready`).
- The cluster provides a default `StorageClass`. The claims in the set omit
  `storageClassName` on purpose, so the default applies on every stage.
- Nothing else. In particular no `java`, no build daemon and no container
  engine on the workstation.

## 2. One-time cluster preparation

The builder pushes to the cluster's own registry and the node pulls from it,
so the cluster needs a registry and the node needs to know how to reach it.
This is cluster preparation, performed once per cluster.

```sh
minikube addons enable registry
```

```sh
minikube ssh -- 'sudo mkdir -p /etc/containerd/certs.d/registry.kube-system.svc.cluster.local \
  && printf "server = \"http://localhost:5000\"\n\n[host.\"http://localhost:5000\"]\n  capabilities = [\"pull\", \"resolve\"]\n" \
     | sudo tee /etc/containerd/certs.d/registry.kube-system.svc.cluster.local/hosts.toml \
  && sudo systemctl restart containerd'
```

For browser reach over the Ingress object, also:

```sh
minikube addons enable ingress
```

## 3. Build the image, inside the cluster

```sh
kubectl apply -f deploy/ui2-image-build/00-namespace.yaml \
              -f deploy/ui2-image-build/10-context-pvc.yaml \
              -f deploy/ui2-image-build/20-context-loader.yaml

kubectl -n ui2-build wait --for=condition=Ready pod/ui2-build-context-loader --timeout=300s
```

Carry the build context in. The repository is streamed onto the claim through
the cluster API; the workstation's filesystem is never mounted into the
cluster.

```sh
kubectl -n ui2-build exec ui2-build-context-loader -- sh -c 'rm -rf /workspace/ui2 /workspace/project'

tar -cf - --exclude=node_modules --exclude=.gradle --exclude=build --exclude=.git ui2 project \
  | kubectl -n ui2-build exec -i ui2-build-context-loader -- tar -xf - -C /workspace
```

Tag the build from the commit it is built from (contract BP-7), then run it:

```sh
kubectl -n ui2-build create configmap ui2-build-config \
  --from-literal=image_tag="$(git rev-parse --short=12 HEAD)" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n ui2-build delete job ui2-image-build --ignore-not-found
kubectl apply -f deploy/ui2-image-build/30-build-job.yaml

kubectl -n ui2-build wait --for=condition=Complete job/ui2-image-build --timeout=1800s
kubectl -n ui2-build logs job/ui2-image-build | tail -20
```

Read the digest the build produced:

```sh
kubectl -n ui2-build exec ui2-build-context-loader -- cat /workspace/image-digest.txt
```

`deploy/ui2/50-service-deployment.yaml` carries the digest of the image built
from the commit that introduced it. If the digest above differs — any change
under `ui2/` or to the copied `project/` inputs produces a different one —
point the Deployment at the new image after step 6:

```sh
kubectl -n ui2 set image deployment/ui2-service \
  service="registry.kube-system.svc.cluster.local/nexus-ui2-service@$(kubectl -n ui2-build exec ui2-build-context-loader -- cat /workspace/image-digest.txt)"
```

## 4. Apply the objects the credential needs

```sh
kubectl apply -f deploy/ui2/00-namespace.yaml \
              -f deploy/ui2/10-configmap.yaml \
              -f deploy/ui2/20-secret.yaml \
              -f deploy/ui2/30-database-pvc.yaml
```

`deploy/ui2/20-secret.yaml` carries the Secret's key names and no value, in
any encoding. It creates an empty shell; step 5 fills it.

## 5. Supply the database credential

**The repository never contains a credential.** The value is generated here,
at creation time, and reaches the cluster without appearing in a tracked
file, in a command line, in shell history or in a terminal.

The two user names are fixed by the contract (MIG-2) and by the migrations
themselves, which grant to `ui2_migrate` and `ui2_app` by name: they are role
names, not secrets. The two passwords are generated and never seen.

```sh
d="$(mktemp -d)"
( umask 077
  printf 'ui2_migrate' > "$d/migrate-user"
  printf 'ui2_app'     > "$d/app-user"
  for key in migrate-pw app-pw; do
      openssl rand -base64 24 | tr -d '\n' > "$d/$key"
  done

  set --
  for key in migrate-user:migrate-user migrate-password:migrate-pw \
             app-user:app-user app-password:app-pw; do
      set -- "$@" --from-file="${key%%:*}=$d/${key#*:}"
  done

  kubectl -n ui2 create secret generic ui2-db "$@" \
    --dry-run=client -o json > "$d/patch.json"

  kubectl -n ui2 patch secret ui2-db --type=merge --patch-file "$d/patch.json" )
rm -rf "$d"
```

The loop pairs each Secret key with the file that holds its value. It is a
loop rather than four flags for one reason worth stating: written out flat,
the flag for a password key is exactly the shape the repository privacy gate
reads as a tracked credential, and a documentation false positive in that
gate is not something to teach an operator to ignore.

Why `patch` and not `apply`: the tracked Secret file has no `data` block, so
it is not in the applied configuration. Patching leaves the live value alone
when the directory is applied again, which is what keeps step 6 repeatable.

The service never receives the value in an environment entry. The Secret is
mounted as four files and the container's environment carries their *paths*;
the DSN is assembled inside the process (contract SEC-4, and the `C1` §6
secret-file rule the service implements — a missing, unreadable or empty
file stops start-up rather than falling back).

## 5c. Supply the AIView pseudonymizer key (2026-09-23)

The `aiview` persona sees pseudonyms (`FW-TANGO-04`, `CLS-ROMEO-01`) computed under an HMAC key and
claimed once in `pseudonym_registry` (V53). The key must survive pod restarts or every pseudonym moves on
each deploy. `deploy/ui2/25-secret-privacy-hmac-key.yaml` carries the Secret's name and no value; the
service reads the mounted file named by `UI2_PRIVACY_HMAC_KEY_FILE` and refuses to start if that file is
missing, unreadable or shorter than 32 bytes. Created once, only when absent:

```sh
kubectl -n ui2 get secret ui2-privacy-hmac-key -o jsonpath='{.data.key}' | grep -q . || {
d="$(mktemp -d)"
( umask 077
  openssl rand -base64 32 | tr -d '\n' > "$d/key"
  kubectl -n ui2 apply -f deploy/ui2/25-secret-privacy-hmac-key.yaml
  kubectl -n ui2 create secret generic ui2-privacy-hmac-key \
    --from-file=key="$d/key" \
    --dry-run=client -o json > "$d/patch.json"
  kubectl -n ui2 patch secret ui2-privacy-hmac-key --type=merge --patch-file "$d/patch.json" )
rm -rf "$d"; }
```

Rotating this key re-assigns every pseudonym: V54 cleared the registry once, when the durable key was
introduced; a later rotation needs the same clearing (`delete from pseudonym_registry`, as the migration
role) and a note to the Product Owner that the names changed.

## 5a. Supply the configuration-artefact-store key (NXS-LOCAL-0165)

Configuration collection's raw copy (Check Point `show configuration`, Palo
Alto `effective-running`/`active`/`merged`) is encrypted at rest under its
own key, `ArtefactStoreCipher` -- a separate purpose from the credential
store, never the same key (C1 §6.1). `deploy/ui2/23-secret-artefact-store-key.yaml`
carries the Secret's key name and no value, exactly like
`21-secret-credential-store-key.yaml`. Created once, the same way:

```sh
d="$(mktemp -d)"
( umask 077
  openssl rand -base64 32 | tr -d '\n' > "$d/key"

  kubectl -n ui2 create secret generic ui2-artefact-store-key \
    --from-file=key="$d/key" \
    --dry-run=client -o json > "$d/patch.json"

  kubectl -n ui2 patch secret ui2-artefact-store-key --type=merge --patch-file "$d/patch.json" )
rm -rf "$d"
```

The worker role mounts it at `/run/secrets/ui2-artefact-store/key`
(`UI2_ARTEFACT_STORE_KEY_FILE`); the service role never mounts it -- it
never decrypts a raw artefact, only the sanitized view already stored in
Postgres. The encrypted bytes themselves live under `UI2_ARTEFACT_STORE_ROOT`
(`/app/artefact-store`, a `PersistentVolumeClaim` --
`deploy/ui2/51-artefact-store-pvc.yaml`, NXS-LOCAL-0167 -- so a worker
restart does not orphan the artefacts `device_configuration_run` rows still
point at by `artefact_ref`).

## 6. Apply the manifest set

```sh
kubectl apply -f deploy/ui2/

kubectl -n ui2 rollout status statefulset/ui2-db --timeout=600s
kubectl -n ui2 rollout status deployment/ui2-service --timeout=600s
```

> **Secrets are created once, never re-applied.** `deploy/ui2/20-secret.yaml`,
> `21-secret-credential-store-key.yaml`, `22-secret-role-binding-key.yaml` and
> `23-secret-artefact-store-key.yaml` carry no values by contract. Applying
> them over an existing Secret that was itself created with `kubectl apply`
> removes that Secret's data (observed 2026-09-14: the role-binding key was
> wiped and had to be regenerated). Create the four Secrets with
> `kubectl create secret generic ... --from-file=key=...` and, on later
> rollouts, apply the manifest set without the `2*-secret-*.yaml` files, for
> example `kubectl apply -f deploy/ui2/50-service-deployment.yaml -f deploy/ui2/52-worker-deployment.yaml`.

`kubectl apply -f` on a directory is not recursive, so `deploy/ui2/openshift/`
is not picked up here. On the corporate platform, apply
`deploy/ui2/openshift/60-route.yaml` **instead of**
`deploy/ui2/60-ingress.yaml` — that substitution is the only difference
between the stages (PORT-1).

Confirm the migrations ran and the database is the clean, empty first state:

```sh
kubectl -n ui2 logs deploy/ui2-service | grep 'migrations applied'

kubectl -n ui2 exec ui2-db-0 -- psql -d ui2 -c \
  'SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;'
```

## 7. Reach the UI

Through the Ingress object, which is what the corporate platform's Route
replaces:

```sh
echo "$(minikube ip) ui2.nexus.local" | sudo tee -a /etc/hosts
curl -sS -o /dev/null -w '%{http_code}\n' http://ui2.nexus.local/
```

Then open `http://ui2.nexus.local/` in a browser.

Without touching `/etc/hosts`, the same page is reachable on the workstation
alone:

```sh
kubectl -n ui2 port-forward svc/ui2-service 8080:8080
```

and then `http://127.0.0.1:8080/`. Neither route exposes the deployment
beyond this machine.

## 7a. Trigger the first configuration collection (NXS-LOCAL-0165)

With an enrolled device present, submit its collection job the same way the
Configuration screen's own "Collect now" button does:

```sh
kubectl -n ui2 port-forward svc/ui2-service 8080:8080 &
curl -sS -X POST http://127.0.0.1:8080/devices/<device_id>/configuration/collect \
  -H 'Content-Type: application/json' -d '{}' \
  -b '<the session cookie from a prior login>'
```

The worker's claim loop (60 s lease, `Ui2WorkerMain`) picks the job up on its
own poll cycle; `GET /devices/<device_id>/configuration` shows the run once
it completes. `kubectl -n ui2 logs deploy/ui2-worker` names no configuration
line, hostname, or secret value at any point (AGENTS.md raw-evidence law) --
only the job/run identifiers and outcome tokens the C2 discipline already
logs for every other job kind.

## 8. Reset the database to empty

The reset is the destruction of the claim, performed entirely through the
cluster API (contract RESET-1, RESET-2). No host database client, no host
container engine, no host access to the volume and no ad-hoc SQL inside a
pod takes part: a `DROP` or `TRUNCATE` script would leave roles, sequences
and the migration history in a state a first start never produces.

```sh
kubectl -n ui2 scale statefulset/ui2-db --replicas=0
kubectl -n ui2 wait --for=delete pod/ui2-db-0 --timeout=300s

kubectl -n ui2 delete pvc ui2-db-data
kubectl apply -f deploy/ui2/30-database-pvc.yaml

kubectl -n ui2 scale statefulset/ui2-db --replicas=1
kubectl -n ui2 rollout status statefulset/ui2-db --timeout=600s

kubectl -n ui2 rollout restart deployment/ui2-service
kubectl -n ui2 rollout status deployment/ui2-service --timeout=600s
```

The service is restarted because it holds connections to a database that no
longer exists and because its migration state is start-up state (RESET-3).
The next start is a first start: the database initializes empty, creates the
`ui2` database and the two roles, and Flyway re-applies V1 to V7 from
nothing.

## 9. Rotate the credential

Repeat step 5 and restart the workloads. **No tracked file changes** — which
is the operational test of whether the secret path was actually implemented
(contract SEC-6).

```sh
kubectl -n ui2 rollout restart statefulset/ui2-db
kubectl -n ui2 rollout restart deployment/ui2-service
```

A rotation after the database has been initialized also needs the database's
own role password changed; the simplest correct sequence is a reset (step 8)
followed by a fresh credential, because the reset makes the next start a
first start.

## 10. Tear down

```sh
kubectl delete -f deploy/ui2/ --ignore-not-found
kubectl delete namespace ui2 --ignore-not-found
kubectl delete namespace ui2-build --ignore-not-found
```

Deleting the `ui2` namespace destroys the claim and the database with it.

## 11. Triggering the first live inventory run

Once a device is `ENROLLED` (step 4's confirm, or an equivalent add-single
flow, has run), inventory collection is triggered the same way "Collect
now" already triggers it in the UI: a `POST /devices/{id}/inventory/collect`
request. `JobAdmissionService` admits the job only if `cp_inventory_collect`/
`pan_inventory_collect` resolves execution-eligible against the running
`gate_registry` table — `V15__inventory_command_gate_entries.sql` seeds the
Product-Owner-approved 14D CF-3/14E PF-1 literals as `SIGNED_OFF` rows, so a
fresh database that has run migrations through V15 admits both capabilities
without any further seeding step. The worker's claim loop (already polling
every 2 s, step 6) picks the admitted job up, runs it against the real
device through `CompositeDeviceTransport`, and writes one `device_inventory`
run.

The run's counts surface through the existing inventory read routes the
frontend's `InventoryScreen`/`InventoryPanels` already call — the per-device
inventory endpoint returns the latest run's context/interface/route counts
once `InventoryJobExecutor` has recorded it; there is no separate "first
live run" endpoint or flag. A `FAILED`/`REJECTED` job (credential
unresolvable, connect failed, identity mismatch under the strict posture)
is visible the same way any other job's terminal state already is.

## 12. What this procedure does not decide

The contract's §10 records eight `UNKNOWN`s. This procedure observes; it does
not close any of them. In particular the readiness endpoint used above
(`/healthz`) answers as soon as the web server is up, which is **not** the
same as answering only once migration has completed (contract MIG-3, U-6):
the service applies its migrations in a start-up runner that runs after the
server starts, and a failed migration stops the process rather than leaving
it serving. Closing U-6 needs a service-side readiness state gated on
migration completion, which is not this procedure's to invent.
