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
   -> **Done 2026-09-27 (H3):** `secrets-encryption: true` in `/etc/rancher/k3s/config.yaml`, keys rotated and every
   Secret re-encrypted (aescbc); verified no Secret value appears in clear in the datastore. A pre-change copy of the
   datastore (plaintext) was taken for rollback and deleted on the PO's instruction the same day.
6. Backup (PO: "Silinsin ve şifreli bir backup alalım çalışan haliyle"). -> **Done 2026-09-27:**
   `deploy/hosta/nexus-platform-backup.sh` (installed `/usr/local/sbin/nexus-platform-backup`, daily timer 01:30 UTC,
   14 kept in `/var/backups/nexus`): k3s datastore (consistent sqlite copy), k3s cred incl. the Secrets encryption
   config, tls, token, k3s config, `/etc/nexus` (local CA, firewall), sshd/chrony drop-ins and a `pg_dump` of ui2,
   encrypted with age. First run 198 MB, decryption verified. The age identity is on HOST-A (root-only) and a copy was
   handed to the PO to keep off the host. The artefact store (117 GB, already encrypted by neXus) needs an off-host
   target -- PO to name it.
7. Host audit (PO: "Sunucu düzeyindekini açalım"). -> **Done 2026-09-27 (M2):** auditd with
   `deploy/hosta/nexus-audit.rules` (identity, sudoers, sshd, k3s config/cred/manifests, `/etc/nexus` read/write,
   chrony, systemd units, root commands run by logged-in users); ~40 MB/day, rotated by auditd.
8. SSH keys: unchanged by the PO's decision -- `securitynexus` keeps its password login.

## 6. Vulnerability review (2026-09-27, in-host only; tools installed temporarily and removed)
Scope rule from the PO: every scan runs on HOST-A against HOST-A; nothing scans the network. (An earlier TCP connect
sweep from the VPN was stopped at the PO's request.)
- Lynis hardening index 69, no warnings, 41 suggestions (open items: unused protocols dccp/sctp/rds/tipc, sysctl,
  AIDE, fail2ban, legal banner, process accounting).
- testssl (127.0.0.1): TLS 1.2/1.3 only, P-256. **Fixed:** CBC suites removed (Traefik `tlsOptions.default`, AEAD only).
- Trivy: Ubuntu -- 82 kernel CVEs, no fix released yet. **neXus image: 105 HIGH / 7 CRITICAL, fixed** by Spring Boot
  3.5.16, Tomcat 10.1.60, Jackson 2.21.4, pgjdbc 42.7.13 and refreshed UBI9 bases (Java 21.0.12). Open: k3s bundled
  components (Traefik, CoreDNS, metrics-server, helm, local-path), the local registry and the build executor image.
- kube-bench (k3s CIS): 47 pass / 8 fail / 52 warn; the kubelet/apiserver FAILs verified false positives (anonymous
  401, read-only port closed). Open: `protect-kernel-defaults`.
- Exposure: Traefik NodePorts were reachable from outside (DNAT bypasses the input chain). **Fixed:** prerouting drop
  of 30000-32767 on the outside interface. Port 53 seen from the VPN is not served by HOST-A (resolved listens on
  loopback only; kube DNS rules target the service VIP) -- most likely the VPN's DNS interception.
- RBAC: `aiview` holds operator, onboarding_admin, backup_admin and compliance_admin besides viewer/replay_viewer, so it
  can start collections and backups (authorized, not a bypass). **PO decision 2026-09-27: keep** -- during development
  the agent uses aiview to test end to end on its own ("senin çalışman değerli, kendi kendine test yapabiliyorsun").
  Revisit before production.
- **Done 2026-09-27 (Lynis/kube-bench follow-up):** kernel sysctl hardening + the kubelet-required values
  (`deploy/hosta/60-nexus-hardening.sysctl.conf`), k3s `protect-kernel-defaults: true` (verified on the running
  process), unused protocols dccp/sctp/rds/tipc blocked (`nexus-unused-protocols.modprobe.conf`), fail2ban sshd jail
  (5 tries / 10 min -> 15 min ban, nftables). k3s is already the newest stable (v1.36.4); its bundled components'
  CVEs wait for the next stable release. Open: AIDE, legal banner text, local registry and build executor images.
- **Done 2026-09-27:** legal banner (`deploy/hosta/nexus-issue.net` -> /etc/issue, /etc/issue.net, sshd `Banner`); the
  managed firewalls carry no custom banner text to reuse (Check Point banner on with the vendor default, FortiGate
  off, ASA none), so a neutral EN/TR text without the company name. AIDE with `deploy/hosta/nexus-aide.conf`
  (OS and configuration; k3s/container, log, backup and build paths excluded), daily check by `dailyaidecheck.timer`.
  Local registry 2.8.3 -> 3.x pinned by digest (`deploy/ui2-image-build/05-registry.yaml`); pull and push verified by
  a full deploy. Build executor (kaniko, archived upstream): accepted risk for now -- runs only during builds, in-cluster.
