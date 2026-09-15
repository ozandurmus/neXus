# PO Decision Record — 2026-09-15 A — The development host, and what an agent may do on it

## Status

**FROZEN — PRODUCT OWNER DIRECTIVES, 2026-09-15.** Given in session after a
three-seat council and one independent external review. Successor clause to
`PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md` §1's staged table, which
named "Ubuntu server, later" without naming a host or authorizing an agent to
act on one. It adds a new authority axis the repository did not have: what an
**agent** may execute on a **host**, as distinct from what the **product** may
execute against a **device**.

`utils/action_taxonomy.py`, the network-device command gate, `12B`, `01C`,
`13D` and `14H`'s pilot-device rules are untouched by this record.

## 1. Why this record exists

The development environment could not stay on the Product Owner's laptop. The
corporate VPN captures every RFC1918 range, including the local cluster's own
service and pod blocks and the hypervisor's node address; the hypervisor's NAT
subnet is fixed and not configurable. Measured on 2026-09-15: with the VPN up,
`10/8`, `172.16/12` and `192.168/16` all route to the tunnel, while the CGNAT
range does not. The cluster became unreachable repeatedly, the operator was
locked out of the product mid-task, and several defects were investigated in
the product that turned out to be the environment.

The Product Owner is the infrastructure authority here and states that no VPN
exception will be made and no separate machine will be provisioned. The chosen
host is an existing server that also runs another product's workload today, and
that the Product Owner intends to take over entirely in future.

**Consent.** The Product Owner authorizes the use of this host, in session, on
2026-09-15. That authorization is recorded here because it exists nowhere else
and because `AGENTS.md` item 7 makes chat non-authoritative.

## 2. Placement (PL)

- **PL-1. The runtime is Kubernetes**, as `12B` §1 already fixes for the server
  stage: one OCI image, built in-cluster or in CI and pulled by digest, driven
  with `kubectl`. `k3s` is the chosen distribution for its footprint beside an
  incumbent workload.
- **PL-2. No host container tool, on any path.** `12B` §1 and `01C` BP-1/BP-2
  stand unchanged and un-amended. A Docker Compose deployment on this host was
  proposed by the Product Owner assistant, was wrong, and is refused. `01C` §11
  check 3 continues to prove this by absence.
- **PL-3. The decisive reason is not the rule.** `k3s` runs on containerd and
  does not use the incumbent's Docker daemon. Control of a rootful Docker daemon
  is root-equivalent on the host, so any Compose or `docker`-group arrangement
  would have given every neXus deployment the ability to reach the incumbent's
  containers and data. Choosing a separate runtime removes that capability as a
  technical fact rather than as a promise.
- **PL-4. Address blocks come from the CGNAT range**, which was measured not to
  be captured by the VPN and not to overlap anything the host or the incumbent
  uses: service CIDR `100.64.32.0/20`, pod CIDR `100.65.0.0/16`. The incumbent's
  `172.16/12` Docker networks and the host's own interface are untouched.
- **PL-5. Nothing neXus runs may bind a port the incumbent publishes**, join a
  network the incumbent owns, or mount a path outside neXus's own storage.

## 3. The host register (HR)

- **HR-1.** A host enters scope only through `docs/design/HOST_REGISTER.md`. A
  host not in the register authorizes no command at all, including a read.
- **HR-2.** The register carries an opaque host token, the owner of the
  incumbent workload, the account's privilege posture, the credential holder by
  role, the admission date, the current tier ceiling and an incident contact
  route. **It never carries an address, hostname, username or key fingerprint**
  — those live in the operator's own store (`AGENTS.md` sensitive identity
  reporting law; `PRIVACY_AND_DATA_HANDLING.md` CLASS 2).

## 4. What an agent may do (HA)

A parallel vocabulary. It is **not** an extension of `CLASS_0..CLASS_4`, whose
fields (`console_submittable`, `refusal_code`) have no meaning for a host
operation, and whose members are pinned by an existing test.

