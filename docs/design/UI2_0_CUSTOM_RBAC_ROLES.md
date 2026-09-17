# UI 2.0 Custom RBAC Roles

**Status**: DRAFT

## 1. Overview
This document outlines the architectural design for moving from a closed vocabulary `RoleToken` system to a dynamic, database-backed Custom Role-Based Access Control (RBAC) system in UI 2.0.

## 2. Requirements
- Replace hardcoded roles (e.g., `ROLE_ADMIN`, `ROLE_USER`) with dynamic roles stored in the database.
- Introduce granular permissions (privileges) that can be assigned to custom roles.
- Allow administrators to create, update, and delete custom roles via the UI.
- Assign users to one or more roles.
- Enforce permissions at the API and UI levels.

## 3. Architecture

### 3.1 Database Schema
Create new tables:
- `permissions`:
  - `id` (Primary Key)
  - `name` (String, e.g., `READ_USERS`, `WRITE_SETTINGS`)
  - `description` (String)
- `roles`:
  - `id` (Primary Key)
  - `name` (String, unique)
  - `description` (String)
  - `is_system` (Boolean - to prevent deletion of default roles like Admin)
- `role_permissions` (Join Table):
  - `role_id` (Foreign Key)
  - `permission_id` (Foreign Key)
- `user_roles` (Join Table):
  - `user_id` (Foreign Key)
  - `role_id` (Foreign Key)

### 3.2 Backend Implementation
- **Entities & Repositories**: Create entities for `Role` and `Permission`.
- **Spring Security Integration**:
  - Implement a custom `UserDetailsService` that loads a user's roles and their associated permissions.
  - Map permissions to Spring Security `GrantedAuthority` (e.g., `AUTHORITY_READ_USERS`).
- **Annotations**: Use `@PreAuthorize("hasAuthority('...')")` on controller methods instead of `@PreAuthorize("hasRole('...')")`.
- **Service Layer**: `RoleService` for CRUD operations on roles, ensuring `is_system` roles cannot be modified or deleted improperly.

### 3.3 Frontend Implementation
- **React Components**:
  - `RoleManagementView`: A data grid listing all roles.
  - `RoleEditor`: A form to edit a role's details and a checklist/transfer list to assign permissions.
- **UI Authorization**:
  - Implement a `usePermissions` hook to check if the current user has specific permissions.
  - Use an `Authorized` wrapper component to conditionally render UI elements (menus, buttons) based on permissions.

## 4. Migration Strategy
- **Database Migration**: Flyway/Liquibase scripts to create the new tables.
- **Data Seeding**: Insert base permissions and default roles (Admin, User).
- **Data Migration**: Map existing user `RoleToken`s to the new `user_roles` relationships during a migration phase.

## 5. Security Considerations
- Ensure that the endpoint to modify roles/permissions is strictly guarded by a high-level permission (e.g., `MANAGE_ROLES`).
- Prevent modification of the user's own roles to avoid privilege escalation.

