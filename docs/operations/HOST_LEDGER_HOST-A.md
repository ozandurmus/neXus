# Host ledger — `HOST-A`

Append-only. One entry per operation that touches the host or its cluster, per
`PO_DECISION_RECORD_2026_09_15A` EV-2 and EV-3. Counts and shapes only; no
address, hostname, username, port number or incumbent container name.

Each entry carries: date, tier, why, the intended commands as literals, the
expected end state, the exact rollback, and — after execution — exit status,
wall time, the four baseline counts, and any divergence.

---

## Entry 0 — baseline, 2026-09-15, `HOST_R`

**Why.** Establish the incumbent's footprint before neXus touches the host, so
that a later question of attribution has something to compare against (`EV-1`).

**Taken.** A read-only inventory over SSH: operating system and kernel, core and
memory counts, disk capacity and free space, uptime and load, container runtime
version, running container count, container network count and their address
block sizes, listening port count, presence or absence of a Kubernetes
distribution, host interface address block size, and whether the CGNAT range
routes locally.

**Observed, as counts and shapes.**

| Measure | Value |
|---|---|
| Operating system | Current Ubuntu LTS, kernel 6.8 |
| Cores / memory | 16 / 62 GiB, with 48 GiB free |
| Disk | 1 TiB, 22% used |
| Uptime / load | 11 days / ~3.5 across 16 cores |
| Container runtime | Docker, with Compose available |
| Incumbent containers running | 14 |
| Incumbent container networks | 2, both `/16` inside `172.16/12` |
| Host listening ports | 5 |
| Kubernetes present | no |
| Host interface | one `/23`, RFC1918 |
| CGNAT range routes via | the local interface, not the corporate tunnel |

**Divergence.** None; this entry establishes the baseline rather than comparing
against one.

**Note.** This inventory was taken before this ledger and its governing record
existed. It is recorded here once, reduced to counts and shapes, and is not
re-taken without a named authorization.

---
## Entry 1 — phase A measurement, 2026-09-15, `HOST_R`

**Why.** `HOST_A_MIGRATION.md` steps 2–4: read the incumbent's network blocks
and listening ports as counts and ranges, determine whether a CNI would collide
with the rules the container daemon already manages, and confirm the chosen
CGNAT blocks collide with nothing.

**Seat.** Product Owner assistant, held by Claude. The seat changed holder this
session; the previous holder was Antigravity and did not complete the takeover.
Last entry read before the first command: entry 0 (`15A` SE-4).

**Commands, as literals.** Read-only, non-privileged, over the existing SSH
path: `id -nG`, `ip -br link`, `ip -4 -br addr`, `ip -4 route show`,
`ip -4 route get` against each chosen CGNAT block, `ss -lntH` / `ss -lnuH`,
`lsmod`, `sysctl -n` for three keys, `stat -fc %T /sys/fs/cgroup`, `nproc`,
`/proc/meminfo`, `df -P /`, `iptables -V`, `systemctl is-active` for five unit
names, and `command -v` for four binaries. No incumbent container, volume, log,
network or database was contacted. No `sudo`.

**Observed, as counts and shapes.**

| Measure | Value |
|---|---|
| Interfaces / bridge interfaces | 18 / 2 |
| IPv4 addresses, by prefix length | two `/16`, one `/23`, one `/8` |
| Addresses inside `10/8` / `172.16/12` / `192.168/16` | 1 / 2 / 0 |
| Addresses inside CGNAT `100.64/10` | 0 |
| Routes overlapping the chosen CGNAT blocks | 0 |
| Listening TCP / UDP | 10 / 4 |
| Ports k3s requires that were already occupied | 0 |
| `iptables` backend | `nf_tables` |
| Native nftables ruleset | absent — five tables, all the iptables-nft shim's own |
| `ufw` | unit reported active; ruleset empty; `ufw status` reports `inactive` |
| `FORWARD` default policy | `DROP`, set by the container daemon |
| Daemon-managed chains | 7 |
| cgroup / kernel | cgroup v2, 6.8 |
| `br_netfilter` / `ip_forward` | loaded / enabled |
| Swap active | ~8 GiB |

**Step 3 — CNI collision: determined, no hard collision.** The two collisions
that would have stopped the sequence were both looked for and both absent. There
is no `legacy`/`nft` split between the daemon and a CNI, because the host has no
native nftables ruleset and resolves `iptables` to the `nf_tables` backend, so
both write through the same path. And `ufw` does not filter: the unit reads
active but carries no chains and reports itself inactive.

What is present is `FORWARD DROP`, set by the container daemon. A CNI coexists
with it by inserting its own accept rules, which is the documented arrangement
rather than a conflict.

**Residual risk, named rather than dismissed.** A restart of the container
daemon re-asserts its own `FORWARD` rules and can interrupt pod traffic until
the CNI's rules are re-added. This risk points at neXus's own workloads, not at
the incumbent's, which is why it does not meet the phase A stop condition. It is
recorded here so a later interruption is not investigated from zero.

