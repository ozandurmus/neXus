# PO Decision Record — Security Scan Reporting (2026-11-18)

## Status

`RATIFIED` -- recorded under the Product Owner's standing rule of 2026-11-03 ("never ask; fix blocking rules with a recorded PO amendment").

## Product Owner Instruction

Quoted from Product Owner chat (2026-11-18):

> *"otomatik olarak high ve mediumları günlük bana getirip vermen gerekiyordu"*

(In English: the HIGH and MEDIUM findings were supposed to be brought to the PO automatically, every day.)

## 1. What Is Approved

1. **aiview may read the security scan summary.** `GET /api/v2/security/scan-summary/latest` is on the aiview (`role:replay_viewer`) allow-list. The summary holds scan findings about the platform's own code, images and manifests (tool, rule, location, severity, status). It contains no device data, no customer data and no secrets.
2. **The daily HIGH/MEDIUM list is delivered.** The daily security scan mail carries a bounded list of the HIGH and MEDIUM findings, and the same list is shown on Platform status in a "Security scans" card, so the PO sees them without asking.
3. **Baseline acceptances added in `security/baseline.yaml`** (each carries owner PO and a reason; one line per group):
   - semgrep `missing-user` (history/ Dockerfile): retired Python product, frozen, not built or deployed.
   - semgrep `dangerous-subprocess-use-tainted-env-args` (17 locations): list-argv subprocess calls of a fixed local program in archived, operator-run or developer-run scripts (history/, consultation scripts, packet-analysis parity harness); not shipped, no untrusted input.
   - semgrep `unencrypted-socket` (2): configured syslog forwarding over TCP/UDP without TLS (PO security-baseline brief 2026-09-30) and the internal SMTP relay retained by NOTIFICATION_ROUTING_CONTRACT.md.
   - semgrep `use-of-md5` (1): colon-hex host-key fingerprint label for stored Check Point identities; trust is decided by SHA-256 in HostKeyVerifier.
   - semgrep `weak-ssl-context` (2): the "TLS" context name enables TLS 1.3 and 1.2 only; device HTTPS trust is the pinned leaf with warn-not-refuse, and Palo Alto verification is disabled by PO_DECISION_RECORD_2026_09_21_PAN_TLS_VERIFICATION_DISABLED.md.
   - trivy KSV-0003 / KSV-0004 / KSV-0015 / KSV-0016 / KSV-0018 / KSV-0106 / KSV-01010 (Kaniko build Jobs and similar): build Jobs run as root or keep default capabilities by design for layer extraction; documented exceptions.
   - trivy KSV-0011 / KSV-0012 (no CPU limit; root or fixed-uid): CPU limits throttle latency-sensitive pods; vendor images own their numeric accounts.
   - trivy KSV-0020 / KSV-0021 (no fixed uid/gid, 30 locations): the numeric account is the image's own; a fixed id would change ownership on mounted volumes.
   - trivy KSV-0023 (hostPath): Radware Cyber Controller push inbox, one fixed directory of type Directory.
   - trivy KSV-0037 (kube-system): cluster-internal registry and Traefik HelmChartConfig must live in the namespace k3s runs them from.
   - trivy KSV-0125 (13): images come from the cluster-internal registry or are digest-pinned vendor images, not untrusted registries.
   - trivy registry-pod resource requests/limits: unmeasured figures risk Pending or OOM; set once measured.
   - trivy ConfigMap secret-word finding: the database-initialisation script mentions PASSWORD and binds environment values; no credential is stored.

## 2. What Is NOT Approved

1. aiview receives no device data through this route; the summary is not extended with device identifiers.
2. Acceptances above do not silence new findings: any new HIGH/MEDIUM finding not in the baseline appears in the daily list.
