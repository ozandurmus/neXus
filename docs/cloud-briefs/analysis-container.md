Repo github.com/ozandurmus/neXus, branch: git fetch origin cloud-base/1105 && git checkout -b cloud/analysis-container-design-1105 origin/cloud-base/1105. Read AGENTS.md first. No access to the live system.

TASK: an architecture design document only. Read docs/design/PO_DECISION_RECORD_2026_11_05_ANALYSIS_CONTAINER_DIRECTION.md and design a new k8s workload, 'ui2-analysis'. It takes policy analysis (PolicyHygiene, PolicyNormalization, the snapshot cache, the policy query and offline services) and topology/path analysis out of ui2-service.
Study the existing ui2-policy/worker split (deploy/ui2/*.yaml, tools/delivery/module_deploy.py, module_runtime_control), the auth (GateChainInterceptor) and the masking boundary (PrivacyMaskingResponseBodyAdvice).
Write docs/design/ANALYSIS_CONTAINER_DESIGN_2026_11_05.md (DRAFT) covering:
- class moves;
- API options with a recommendation;
- a single masking authority;
- RBAC and audit;
- memory limits;
- deploy, drain and rollback (avoid the drain deadlock class);
- test changes;
- phased migration with a flag;
- risks;
- the live measurements still needed.
Commit (messages end with 'Co-Authored-By: Claude'), git push origin cloud/analysis-container-design-1105, no PR.