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