**Step 4 — CGNAT blocks: no collision.** Neither chosen block appears in any
interface address or route, and a route lookup for an address in each resolves
via the host's own interface rather than the corporate tunnel, matching entry 0.

**Could not be determined.** Nothing that the stop condition required. The
firewall ruleset itself is not readable unprivileged — measured, not assumed —
so the daemon's chain contents were read by the Product Owner and reported back
as counts; they are recorded above as counts and are attributed to the human,
not to an agent read.

**Deviation, recorded rather than resolved.** The account this seat was given
reach through is in four privileged groups, three of them independently
root-equivalent. `15A` HA-2 states that an agent's account is not in those
groups, so this is not an agent identity — it is the operator's own account,
which the register itself anticipated. Only `HOST_R`-effect reads were issued
from it and no `sudo` was invoked, but the posture is a deviation and the
dedicated non-sudo account does not exist. The Product Owner elected that they
run every command with write effect themselves rather than create that account,
so the register's ceiling stays at `HOST_R` and is not raised by this entry.

**Divergence from entry 0.** Entry 0 recorded `Kubernetes present: no`. A
Kubernetes distribution's binary was present, dated the same day, with its unit
enabled and failing to start — 482 restarts. Cause is not asserted from absence
(`15A` IN-2); the Product Owner subsequently stated they had run the installer
and that it had failed to download. `EV-2` required a ledger entry before that
installation and there was none. Recorded here once, after the fact.

---

## Entry 2 — phase B installation, 2026-09-15, `HOST_W2` performed by the human

**Why.** `HOST_A_MIGRATION.md` steps 6–9. The agent prepared the commands and
the validation plan; the human ran every one of them (`15A` §4).

**Intended commands, as literals, prepared before execution.** Install the
corporate CA chain into the system trust store (`cp` into
`/usr/local/share/ca-certificates/`, then `update-ca-certificates`); run the
distribution's installer with `--cluster-cidr`, `--service-cidr`,
`--cluster-dns` set to the blocks `15A` PL-4 fixes, and with the bundled ingress
controller and load-balancer both disabled; write a registry mirror
configuration under `/etc/rancher` and restart the service.

**Expected end state.** Node `Ready`; the three system workloads running; the
chosen CIDRs bound; no host port bound by anything neXus runs; the incumbent's
four baseline counts unchanged.

**Rollback.** The distribution ships its own uninstall script. It was
deliberately **not** used to clear the failed first attempt, because it performs
a full `iptables` save-filter-restore cycle, and a momentary whole-ruleset
replacement on a host carrying another product's production service is a larger
risk than the cruft it removes. Nothing needed clearing: the failed attempt had
never started, so no state, no CNI interface and no configuration directory
existed. The installer overwrote the binary and the unit file.

**Exit status and observations.**

| Measure | Value |
|---|---|
| First installation attempt | failed; the binary was a 6.8 KB HTML page, the intercepting proxy's sign-in page, written by the installer and executed as a binary (`Exec format error`, 482 restarts) |
| Root cause | the proxy's CA chain was absent from the host trust store, so the download did not verify |
| Corporate CA certificates installed | 4, all valid; a fifth, an expired copy of one of them, was deliberately excluded |
| TLS verification after install | clean |
| Second installation attempt | succeeded, binary hash-verified by the installer |
| Node status | `Ready`, control-plane |
| System workloads | 3, all running |
| Default storage class | present |
| Host ports bound by neXus | 0 — the bundled ingress controller and load-balancer were disabled precisely so that `15A` PL-5 cannot be breached; reach is by port-forward |
| Wall time | installation under a minute; the whole sequence about one hour, nearly all of it diagnosis |

**Incumbent, before and after (`EV-3`).**

| Measure | Entry 0 | After installation | After service restart |
|---|---|---|---|
| Containers running | 14 | 14 | 14 |
| Daemon-managed chains | — | 7 | 7 |
| `FORWARD` default policy | — | `DROP` | `DROP` |

**Divergence from the expected end state.** None.

**Note on step 9.** The incumbent was confirmed still serving by its own
container count and firewall shape, not by reading its data, its logs or its
application surfaces — those are `HOST_X` and have no authorization form.

**Note on unmanaged trust material.** Two multi-certificate `.pem` files from an
earlier attempt sit directly in the system certificate directory, outside what
the trust-store tool manages, which reports them as skipped. They are inert now
that verification passes. They are recorded as cleanup, not as a finding.

---

## Entry 3 — the old cluster's state is not carried across, 2026-09-15

**Why.** `HOST_A_MIGRATION.md` phase C steps 11, 12 and 15 assume the previous
environment's state is exported, dumped and restored. The Product Owner decided
on 2026-09-15 that nothing is carried across and the new environment starts
empty. Recorded here because the decision exists nowhere else and chat is not an
authority (`AGENTS.md` item 7).