| Tier | Meaning | Authority |
|---|---|---|
| `HOST_R` | Reads that change nothing and touch no other product's data: node and cluster health, our own workloads' status and logs, our own namespace's objects | Standing, once the host is registered |
| `HOST_W1` | Writes confined to neXus's own declared workspace: deploy, restart, scale or roll back our own workloads; run our own in-cluster build; edit our own source checkout | Standing task grant, with the ledger entry of §5 |
| `HOST_W2` | Anything outside that workspace or any elevation: installation, packages, `systemd`, firewall or CNI, sysctl, users, anything under `/etc`, Docker daemon configuration, RBAC, node operations | **The human performs it.** The agent prepares the exact commands and the validation plan |
| `HOST_X` | Reading, copying, tailing, querying or exporting the incumbent's data, containers, logs, volumes or database; host root shell; global prune; daemon restart; host reboot; disabling a control | **Prohibited.** No authorization form exists |

- **HA-1. The tier is decided by effect, not by command name.** An agent that
  cannot tell which workload a command will affect treats it as `HOST_W2`.
- **HA-2. No `sudo`, ever, for an agent identity.** The presence of sudo on an
  account does not authorize its use. The agent's account is not in `sudo`,
  `admin`, `wheel`, `docker` or `adm`. `docker` group membership is
  root-equivalent and is indistinguishable from sudo for this purpose.
- **HA-3. No container runtime socket.** The agent never holds
  `/var/run/docker.sock`, a containerd socket, or a remote daemon credential.
- **HA-4. After installation the agent has no host access at all.** Its reach is
  a kubeconfig scoped to neXus's own namespace. Host operations end when
  installation ends; everything after is a cluster operation.
- **HA-5. An image update is `HOST_W1` only when review proves it carries no
  migration.** Our service runs Flyway at startup, so an image update can change
  the schema. Where it does, it is `HOST_W2`, and image rollback does not undo
  it — the pre-migration dump is the rollback and is taken first.
- **HA-6. Builds run in-cluster**, never on the host and never on the incumbent's
  daemon. Build concurrency, image cache growth and pull bandwidth are bounded.
- **HA-7. Namespace-scoped RBAC is not a blast radius.** Whoever can create a
  workload in a namespace can mount what that namespace can mount and select its
  service accounts. Therefore an agent that can freely replace workloads cannot
  also be promised not to reach live credentials. The consequence is §6.

## 4a. Who holds host reach (SE)

The vocabulary above says what may be done. This section says *who* may do it.
Neither the Product Owner seat nor the worker seat belongs to a vendor: either
may be held by any participant on the roster (`docs/reference/MODEL_TIER_MAP.md`),
the Product Owner chooses which at the start of a session, and may change the
holder mid-session. The rules below are therefore written about seats, never
about a product name, and a change of holder changes nothing in them.

- **SE-1. Host reach belongs to the assistant seat, never to a worker.** An
  orchestrated worker runs unattended, in a worktree, against a budget, with no
  human reading its output as it goes. It gets no host credential, no kubeconfig
  and no reach beyond its worktree. A movement that needs something on the host
  says so in its report; the assistant does it, in the open, with the ledger
  entry of §5.
- **SE-2. Only one seat holds host reach at a time.** Parallel movements are
  normal; parallel host reach is not. Two holders make the ledger a partial
  record of what happened on the host, and §7's first question — what changed —
  stops being answerable.
- **SE-3. The tier ceiling does not move with the holder.** A more capable
  participant in the assistant seat does not earn `HOST_W2`, and a lighter one
  does not lose `HOST_R`. The ceiling is the register's
  (`docs/design/HOST_REGISTER.md`), and `HOST_W2` stays with the human whoever
  holds the seat.
- **SE-4. A handover between holders carries the ledger, not the trust.** The
  incoming holder reads the host's ledger before its first command and states
  the last entry it read. Whatever the outgoing holder believed about the host's
  state and did not write down is `UNKNOWN` to its successor (IN-1).
