# PO decision record 2026-09-24 — HOST-A becomes neXus's own host; the agent works there with sudo

## Status

**RATIFIED — PRODUCT OWNER, 2026-09-24**, in session: "Artık lvm'ye gerek yok bütün sunucu bizim olacak 1 tb" and
"Bence sudo alabilirsin, hatta nexus'a özel bir user yaratmayı öneriyorum sudo yetkili. Sadece bazı komutlar için ön
onay isteyeceğim mesela rm gibi, yada bu sunucuyu jump server olarak kullanıp başka yere erişmeye çalışmak gibi. bunlar
olmamalı." **Takes effect when HOST-A is reinstalled with no other product on it** (HOST_A_REBUILD_RUNBOOK.md). Until
then the 2026-09-19 amendment (no sudo, incumbent untouched) stands.

## Why

A co-hosted product filled the shared root disk repeatedly on 2026-09-24 (~6–8 GB/min of container logs) and took
neXus down twice; the host is being reinstalled for neXus alone. On a host neXus owns, the rule "no agent holds sudo on
a host it does not own" (AGENTS.md, Host action boundary) no longer applies by its own terms, and waiting on the
Product Owner for every privileged step of an install or a fix only slows recovery.

## Decision

1. **Account.** A dedicated neXus agent account on HOST-A, in the sudo group, reached with the agent's own SSH key.
   Its name and key live in the operator's store, never in the repository (HOST_REGISTER.md).
2. **What sudo is for.** Installing and maintaining neXus and the host it runs on: packages and updates, k3s and its
   configuration, storage and mounts, the SFTP receiver accounts and `sshd` blocks for pushing products
   (e.g. `nexus-cc`), firewall on the host, service restarts, logs.
3. **Ask first, in chat, and wait for a clear yes** — for any command that deletes or irreversibly changes data:
   `rm` of any kind, `truncate`, `shred`, `dd`, `mkfs`, partitioning or `lvremove`/`vgremove`, dropping or truncating
   database objects, `kubectl delete` of a PersistentVolumeClaim, PersistentVolume or namespace, deleting backups or
   artefacts, and a host reboot. The request names the exact command and what it removes.
4. **Never: HOST-A as a jump server.** The agent does not log in to, authenticate to, tunnel to, proxy through, or
   forward ports to any other system from HOST-A (no `ssh`/`scp`/`sftp`/`rdp` onward, no `-L`/`-R`/`-D` forwarding,
   no SOCKS or HTTP proxy for its own use). What stays allowed: the product's own device traffic (the worker's gated
   jobs) and the non-mutating reachability probes of the 2026-09-19 amendment (`ping`, `nc -z`, `traceroute`, `curl`
   status, SSH banner) toward managed devices, used to diagnose a product failure.
5. **Every sudo command is recorded** on the host (`Defaults logfile=/var/log/sudo.log, log_input, log_output` for the
   agent account), so what was run can be read back.
6. Unchanged: secrets never printed or copied off the host outside a Product Owner request; the product's device
   command gate, the network action taxonomy and every data-handling law apply as before.
