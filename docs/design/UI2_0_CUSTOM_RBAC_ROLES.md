# Custom RBAC Roles Architecture

## Status

**DRAFT**

## Context
The platform currently utilizes a closed vocabulary `RoleToken` for Role-Based Access Control (RBAC) (e.g., hardcoded roles like `ADMIN`, `VIEWER`). The Product Owner has requested moving to a dynamic DB-backed role/permission system to support custom roles with granular permission mappings.

## Goals
1. Transition from statically defined `RoleToken` enums to dynamically managed Role entities in the database.
2. Introduce a granular Permissions model that maps to specific system actions.
3. Expose APIs to allow administrators to create, update, and delete custom roles, and assign users to them.

## Architecture

### 1. Data Model
Introduce `roles`, `permissions`, `role_permissions`, and map users to roles.

```sql
CREATE TABLE permissions (
    id UUID PRIMARY KEY,
    key VARCHAR(255) UNIQUE NOT NULL, -- e.g., 'devices:read', 'devices:write'
    description TEXT
);

CREATE TABLE roles (
    id UUID PRIMARY KEY,
    name VARCHAR(255) UNIQUE NOT NULL,
    description TEXT,
    is_system BOOLEAN DEFAULT FALSE, -- Predefined roles cannot be deleted
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE role_permissions (
    role_id UUID REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_roles (
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    role_id UUID REFERENCES roles(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);
```

### 2. Migration of RoleToken
Existing `RoleToken` values will be migrated into `roles` with `is_system = TRUE`. A bootstrap script will populate the `permissions` table with the known capabilities of the system and associate them with the system roles to maintain backward compatibility for existing users.

### 3. API Changes
- `GET /api/v1/roles`: List all roles.
- `POST /api/v1/roles`: Create a custom role.
- `PUT /api/v1/roles/{id}`: Update permissions for a custom role.
- `DELETE /api/v1/roles/{id}`: Delete a custom role (blocked if `is_system=TRUE`).

### 4. Enforcement
The authorization interceptors (Spring Security `@PreAuthorize` or custom HandlerInterceptors) will be updated to check against the new `permissions` table instead of relying directly on role names (e.g., `hasAuthority('devices:write')` instead of `hasRole('ADMIN')`).

## Deployment / Rollout Strategy
1. **Phase 1**: Database schema updates and population of default roles/permissions.
2. **Phase 2**: Migrate internal authorization logic to check permissions instead of hardcoded roles.
3. **Phase 3**: Expose the UI for administrators to create custom roles and assign them to users.
