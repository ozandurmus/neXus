# HOST-A hardening: measured state and decisions (DRAFT)

**Status:** DRAFT -- measurement and proposal for Product Owner review; not implementation authority. No setting was
changed to produce it.

Product Owner, 2026-09-27: "Sunucunun hardening kararlarını hiç konuşmadık. Burada bir çalışma yapmamız lazım."
Measured read-only on 2026-09-27 (HOST-A: Ubuntu 26.04.1 LTS, single-node k3s, neXus namespace `ui2`).

## 1. Already in good shape
- neXus pods: run as non-root, read-only root filesystem, no privilege escalation, all capabilities dropped, CPU and
  memory limits set. Database and internal services are ClusterIP only (not exposed outside the cluster).
- SSH: root login key-only (`prohibit-password`); modern ciphers/MACs/key exchange (post-quantum hybrid first).
- AppArmor enabled; unattended security upgrades enabled; `/tmp` mounted `nosuid,nodev`.
- Application layer: credentials, artefacts and transcripts encrypted by neXus (credential store, artefact store keys).

## 2. Findings, by priority
| # | Finding | Why it matters | Proposed fix |
| --- | --- | --- | --- |
| H1 | **The UI is served over plain HTTP** (Ingress has no TLS). | Passwords at login and session cookies cross the network in clear. | TLS on the Ingress with a certificate from the corporate CA; HTTP redirected to HTTPS; `Secure` cookies; HSTS. |
| H2 | **Host firewall inactive.** The k3s API (6443), kubelet (10250) and the overlay network (8472/udp) listen on all interfaces. | Cluster control ports are reachable from the whole network; only their own authentication protects them. | Host firewall (nftables/ufw): inbound 22 and 80/443 from approved management networks only; 6443/10250/8472 local only. |
| H3 | **k3s Secrets not encrypted at rest.** | neXus's own key material (credential-store, artefact-store, privacy HMAC keys) sits in Kubernetes Secrets, readable from the k3s datastore on disk. | `k3s secrets-encrypt enable` + rotate; back up the encryption config with the keys. |
| H4 | **SSH password authentication on**, X11 and TCP forwarding on, MaxAuthTries 6, no user allowlist. | Password guessing possible; forwarding enables pivoting. | Key-only, `AllowUsers` the named admin accounts, forwarding off, MaxAuthTries 3, idle timeout. |
| M1 | **Time not synchronized:** the configured NTP sources are unreachable. | Wrong timestamps in audit/job records and logs; certificate validity checks. | Point chrony at the corporate NTP servers. |
| M2 | **No host audit daemon** (auditd inactive). | No record of host-level changes beyond sudo logs. | auditd with a baseline rule set (identity files, sudo, k3s config, SSH config), logs kept locally and forwarded. |
| M3 | **No NetworkPolicy in `ui2`.** | Any pod in the namespace can reach the database and every service. | Default-deny + explicit allows (service/worker/compliance/configuration -> db; ingress -> service). |
| M4 | **Logs are not forwarded** (no SIEM target measured). | Host, k3s and neXus audit events stay on the box. | Forward journald and the neXus audit log to the corporate SIEM. |
| L1 | Pod Security Admission not enforced on the namespace. | Future pods could run privileged; today's already comply. | Label `ui2` with `pod-security.kubernetes.io/enforce=restricted`. |
| L2 | No disk encryption. | Physical/VM-disk theft exposes the database (application-encrypted fields excepted). | Decide with the virtualisation owner (VM-level encryption is usually the right layer). |
| L3 | 8 pending package updates; one `NOPASSWD` sudo rule present. | Patch lag; the NOPASSWD rule should match the logged-sudo decision (2026-09-24). | Scheduled maintenance window with reboot; review the rule. |

## 3. Not measured yet
- Backups of HOST-A itself: database dumps, the k3s datastore, the Secrets/keys (without the keys, the encrypted
  credential store and artefacts are unrecoverable). Proposed as its own item with a restore test.
- CIS Ubuntu benchmark scan (e.g. OpenSCAP / `usg`), if the corporate standard requires it.

## 4. Decisions for the Product Owner
1. H1: certificate source (corporate CA?) and the DNS name the UI will be reached by.
2. H2/H4: the approved management networks for SSH and HTTPS; which named accounts keep SSH access.
3. M1: corporate NTP server addresses. M4: SIEM target and protocol.
4. Maintenance window for package updates and reboots (neXus is down during a reboot).
5. Order: proposed H1 -> H3 -> H4 -> H2 -> M1..M4 -> L1..L3, one change at a time, each verified and reversible
   (firewall changes applied with an automatic rollback timer so a mistake cannot lock the host out).

## 5. Product Owner decisions (2026-09-27) and progress
1. TLS: the corporate CA later (the PO can register a host name); until then a neXus local CA, whose root the PO adds
   to their own trust store. -> **Done 2026-09-27 (H1):** local CA + server certificate in `/etc/nexus/pki` (keys
   root-only, never in Kubernetes or the repository); Traefik default certificate + permanent HTTP->HTTPS redirect
   (`deploy/hosta/traefik-config.yaml`); session cookie `Secure`. HSTS deferred until the corporate certificate.
2. SSH: only the VPN block and the team's virtual-machine block. HTTPS: open to everyone. Every other port closed.
   (Pending: the exact CIDRs of both blocks.)
3. NTP: the corporate servers the managed firewalls use. -> **Done 2026-09-27 (M1):** the three most-used servers that
   answer, taken from the stored firewall configurations on HOST-A (values never left the host), in
   `/etc/chrony/sources.d/nexus-corporate.sources`; the unreachable Ubuntu NTS pool renamed `.disabled-by-nexus`.
   Clock synchronized (stratum 2). SIEM: later.
4. Maintenance window: any time.
5. SSH/firewall blocks: the VPN block (/19) and the team's virtual-machine block (/27, "32 IP"), given by the PO;
   the values live only in `/etc/nexus/firewall.nft` on HOST-A.
   -> **Done 2026-09-27 (H4):** `/etc/ssh/sshd_config.d/10-nexus-hardening.conf` -- root login off, password login off
   except for `securitynexus` (password-only today; key-only once it has a key), no X11/TCP/agent forwarding,
   MaxAuthTries 3, idle timeout, `AllowUsers aiadmin securitynexus`.
   -> **Done 2026-09-27 (H2):** nftables table `inet nexus_host` (`deploy/hosta/nexus-host-firewall.nft`, loaded at boot by
   `nexus-host-firewall.service`): inbound 22 from the two blocks only, 80/443 for everyone, pod/overlay traffic and
   loopback allowed, everything else dropped (rate-limited log `nexus-fw-drop`). Applied with a 5-minute automatic
   rollback armed; verified (new SSH, HTTPS 200, HTTP 301, 6443/10250 closed from outside, pods and services healthy)
   before the rollback was cancelled.