- **SE-5. A participant's own operating limits still apply on the host.** Known
  participant behaviour — an anchored working directory, a missing pre-push gate,
  an absent usage signal — is recorded in
  `docs/reference/PROVIDER_OPERATING_NOTES.md` and is read as part of choosing a
  holder. A limit that would make a host command's effect unpredictable makes
  that command `HOST_W2` for that holder, by HA-1.

## 5. Evidence (EV)

- **EV-1. Baseline before the first write.** The incumbent's footprint is
  captured as counts and shapes only — container count, network count, published
  port count, volume count, free disk — never names, addresses or ports. Stored
  as entry zero of the host ledger.
- **EV-2. Before a `HOST_W1` or `HOST_W2` change**, a ledger entry records the
  intended commands as literals, the tier, the expected end state and the exact
  rollback. Before, not after.
- **EV-3. After**, the same entry records exit status, wall time, the four
  baseline counts again, and any divergence from the expected end state. Raw
  transcripts are not retained (`AGENTS.md` raw-evidence law).
- **EV-4.** No address, hostname, username, key path or incumbent container name
  enters any repository file, relay entry, packet or pull request.

## 6. Two profiles (PR)

- **PR-1. `DEV` — synthetic data only.** The agent works autonomously within the
  standing grant: edit source, run tests, build in-cluster, deploy and restart
  its own workloads, read its own diagnostics. The environment holds only
  synthetic credentials and synthetic device data.
- **PR-2. `LIVE` — real credentials or real device evidence.** A separately
  authorized environment and session. The agent proposes changes and reads
  sanitized diagnostics; it does not deploy unreviewed code there. Device
  contact stays under the existing contracts and the real-environment procedure.
- **PR-3. `DEV` is not a switch the agent may flip.** Moving work to `LIVE` is a
  Product Owner decision with its own record.

## 7. Incidents (IN)

- **IN-1. Stop first.** On any sign of incumbent degradation, or any unexplained
  resource pressure, the agent stops issuing writes of every tier and does not
  start another repair.
- **IN-2. Cause is `UNKNOWN` by default.** "I only touched my own workspace" is
  not evidence of non-causation. The evidence is the baseline counts across the
  agent's own window, and that is what the record carries. Neither
  `CAUSED_BY_AGENT` nor `NOT_CAUSED_BY_AGENT` may be asserted from absence.
- **IN-3. Report, do not repair.** The agent notifies through the register's
  contact route with a safe summary and does not inspect, restart or stop
  anything belonging to the incumbent — that is `HOST_X` during an incident as
  at any other time.
- **IN-4.** The only write permitted during an incident is stopping neXus's own
  workloads, and only when the Product Owner asks for it.
- **IN-5.** The tier ceiling drops to `HOST_R` until the Product Owner raises it.

## 8. Credentials (CR)

- **CR-1.** The key that reaches this host is held by the operator. It is never
  read, copied, echoed or referenced by path in any repository file or packet.
- **CR-2.** The password file created on the laptop during setup is removed and
  the account password rotated, now that key access exists. A second, weaker
  factor for the same account only widens the blast radius of a laptop
  compromise.
- **CR-3.** Host-key trust is pinned by the human on first connection. A
  mismatch is a stop, never a disabled check.
- **CR-4.** Rotation is triggered by: a session with host reach ending
  abnormally, a ledger entry whose observed state diverged unexpectedly, a
  change in the corporate network posture, or ninety days.

## 9. What this does not decide

Off-host custody of the artefact-store master key (`14I` AL-4) stays open. The
long-term artefact location (`14I` AL-2) stays open; retention was decided
separately as disk-bound with no fixed limit. The incumbent's own backup and
snapshot regime, and whether it captures neXus volumes, is `UNKNOWN` and is a
question for its administrators before any CLASS 3 material is placed there.

## 11. Where these clauses came from (PV)

