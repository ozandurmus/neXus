# PO decision record 2026-09-30: secure SDLC checks built in, run daily, tools stay installed

**Status:** RATIFIED -- Product Owner, 2026-09-30 (chat): "İş bitince kaldır olmasın, SDLC süreçlerini direkt dahil
edelim ve günlük olarak kontrolden geçecek şekilde otomatize edebilir miyiz?"

## Context
Measured 2026-09-30: the repository has supply-chain integrity (Gradle lockfiles + `verification-metadata.xml`), the
privacy/DLP gate, three architecture security tests and ~300 test classes (RBAC, masking, CSRF, command gates). It
has no SAST, no continuous dependency/image vulnerability scanning, no secret scanning for keys, no SBOM, no IaC
scanning and no DAST; the 2026-09-27 host scan (Trivy, Lynis, testssl.sh, kube-bench) was a one-off and its tools
were removed afterwards by the PO's rule of that day, which this decision replaces.

## Decision
1. The security scanners stay installed on HOST-A permanently, as pinned container images in the local registry
   (digest-pinned, like every other neXus image), run by Kubernetes Jobs/CronJobs in their own namespace.
2. **Daily** (night, after backups), in-host only -- targets are the repository checkout on HOST-A, the neXus images in
   the local registry, the Kubernetes manifests and the application on 127.0.0.1; no scan traffic leaves HOST-A.
   Outbound traffic is limited to vulnerability-database updates through the corporate proxy.
   - SAST: Semgrep (Java, TypeScript, security rule packs) -- SpotBugs + FindSecBugs optional later.
   - Secrets: gitleaks over the working tree and git history.
   - Dependencies: Trivy filesystem scan of Gradle lockfiles and `package-lock.json`.
   - Images: Trivy image scan of the running service/worker/ui images + an SBOM (CycloneDX) per image digest.
   - IaC: Trivy config scan of `deploy/`.
   - DAST: OWASP ZAP baseline against the UI on 127.0.0.1, authenticated with the read-only e2e machine identity
     (weekly first; daily once its run time is known).
3. **Ship gate:** every release (`standalone_orchestrate.py ship`) runs SAST, secrets, dependency and image scans on
   the new build; a **new** HIGH/CRITICAL finding (not in the accepted baseline) stops the rollout.
4. **Baseline and triage:** today's findings form the baseline; each accepted finding carries a reason and an owner;
   a daily summary (new / fixed / accepted counts, no secret values) goes to the security notification route, and the
   full reports are kept on HOST-A for 90 days.
5. A one-page mapping to NIST SSDF (SP 800-218) records which practice each control covers and what is still open
   (threat modelling, security requirements, vulnerability SLA).
