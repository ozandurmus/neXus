# UI 2.0 LDAP Configuration

**Status**: DRAFT

## 1. Overview
This document outlines the architectural design for the LDAP Configuration Menu in UI 2.0. The goal is to move from static `application.properties` based LDAP configuration to a dynamic, database-backed configuration system that can be managed via the UI.

## 2. Requirements
- Store LDAP connection settings (URL, bind DN, credentials, search base, etc.) in the database.
- Provide a secure UI menu for administrators to configure LDAP settings.
- Support runtime updates of the LDAP connection without restarting the application.
- Securely store credentials (e.g., encrypted in the database).

## 3. Architecture

### 3.1 Database Schema
Create a new table `ldap_configuration`:
- `id` (Primary Key)
- `url` (String)
- `bind_dn` (String)
- `bind_password` (Encrypted String)
- `user_search_base` (String)
- `user_search_filter` (String)
- `group_search_base` (String)
- `group_search_filter` (String)
- `enabled` (Boolean)
- `created_at` (Timestamp)
- `updated_at` (Timestamp)

### 3.2 Backend Implementation
- **Entity & Repository**: Create `LdapConfiguration` entity and `LdapConfigurationRepository`.
- **Encryption Service**: Use a symmetric encryption utility to encrypt and decrypt the `bind_password` before saving/reading from the DB.
- **Service Layer**: `LdapConfigurationService` to handle CRUD operations and trigger context refresh.
- **Security Configuration**: 
  - Implement a custom `AuthenticationProvider` or dynamic `LdapContextSource` that reads from the database.
  - Listen for configuration changes to re-initialize the `LdapContextSource` at runtime.

### 3.3 Frontend Implementation
- **React Components**:
  - `LdapConfigForm`: Form for updating LDAP settings. Includes fields for URL, Bind DN, Password, Search Bases, and Filters.
  - **Test Connection**: A button to test the LDAP connection with the provided settings before saving.
- **State Management**: Redux/Context to manage configuration state.

## 4. Security Considerations
- The LDAP bind password must NEVER be sent back to the frontend in plain text. Send a placeholder (e.g., `********`) and only update it if the user provides a new value.
- Ensure the API endpoints for managing LDAP settings are restricted to users with the `ADMIN` role or equivalent custom permission.

## 5. Migration Strategy
- Provide a Flyway/Liquibase script to create the new table.
- Implement a startup task to migrate existing `application.properties` LDAP settings to the database if the table is empty.

