# In-host security scans

Repository implementation only. No scanner digest, image pull, Kubernetes change or authenticated scan was
verified from this offline lane. Rendering refuses placeholders and mutable image tags. The deployment reviewer
must supply **reviewed upstream digests**, run the mirror step and verify the resulting containers before enabling
the schedules. Do not replace the digest checks with tag defaults.

## Prepare on the owned host

1. Keep the existing `ui2-build` Kaniko infrastructure and registry. Copy `config.example.json` and
   `sources.example.json` to private host configuration, replacing every placeholder locally. Proxy values and
   node/service addresses never belong in Git. The four scanner images must supply Semgrep/Python 3, gitleaks
   (`dir` and `git` commands), Trivy, and `zap-baseline.py` respectively, with non-root execution supported.
2. Run `python3 tools/security/mirror_security_images.py --sources <private-sources.json> --config <private-security.json>`.
   This builds a one-line `FROM image@digest` with the **same pinned Kaniko builder** in `ui2-build`, pushes only to
   the local registry, and writes the resulting digest references to the private config atomically. Builder jobs
   retain the existing Kaniko root/writable-filesystem exception; scanners do not inherit it. Registry reads by
   scanners need no Kubernetes RBAC token. No external registry credentials are embedded in manifests.
3. Cache the exact Semgrep `p/java`, `p/typescript`, `p/owasp-top-ten`, `p/secrets` packs as `java.yaml`,
   `typescript.yaml`, `owasp-top-ten.yaml`, `secrets.yaml` in `~/.config/nexus/security-rules/`. Obtain them through
   the approved proxy on the host, review their provenance/content and keep them current. Daily scans never fetch
   rules or upload source; metrics/version checks are off. Missing packs fail closed. Trivy database updates use
   the configured proxy; local image reads bypass it. Apply the corporate proxy's database/registry allowlist.
4. Pre-create `/var/lib/nexus-security` (owner/group 1000, mode 0700) and its `notifications` child (1000:1000,
   mode 0750). The reports PVC binds a retained local PV to the configured node. It stores normalized JSON and
   per-image CycloneDX SBOMs for 90 days; raw scanner snippets stay in memory-backed temporary volumes and are
   not retained. Size the 30 GiB report and 20 GiB source claims for the local repository/history.
5. Save the private config as `~/.config/nexus/security.json`. Render and apply:

   ```sh
   python3 tools/security/security_manifests.py --config "$HOME/.config/nexus/security.json" > /tmp/security-manifests.json
   kubectl apply -f /tmp/security-manifests.json
   python3 tools/security/security_host.py snapshot --config "$HOME/.config/nexus/security.json" --rules "$HOME/.config/nexus/security-rules"
   ```

   The renderer emits namespace, ServiceAccount, source/report claims, PV, NetworkPolicy, script ConfigMap, daily
   and weekly CronJobs. All scanner/loader containers are digest pinned, non-root, capability-free, without
   privilege escalation and with read-only root filesystems. Source mounts are read-only except the bounded loader.
   There are **no scanner RoleBindings and no mounted API tokens**. A host-side preparer reads `ui2` Deployments,
   refusing nonlocal or unpinned images, then streams committed source and Git history into the source PVC using
   the image builder's `tar | kubectl exec` mechanism. Remote Git config, hooks and working-tree debris are excluded.
   The Job refuses inventory older than two hours. Ship also checks source commit against the build commit.
   Snapshot refreshes are serialized; unused source snapshots age out after two days, retaining the current and
   recently replaced snapshot for running Jobs (whose deadline is one hour). Reports retain their full 90 days.
6. Install the two `security-source-refresh.*` files as **user systemd units** on the same host, enable its user
   timer (and user lingering as appropriate). Refresh is at 02:15 Europe/Istanbul, daily scan at 02:30, weekly DAST
   Sunday at 03:30. Snapshot failures are visible in that unit's status; scanner failure is never a clean result.
   This host preparation is intentional: it preserves the brief's no-API-rights ServiceAccount boundary while
   obtaining image digests from Deployments. Do not grant the scanners cluster-wide read rights.
7. Apply `deploy/ui2/56-service-internal.yaml`: it adds internal HTTP alongside machine-session port 8086 and admits
   only the scanner namespace/label to the latter. Copy the existing e2e token through the host's approved secret
   handling into `security-machine-token` (`token` key) in `ui2-security`; never print it or commit it. Only the ZAP
   container mounts it. The existing endpoint enforces the `aiview-e2e` read-only role set. ZAP verifies the session,
   injects its cookie only for the internal UI, disables form processing and runs **baseline only**, without an
   active or AJAX scan. SPA/API coverage remains limited to what the baseline spider reaches; review coverage on-host.
8. Apply `notification-mount.patch.yaml` as a strategic merge patch to `ui2-service` after migration V113. The service
   sees only read-only counts files; supplemental group 1000 reads those 0640 files, not raw reports. Keep the
   service on the same node as this local PV. Enable **Administration > Platform > Notifications > Security scans**
   and its recipients. Existing relay routing, per-type watermark and retry behavior are reused.
9. Synchronize the canonical `deploy/ui2-image-build/run_build.sh` to the host's existing `~/run_build.sh` before the
   next ship. That script prepares the source, builds, reads the new digest, runs a unique `security-gate-*` Job,
   waits for its terminal result, prints validated counts, and only then reaches the first rollout command.
   Failure/timeout/missing summary blocks rollout. `standalone_orchestrate.py ship --skip-security "reason"` is the
   explicit emergency bypass; the reason is printed and retained in deployment logs. Build/ship remain reviewer actions.

NetworkPolicy permits only the proxy IP/port, registry pods and internal service pods. Static host aliases avoid
a DNS or API egress exception; refresh the private config/rendered manifests if either ClusterIP changes. There is
no device route. Kubernetes policy enforcement, proxy allowlists, local PV ownership, scanner command compatibility,
authenticated DAST, report retention and SMTP delivery all require host validation before declaring this operational.

## Triage

`security/baseline.yaml` uses JSON syntax, a YAML 1.2 subset, so the summarizer needs only Python's standard library.
All pre-existing acceptance records are preserved. Each acceptance requires tool, exact rule/CVE, exact normalized
location, reason, owner and inclusive review date; history acceptances may also bind a commit. Expired records no
longer suppress findings. Never fuzzy-match a rule name, file suffix or package name to make a finding disappear.
Review scanner-native identities before accepting any additional finding; older manually recorded locations may
not match scanner-native locations and therefore remain blocking until explicitly triaged.

Summary trend compares the latest same-mode run from yesterday; ship runs never replace daily history. Accepted
findings are counted separately. All unaccepted HIGH/CRITICAL (and unknown severity) findings block the gate,
including yesterday's still-untriaged findings. Scanner errors suppress false `fixed` counts and block the gate.
The first run treats every unaccepted finding as new. The summary never includes snippets, secret values or paths.
