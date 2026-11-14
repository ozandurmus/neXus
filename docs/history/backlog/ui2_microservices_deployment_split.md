# Split K3s deployment into true independent microservices (ui2-frontend, ui2-service, ui2-worker) without full rollout

status: planned · target:

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Split K3s deployment into true independent microservices (ui2-frontend, ui2-service, ui2-worker) without full rollout

2026-11-14 backlog truth: DONE. Evidence: PRs #521, #526, #598, #699; deploy/ui2 has separate service, worker, configuration, compliance, policy, packet-analysis; module_deploy.py. Modules roll out independently (frontend still bundled in service image)
