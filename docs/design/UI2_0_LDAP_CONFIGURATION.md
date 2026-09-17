# LDAP Configuration Menu (DRAFT)

## Overview
Moving LDAP configuration from `application.properties` to a DB-backed directory settings menu in UI 2.0.

## Architectural Decisions
- Use `DirectorySettings` entity mapped to DB to store LDAP configuration parameters.
- Provide CRUD REST API for UI 2.0 to manage LDAP configurations.
- Reload Spring Security `LdapAuthenticationProvider` dynamically when settings change.
- Encrypt sensitive fields (bind password) at rest.

## Endpoints
- `GET /api/v2/config/ldap`
- `PUT /api/v2/config/ldap`

## UI Components
- Settings form with validation for LDAP URL, Base DN, Bind DN, etc.
- Connection test button to verify LDAP connectivity before saving.

## Security
- Requires `ROLE_ADMIN` (or new custom equivalent) to view/modify.
