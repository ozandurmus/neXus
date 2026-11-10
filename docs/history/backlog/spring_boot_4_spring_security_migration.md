# Migrate to Spring Boot 4 / Spring Framework 7.0.9+ and Spring Security authentication/authorization; preserve RBAC

status: planned · target: 2026-11-05: CVE-2026-47884 baseline exception review deadline

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): Migrate to Spring Boot 4 / Spring Framework 7.0.9+ and Spring Security authentication/authorization; preserve RBAC

Target (full): 2026-11-05: CVE-2026-47884 baseline exception review deadline

2026-11-10 (plan refresh): Spring Boot 4.0.8 / Spring Framework 7.0.9 landed on 2026-10-15 (21553394). What remains is Spring Security: authentication first (SAML 2 via ADFS, the existing LDAP path, one audited local break-glass administrator; enterprise phase 0.2), then the route-to-action authorization map with deny-by-default and audit kept (phase 1.1).
