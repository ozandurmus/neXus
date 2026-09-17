# LDAP Configuration Menu Architecture

## Status

**FROZEN**

## Context
Currently, LDAP directory settings are configured statically via `application.properties`. As part of the UI2.0 initiative, the Product Owner has requested moving these settings to a DB-backed configuration menu, allowing administrators to dynamically configure LDAP connections without restarting the application.

## Goals
1. Provide a persistence model for storing LDAP connection parameters dynamically in the database.
2. Ensure secure storage of sensitive LDAP information (e.g., bind credentials).
3. Provide a unified backend API for the UI to read, write, and test LDAP configurations.

## Architecture

### 1. Data Model
A new entity, `LdapConfiguration`, will be introduced to persist connection settings.

```sql
CREATE TABLE ldap_configurations (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    url VARCHAR(512) NOT NULL,
    base_dn VARCHAR(255) NOT NULL,
    bind_dn VARCHAR(255),
    bind_password_encrypted VARCHAR(1024),
    user_search_base VARCHAR(255) NOT NULL,
    user_search_filter VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
```

### 2. Encryption of Bind Password
Sensitive fields, specifically `bind_password_encrypted`, must be encrypted at rest using the application's existing cryptographic envelope configuration. The raw password will never be sent back to the frontend on reads; the API will return a masked placeholder (e.g., `********`).

### 3. Service Layer and Connection Testing
A new `LdapConfigurationService` will manage CRUD operations for `LdapConfiguration`. It will include a `testConnection()` method that attempts a bind operation using the provided or saved credentials before marking a configuration as active.

### 4. Dynamic Authentication Provider
The authentication manager will be updated to query the database-backed `LdapConfiguration` instead of relying on the static Spring `Environment`. A cache (e.g., caffeine or Hazelcast) should be used to store parsed LDAP contexts to avoid database round-trips on every login attempt. Cache invalidation will be triggered upon any update to the configuration.

## Migration Path
1. Schema migration via Flyway to create `ldap_configurations`.
2. One-time boot migration script to copy existing valid `application.properties` LDAP settings into the database.
3. Update the frontend to include the new "LDAP Settings" administration panel.
