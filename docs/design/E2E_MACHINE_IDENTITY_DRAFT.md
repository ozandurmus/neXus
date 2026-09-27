# End-to-end screen tests without a human login: an in-cluster machine identity (DRAFT)

**Status:** DRAFT -- proposal for Product Owner review; not implementation authority.

Product Owner, 2026-09-27: the e2e screen suite (`ui2/frontend/e2e`, `docs/reference/E2E_SCREEN_TESTS.md`) needs a
human aiview login each day, and running it as `aiview` signs the PO out (one session per identity). "User
gereksinimi olmadan senin yapabildiğin bir şey olması bu servisi daha mantıklı kılmaz mı?" -- yes, with one boundary:
an AI agent does not authenticate to a non-local system by entering passwords or tokens. So the suite must run where
the system itself authenticates it: inside the cluster, as a machine identity whose secret no person or agent handles.

## 1. Proposal
1. **Identity.** `aiview-e2e` (seeded 2026-09-27 with `role:viewer` + `role:replay_viewer`, random password nobody
   knows). It never logs in with a password.
2. **Machine login, internal only.** A new endpoint `POST /internal/machine-session` served on a second service port
   (e.g. 8086) that the Ingress does not route; reachable only through a ClusterIP Service inside the `ui2`
   namespace, and a NetworkPolicy admits only pods labelled `app.kubernetes.io/component=e2e`.
   - Proof: a 256-bit random token in a Kubernetes Secret (`ui2-e2e-machine-token`), mounted into the service (as a
     SHA-256 digest) and into the e2e Job (plain). Created by `hosta_deploy.sh` if absent; rotatable by deleting it.
   - Result: an ordinary session for `aiview-e2e`, absolute lifetime 30 minutes, no idle exemption.
3. **Server-enforced limits for machine sessions** (a `machine=true` flag on the session row):
   - the role set is exactly viewer + replay_viewer (refused otherwise);
   - GET/HEAD only -- every other method is refused by the gate chain before any controller (403
     `MACHINE_SESSION_READ_ONLY`), including POST routes aiview can use today (collect, backup);
   - the replay-viewer masking applies as for aiview;
   - login and every refusal are written to the audit log with actor `aiview-e2e (machine)`.
4. **Runner.** A Kubernetes Job (`ui2-e2e`), run by `hosta_deploy.sh` after each rollout and by a CronJob every 4 h.
   It obtains the session, writes Playwright `storageState` in its own memory/tmpfs, runs the suite against the
   in-cluster service URL, and prints the Playwright list report to its log (no screenshots/traces, as today).
   `standalone_orchestrate.py ship` reads that log and fails loudly when a test fails.
5. **Privacy check that works with masking.** The masker maps real addresses to synthetic `10.x` ones, so "no RFC 1918
   address on screen" cannot tell a leak from a masked value. Replace it with a canary check: at deploy,
   `hosta_deploy.sh` writes a Secret `ui2-e2e-canary` holding SHA-256 digests of every real management/interface
   address and serial in the database; the suite hashes every IPv4/serial-shaped token it sees and fails on a match.
   Only digests leave the database; the runner never sees a real value.

## 2. The browser inside the cluster
The runner uses the official Playwright image `mcr.microsoft.com/playwright:v<version>-noble` (version = the
`@playwright/test` in `ui2/frontend/package.json`, pinned by digest). HOST-A reaches the Microsoft registry through
the corporate proxy that k3s already uses (measured 2026-09-27: `mcr.microsoft.com/v2/` answers through the proxy;
an earlier test without the proxy was wrong). At run time the Job talks only to the in-cluster service -- no outbound
traffic. The image is ~1 GB compressed, pulled once per Playwright version.

## 3. Risk
A new credential boundary: a token that yields a session without a password. Blast radius if the token leaks: what
aiview already sees (masked, read-only), from inside the cluster only, for 30 minutes per session. Mitigations above;
rotation by deleting the Secret.

## 4. Questions for the Product Owner
1. Approve the machine identity design (§1)?
2. Cadence: after each deploy + every 4 h?

## 5. Product Owner decision (2026-09-27)
"Devamına ok veriyorum, devam et" -- §1 (machine identity, internal port, server-enforced read-only, audit), §2 (the
official Playwright image through the corporate proxy) and the cadence (after each deploy + every 4 h) are approved
for implementation. The console now serves HTTPS (hardening H1); in-cluster the runner calls the service directly.
