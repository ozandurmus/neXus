# Custom RBAC Roles (DRAFT)

## Overview
Migrate from a closed vocabulary `RoleToken` to a dynamic DB-backed role/permission system.

## Architectural Decisions
- Create `Role` and `Permission` entities with a many-to-many relationship.
- Map users to `Role` entities.
- Seed predefined roles (Admin, User, etc.) on application startup.
- Update UI 2.0 to manage Roles and their associated permissions dynamically.
- Introduce method-level security using `@PreAuthorize("hasPermission(..., 'read')")`.

## Data Model
- `Role (id, name, description)`
- `Permission (id, name, resource, action)`
- `Role_Permission` junction table.
- `User_Role` junction table.

## Endpoints
- `GET /api/v2/roles`
- `POST /api/v2/roles`
- `PUT /api/v2/roles/{id}`
- `DELETE /api/v2/roles/{id}`

## UI Components
- Role list view.
- Role editor with permission matrix (checkboxes for Create, Read, Update, Delete per resource).

## Security
- Requires admin privileges to modify roles.
