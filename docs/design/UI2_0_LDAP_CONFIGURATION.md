# UI 2.0 LDAP Configuration

**Status:** FROZEN

## 1. Introduction
The Product Owner requested moving the LDAP configuration from static `application.properties` to a database-backed `directory_profiles` table, exposed via a user interface. This document outlines the architectural changes required to fulfill this request.

## 2. Current Architecture
Currently, `DirectoryAuthenticationConfiguration.java` reads properties like `ui2.ldap.profile-id`, `ui2.ldap.host`, `ui2.ldap.port`, and trust material paths directly from the Spring `Environment` to instantiate a single `DirectoryProfile` bean. If no profile ID is present, LDAP is disabled.

## 3. Proposed Architecture

### 3.1 Database Schema
We will introduce a `directory_profiles` table to store the configuration.
- `id` (Primary Key, UUID)
- `profile_name` (String, unique)
- `host` (String)
- `port` (Integer)
- `transport` (String, enum mapping to `DirectoryProfile.Transport`)
- `trust_format` (String, enum mapping to `DirectoryProfile.Format`)
- `trust_material_path` (String)
- `bind_dn_template` (String)
- `group_search_base_dn` (String)
- `access_group_reference` (String)
- `is_active` (Boolean)

**Security Invariant:** Secrets such as the `store-pin` or any bind passwords must be encrypted at rest and MUST NEVER be exposed to the browser/UI in raw text (in accordance with the repository privacy gate and AGENTS.md rules on secrets). 

### 3.2 Backend Changes
- **Deprecate Properties:** The hardcoded `ui2.ldap.*` properties in `application.properties` will be deprecated.
- **Dynamic Configuration Service:** `DirectoryAuthenticationConfiguration.java` will be refactored to rely on a `DirectoryProfileRepository` instead of the static `Environment`. The system will support loading active profiles from the database at runtime or during a configuration refresh.
- **API Endpoints:** Introduce REST endpoints (`/api/v2/config/ldap`) for CRUD operations on directory profiles. The API must redact sensitive fields on read.

### 3.3 UI Implementation
- A new LDAP Configuration screen in the UI to list, create, edit, and delete directory profiles.
- Form fields will map to the DB schema.
- Password/PIN fields will be write-only (empty on load, only updated if the user enters a new value).

## 4. Resolved Questions
- **Trust Material Handling:** The user inputs the trust material (e.g. PEM certificate) directly via the UI, which is stored securely in the database (`trust_material_pem` column).
- **Multiple Profiles:** The system will support exactly one active directory profile at a time for this phase.
- **Bootstrapping:** The initial administrative user (`nexusadmin`) logs in via the existing local fallback authority (LocalMechanism) to configure the LDAP integration.

## 5. Alignment with AGENTS.md
- **Evidence over assumptions:** All previous UNKNOWNs have been resolved by the Product Owner.
- **Language:** English by default.
- **No secrets in browser:** Explicitly declared in section 3.1.
