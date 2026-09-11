# op2_c_release_gate_dependency_scoping — OP.2.C release-gate scoping - dependency order and smallest actionable next build

## Summary

Read-only ARCHITECTURE/RELEASE-GATE-SCOPING pass classifying the four remaining OP.2.C production-reachability gates (DEPLOY.1A OIDC+RBAC OPERATE, production CP SSH host-key trust hardening, the signed change-management/network-security review, the protected production entry point + live adapter_resolver construction) against repository evidence. Found: DEPLOY.1A is externally blocked on DEPLOY.1 server availability; the SSH trust hardening item's own backlog.json target field ('post-DEPLOY.1') shows it shares that same external blocker rather than being independently closable; the protected entry point/adapter_resolver is dependent on both landing first and stays out of scope for any build before then; the change-management/network-security review has no drafted artifact anywhere in the repository (unlike its sibling OP.2.1 network-device command gate, already DRAFTED), and drafting that package -- not signing it -- is the one item in the set that is engineering-actionable now with zero code, server, or device contact. No code, taxonomy, Authorizer, adapter, UI, or device command touched or added.

## Evidence

Read-only session: AGENTS.md, AI_START_HERE.md, CURRENT_STATE.md, project/roadmap.json, project/backlog.json, project/feature_registry.json, docs/design/FAILOVER_ENGINE_ARCHITECTURE.md section 10, utils/operate/authorization.py, checkpoint/clusterxl_capability_adapter.py, checkpoint/clusterxl_member_session.py, checkpoint/clusterxl_preflight_provider.py docstrings, and a repository-wide search confirming no utils/operate/adapter_resolver.py or production ActionCoordinator construction exists outside tests/. No test suite run (no code changed).

## Risks forward

The change-management review package's own future draft must not claim DEPLOY.1A, SSH trust hardening, or the protected entry point are any closer to done than DenyAllAuthorizer/no-taxonomy-member already make them -- its acceptance criteria say so explicitly (project/roadmap.json now_next.upcoming op2_c_change_management_review_package_draft).
