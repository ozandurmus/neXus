# Probes displace the e2e runner's machine session (one session per token): use a separate probe machine identity or allow concurrent machine sessions

status: planned · target:

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Probes displace the e2e runner's machine session (one session per token): use a separate probe machine identity or allow concurrent machine sessions

2026-11-14 backlog truth: still open (P2, size M). Today: Diagnostic probes can break a running deploy e2e. Evidence: One machine session per token; probes still displace the e2e runner (known rule: no probes during deploy e2e)

2026-11-14: in ship batch 3.

2026-11-15 10:00 backlog refresh: Shipped on main (batch 2/3). Commits 'Isolate diagnostic probe machine sessions from e2e test sessions' and 'Probe machine session: end it when the identity is disabled'; MachineSessionControllerTest and LocalIdentityAdministrationTest cover it. Not yet exercised by a probe run beside a live deploy e2e; the no-probes-during-deploy-e2e rule stays until that is observed.
