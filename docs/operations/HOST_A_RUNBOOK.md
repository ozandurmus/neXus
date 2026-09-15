# HOST-A runbook — how this environment is actually operated

## Status

**OPERATIONAL NOTES, 2026-09-15.** Not a contract. The authority for what an
agent may do on this host is
`docs/design/PO_DECISION_RECORD_2026_09_15A_THE_DEVELOPMENT_HOST_AND_WHAT_AN_AGENT_MAY_DO_ON_IT.md`;
the order of the migration is `HOST_A_MIGRATION.md`; what was done is
`HOST_LEDGER_HOST-A.md`. This file is the fourth thing: the commands that were
actually run, and the traps that cost time on the day.

**No address, hostname, username, port number or key path appears here**
(`15A` EV-4). Where one is needed, the text says which store holds it. The
credential holder is the Product Owner (`docs/design/HOST_REGISTER.md`).

## 1. Reaching the host

The key lives on the operator's laptop and the connection details live in the
operator's own store, not here. An agent holds no host credential of its own:
the dedicated non-sudo account the register requires does **not exist yet**, and
the account used on 2026-09-15 is the operator's own, which is in `sudo`,
`docker`, `adm` and `lxd`. That account is not an agent identity (`15A` HA-2,
HA-3), so every command with write effect on that day was run by the human.

Host-key trust was pinned by the human on first connection and must stay
`StrictHostKeyChecking yes` (`15A` CR-3).

## 2. Where things are

On the host:

- The repository is cloned in the operator's home directory. It is a plain
  public clone; no credential is stored for it.
- `kubectl` is k3s's own bundled binary. The operator's kubeconfig is a copy of
  the cluster's, in their home under `.kube/`, and `KUBECONFIG` is exported from
  their shell profile. Without that export, `kubectl` reads the root-owned
  file under `/etc/rancher` and fails with a permission error that looks like a
  cluster problem and is not.
- A patched build job sits in the operator's home as a working file, not in the
  repository. It is the repository's build job plus three things the corporate
  network needs: proxy environment, a mounted CA bundle, and the build context
  path. See §5.

In the cluster:

- `ui2` — the product: database, service, worker, and five Secrets.
- `ui2-build` — the in-cluster image build: a context claim, a loader pod, and
  the build job.
- `kube-system` — the cluster's own registry, and Traefik.

## 3. The corporate network, and the four places it bites

This network runs a TLS-intercepting proxy. It broke the build in four separate
places on 2026-09-15, each with a different fix, and every one of them will
return on a fresh host.

**The host trust store.** Without the corporate CA chain installed, the
installer downloaded an HTML sign-in page, wrote it to the binary path and
executed it. Install the chain into the system trust directory and refresh it;
verify with a TLS probe that reports a verify result of zero, not merely a 200.

**The build container's trust store is not the host's.** The `Containerfile`
already anticipates this: it copies `ui2/.ca/*.pem` into the image's anchors and
refreshes them. That directory is git-ignored and normally empty. **Before every
build on this network, copy the CA chain into `ui2/.ca/` and rename to `.pem`** —
the glob matches only that extension.

**Java does not read proxy environment variables.** `curl` and `npm` honour
`HTTPS_PROXY`; the JVM needs `-Dhttps.proxyHost` and `-Dhttps.proxyPort`. And
`GRADLE_OPTS` must contain no shell metacharacter: a `nonProxyHosts` value with
`|` and `*` silently corrupted the whole variable and the proxy settings never
reached the JVM at all.

**The Gradle distribution download.** Even with the proxy configured, fetching
the distribution failed. It is placed in the build context instead, at the path
the wrapper checks before downloading:
`ui2/.gradle-home/wrapper/dists/gradle-<version>-bin/<hash>/`. The hash is
base36 of the MD5 of the `distributionUrl` string, and
`gradle-wrapper.properties` is the authority for that URL. Recompute it if the
version changes; do not copy the directory name from here.

Whether the builder propagates its environment into build steps was never
measured and is **UNKNOWN**. Two attempts to pass proxy settings that way
changed nothing, which is why the distribution is carried in instead.

## 4. The registry, and why the obvious configuration fails

The build pushes to a cluster-internal registry and the node pulls from it. The
manifests name that registry by its in-cluster DNS name, so a registry Service
with that exact name in `kube-system` means nothing under `deploy/` has to
change.

The trap: **`containerd` runs on the host and cannot resolve cluster DNS.**
Pointing the mirror at the service *name* produces `ImagePullBackOff` with a
lookup failure. Point it at the Service's cluster address instead — routable
from the host on this distribution — in the registry configuration under
`/etc/rancher`, then restart the service. That address changes if the Service is
recreated, so read it rather than remembering it.

## 5. Building and rolling out

The order is: update the checkout, put the CA chain in `ui2/.ca/`, put the
Gradle distribution in place, stream the context, build, then point the
Deployments at the digest the build produced.

Two things that are easy to get wrong:

- **The build context is the repository root, not `ui2/`.** It changed on
  2026-09-15 so that the image could carry the project-plan data the
  Administration screen reads. The context stream therefore carries `ui2` **and**
  `project`, and the patched build job's `--context` must match. A stale
  `--context=dir:///workspace/ui2` builds without the project data and the
  screen renders empty again.
- **Never apply the Secret manifests over existing Secrets.** They carry the
  contract — name, type, key names — and no value. Applying them wipes the live
  value (`roles/PO.md` §1b). Apply the manifest set *without* the `2*-secret-*`
  files on every rollout after the first.

## 6. Reaching the product

Traefik terminates TLS with its own self-signed certificate until a real one is
issued, and the Ingress carries a host rule, so the browser must resolve that
name to the host. Until DNS exists, that is a hosts-file entry on the operator's
laptop.

One trap that wasted time: **the corporate proxy intercepts your own test.** A
`curl` from the host to the product's own hostname goes out through the proxy,
which cannot resolve it, and fails in a way that looks like a broken TLS
listener. Bypass the proxy explicitly when testing the local cluster.

## 7. What is not done

The dedicated non-sudo agent account does not exist, so the register's ceiling
stays `HOST_R` and every write is the human's. Raising it needs that account, a
namespace-scoped kubeconfig, and the ledger entries `15A` §5 requires.

Two unmanaged certificate files from an early attempt sit in the system
certificate directory, outside what the trust tool manages. They are inert and
should be cleaned up.

The previous environment's virtual machine is stopped, not deleted, and still
holds the four hand-created Secrets and a verified database dump. Nothing was
carried across (ledger entry 3), and that decision stays reversible only while
that machine exists.

## 8. The lesson that cost the most

The product's start-up order can only be proven by a real deployment. On
2026-09-15 a movement fixed a genuine defect — the service could not start
against an empty database — by moving migrations earlier in the Spring
lifecycle. Its unit tests passed, its verification gate passed, the privacy gate
passed, and the merged result crash-looped on first deployment, because the new
lifecycle position runs before configuration placeholders are resolved and the
migration credential path arrived unresolved.

`AGENTS.md` already says automated validation is not real-environment
validation. Nothing in any packet that day required a real start, and that is
the gap: **a movement that changes start-up ordering must be deployed before it
is called done.**