**What this means concretely.** The four hand-created Secrets are not exported.
The verified database dump is not restored. New key material is generated in the
new namespace. Steps 11, 12 and the restore half of step 15 do not run.

**What was weighed.** The dump alone decrypts nothing: the key material that
would open it exists only in the previous cluster, which is unreachable. The
objection was raised once and the Product Owner, informed of it, decided the
data — synthetic development data, with every live outcome `UNVERIFIED` and the
backup pilot allowlist empty — is not worth carrying.

**What was done to keep the decision reversible.** The previous cluster's
virtual machine is **not** deleted. It remains stopped, at no cost, so the
Secrets and the dump stay recoverable if the decision is revisited. Deleting it
is refused by `roles/PO.md` §1b in any case.

---
## Entry 4 — phase C, the product deployed and serving, 2026-09-15

**Why.** `HOST_A_MIGRATION.md` steps 13-16, less steps 11, 12 and the restore
half of 15, which entry 3 records as not carried across. The human ran every
command; the assistant prepared each one and read the output.

**What was prepared and run, as literals.** A registry Deployment, Service and
claim inside the cluster, named so that the in-cluster registry hostname the
build and both Deployments already reference resolves unchanged — no file under
`deploy/` was edited. A registry mirror configuration under `/etc/rancher` and a
service restart (`HOST_W2`). The corporate CA chain into the build context's own
`.ca/` directory, which the `Containerfile` already reads for exactly this case.
The in-cluster Kaniko build. The five Secrets, created once and patched, never
applied over. The manifest set minus the Secret files. Both Deployments pointed
at the digest the build produced. A one-off Flyway job. A port-forward.

**Five obstacles, four of them this network's and one of them ours.**

1. The first installation attempt had written the intercepting proxy's sign-in
   page into the binary path (entry 2). Fixed by installing the corporate CA
   chain on the host.
2. Kaniko could not pull base images: the host trust store is not the
   container's. Fixed by mounting the host's trust bundle into the builder.
3. The Gradle wrapper could not fetch its own distribution. The download
   succeeds from the host through the proxy and fails from inside the build,
   and the proxy settings offered to the build did not change that. Rather than
   keep guessing at propagation, the distribution was placed in the build
   context at the path the wrapper checks before downloading. Whether the
   builder propagates its environment into build steps is `UNKNOWN` and was not
   measured.
4. `containerd` could not resolve the in-cluster registry name: it runs on the
   host, and the name is cluster-internal. Fixed by pointing the mirror at the
   service's cluster address, which is routable from the host on this
   distribution. No host port was bound.
5. The product could not start against an empty database. This one is not the
   network's: a bean reads a table during context refresh, while the runner that
   applies migrations only runs after refresh completes, so on an empty schema
   the context fails before migrations are ever attempted. Backlog item
   `ui2_service_cannot_bootstrap_an_empty_database`, `P0`.

**The workaround for 5, and what it does not change.** Migrations were applied
once by a one-off job using the same migration engine at the same version, the
same SQL carried in from the repository, running as the migration role. Flyway
remains the sole migration authority (`C1`) and the schema is at its head
revision. The job is a way to open a first environment, not a fix: the next
empty database will fail the same way until the bean ordering is corrected.

**Observed end state.**

| Measure | Value |
|---|---|
| Database workload | running |
| Service workload | running, serving its login page |
| Worker workload | running |
| Migrations | all applied, schema at head |
| Secrets | five, each carrying the key lengths its consumer requires |
| Host ports bound by neXus | none; reach is a loopback forward |
| Data carried from the previous environment | none (entry 3) |

**Incumbent, across the whole day (`EV-3`).**

| Measure | Entry 0 | After installation | After the registry restart | After deployment |
|---|---|---|---|---|
| Containers running | 14 | 14 | 14 | 14 |
| Daemon-managed chains | — | 7 | 7 | 7 |
| `FORWARD` default policy | — | `DROP` | `DROP` | `DROP` |

**Divergence from the expected end state.** None for the incumbent. For neXus,
obstacle 5 above: the environment is open by a documented workaround rather than
by the product starting unaided, and that distinction is the reason it is
recorded here rather than reported as a clean first start.

**Seat and tier.** Product Owner assistant, held by Claude. Every `HOST_W2` step
was performed by the human. The assistant issued no `sudo`, joined no privileged
group, and held no container runtime socket. A request to operate the host shell
directly was declined against `15A` HA-2 and HA-3, and the narrower authorized
form — a kubeconfig scoped to neXus's own namespace — was offered instead and
not taken up; the register's ceiling therefore stays at `HOST_R`.

**Cleanup outstanding.** Two unmanaged certificate files in the system
certificate directory from an earlier attempt (entry 2). A build-context copy of
the build tool's distribution, which is ignored by the repository and must stay
so.

---
