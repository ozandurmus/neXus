# Migrate to Spring Boot 4 / Spring Framework 7.0.9+ and Spring Security authentication/authorization; preserve RBAC

status: planned · target: 2026-11-05: CVE-2026-47884 baseline exception review deadline

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Migrate to Spring Boot 4 / Spring Framework 7.0.9+ and Spring Security authentication/authorization; preserve RBAC

Target (full): 2026-11-05: CVE-2026-47884 baseline exception review deadline

2026-11-10 (plan refresh): Spring Boot 4.0.8 / Spring Framework 7.0.9 landed on 2026-10-15 (21553394). What remains is Spring Security: authentication first (SAML 2 via ADFS, the existing LDAP path, one audited local break-glass administrator; enterprise phase 0.2), then the route-to-action authorization map with deny-by-default and audit kept (phase 1.1).

2026-11-14 backlog truth: still open (P1, size L). Today: No SAML/ADFS sign-in or deny-by-default route map yet. Evidence: Boot 4.0.8 landed 10-15; no spring-security/SAML in builds; only LDAP service-bind PR 818 (V204); ENTERPRISE_STACK_ALIGNMENT 0.2, 1.1

2026-11-16 backlog refresh (origin/main db9b2ecd, live through batch 14, schema V242): done through Step 5 of docs/design/SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md (the plan has five steps): 1 passthrough filter chain, 2 session bridge and Local/LDAP providers, 3 break-glass scope (ALL_LOCAL / BREAK_GLASS_ONLY, V236) with audit and alert, plus security response headers, logout requires the CSRF token, 4 SAML 2.0 service provider for ADFS with a test IdP harness (V241, saml_settings OFF by default, no row means SAML off), 5 route-to-action authorization decision moved into NexusRouteActionAuthorizationManager (rollback flag ui2.security.spring-authz-manager.enabled, bake-in release). Remaining: SAML stays off until the ADFS metadata and claim names arrive and a real ADFS sign-in is tried; Step 5 bake-in then removal of the old GateChainInterceptor path; nothing here is REAL_ENV_VALIDATED.
