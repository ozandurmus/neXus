# PO Decision Record — 2026-11-15 — Spring Security migration decisions D1-D5 and response hardening

## Status

**RATIFIED — PRODUCT OWNER DECISION, 2026-11-15.** Decides the open questions of
`docs/design/SPRING_SECURITY_MIGRATION_PLAN_2026_11_15.md` section 5 and three response
hardening items found while characterizing the HTTP surface.

## Decision

The Product Owner decided, in the PO session on 2026-11-15, that D1 through D5 are all
adopted as recommended in plan section 5:

1. **D1 (Q18) ADFS group claim and role mapping.** The ADFS group claim attribute is
   configurable (default `http://schemas.xmlsoap.org/claims/Group`). Group to role mapping
   lives inside neXus, as the LDAP mapping does today.
2. **D2 Actor fingerprint namespace.** LDAP and SAML identities stay separate, with
   mechanism-prefixed opaque fingerprints (for example `saml:adfs:` plus the normalized
   name id). No cross-protocol equivalence is inferred.
3. **D3 LDAP after ADFS.** LDAP stays configurable beside ADFS and can be disabled once
   ADFS is verified in production.
4. **D4 Local accounts when SAML or LDAP is active.** In `BREAK_GLASS_ONLY` local mode only
   `nexusadmin` and `aiview` may sign in interactively. `claudeadmin` is off.
   `aiview-e2e` is machine-only (port 8086 machine sessions).
5. **D5 (Q13) SAML SP key delivery.** The SP signing/decryption key comes from file paths
   or from encrypted settings.

## Response hardening (decided the same day)

- Security response headers are on for every response: `X-Content-Type-Options: nosniff`,
  `X-Frame-Options: DENY`, and `Cache-Control: no-store` on API and sensitive responses.
  Static asset caching stays as it is today.
- Logout (`POST /session/logout`) requires the CSRF token.
- `HEAD /healthz` answers like `GET /healthz` (200).

## Consequences

- The plan's Steps 3-5 are no longer blocked on D1-D5.
- The headers, logout CSRF and `HEAD /healthz` change are made deliberately and the HTTP
  characterization golden is updated only for those intended differences.