The council skill persists nothing and chat is not an authority (`AGENTS.md`
item 7), so the reasoning that produced the clauses above would otherwise
survive nowhere. What follows is provenance, not authority: no clause is weaker
because a seat argued for it, and none is stronger.

**Composition.** Three specialist seats, run independently and read-only, then
one blinded challenge pass, then this synthesis. The seats shared a model
family, so this was **role-diverse review only** — not independent cross-model
validation. One external review was obtained separately, on the Product Owner's
own initiative and through their own account, over a packet that carried no
address, hostname, username or fingerprint.

**What the review changed, clause by clause.**

- **PL-2** exists because two seats independently found that this session had
  twice recommended a host container tool, confidently, against `12B` §1 and
  `01C` BP-2 — the second of which is enforced by a grep gate that would have
  rejected the work anyway. The recommendation was the assistant's, not the
  Product Owner's; the Product Owner had originally proposed Kubernetes and was
  talked out of it. The refusal is recorded rather than quietly reversed
  because the failure mode was confidence, and confidence does not announce
  itself next time.
- **The consent paragraph in §1** exists because the external review separated
  two questions the assistant had merged: whether the runtime is compatible
  with this host, and whether placing our workload beside another product's
  production is authorized. The first is a technical finding; the second is only
  ever a human's to give, and had not been given at the time it was assumed.
- **HA-7** exists because the assistant claimed a namespace-scoped kubeconfig
  "closes the door". It does not: whoever can create a workload in a namespace
  can mount what that namespace can mount and select its service accounts. The
  clause states the limit instead of the comfort, and §6's two profiles follow
  from it.
- **HA-2 and HA-3** were the one point of full agreement across every seat and
  the external review, reached independently: an agent identity holding `sudo`,
  a privileged group, or a runtime socket makes every other clause decorative,
  because each is root-equivalent on this host.
- **The four-tier vocabulary** replaced an earlier two-way permitted/prohibited
  split that the challenge pass showed could not express the largest real
  category — operations that are legitimate, necessary, and still not an
  agent's to perform. `HOST_W2` is that category, and naming it is what keeps
  it from being quietly folded into `HOST_W1` under time pressure.

**Material dissent, retained.** One position held that co-location should be
refused outright until the incumbent's administrators had been consulted, on
the grounds that the incumbent's own backup and snapshot regime is `UNKNOWN`
and may or may not capture our volumes. The Product Owner, who is the
infrastructure authority here, authorized the host regardless. The dissent is
not resolved by that authorization — it is carried forward as the open question
in §9, and it is the reason `DEV` in §6 is a ceiling rather than a starting
point.

**An assertion that did not survive.** The assistant argued that a VPN
exception would be cheap. It had no evidence of feasibility, cost or lead time,
and the external review marked it `UNKNOWN`. The Product Owner then settled the
question by authority rather than by evidence — no exception will be sought —
which closes the decision without validating the claim. The claim stays marked
as what it was.

**Revisit trigger.** This record is reopened when the Product Owner takes the
host over entirely, when the incumbent's administrators answer §9's backup
question, or when a profile change to `LIVE` is proposed — whichever is first.

## 10. Cross-references

- `PO_DECISION_RECORD_2026_09_12B_LOCAL_KUBERNETES.md` §1 — the runtime, unchanged.
- `UI2_0_B1_01C_CONTAINER_IMAGE_AND_KUBERNETES_DEPLOYMENT_CONTRACT.md` BP-1..BP-4, §11 check 3.
- `PO_DECISION_RECORD_2026_09_13D_INDEPENDENTLY_DEPLOYABLE_SERVICES.md` — DS-1, and §3's AUTH-PLACEMENT, still open.
- `AGENTS.md` — authority hierarchy, diagnostic-path law, raw-evidence law, sensitive identity reporting law, network action taxonomy.
- `PRIVACY_AND_DATA_HANDLING.md` — CLASS 2 and CLASS 3.
