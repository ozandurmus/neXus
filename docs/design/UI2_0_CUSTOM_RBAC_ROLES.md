# UI 2.0 Custom RBAC Roles

**Status:** FROZEN

## 1. Introduction
The Product Owner requested transitioning from the current closed, enum-based `RoleToken` vocabulary to a dynamic, database-backed custom RBAC (Role-Based Access Control) system. This design outlines the necessary schema and logic changes.

## 2. Current Architecture
- **`RoleToken.java`:** Defines a hardcoded set of roles (`VIEWER`, `OPERATOR`, `ONBOARDING_ADMIN`, etc.).
- **`RbacEvaluator.java`:** Evaluates an actor's access against a required `RoleToken` utilizing `RoleBindingRecord`.

## 3. Proposed Architecture

### 3.1 Database Schema
We will introduce tables to represent dynamic roles and their granular permissions.

- **`rbac_roles`**
  - `id` (Primary Key, UUID)
  - `name` (String, unique, e.g., "Custom Security Admin")
  - `token_string` (String, unique, e.g., "role:custom_security_admin")
  - `description` (String)
  - `is_system` (Boolean, true for default legacy roles to prevent deletion)

- **`rbac_permissions`**
  - `id` (Primary Key, UUID)
  - `action` (String, e.g., "view:reports", "edit:users")
  
- **`rbac_role_permissions`** (Join Table)
  - `role_id` (UUID)
  - `permission_id` (UUID)

### 3.2 Backend Changes
- **Replace Enum:** The `RoleToken` enum will be replaced by a `Role` entity model. Methods expecting `RoleToken` will be refactored to accept a String token or a `Role` object.
- **Dynamic Evaluation:** `RbacEvaluator.evaluate()` will be modified to resolve the requested token dynamically against the `rbac_roles` table rather than the closed enum values. It will check if the user's bound roles map to the required context.
- **Role Binding Updates:** `RoleBindingRecord` and `RoleBindingRepository` will need to link to the new `rbac_roles` table (e.g., via `role_id` or `token_string`).
- **Granular Permissions (Optional Phase 2):** In the future, evaluation could transition from checking role presence to checking specific permission presence.

### 3.3 UI Implementation
- A Roles & Permissions management view.
- Ability to create a custom role, assign a name, and potentially select from a list of granular permissions.
- Ability to bind these new custom roles to existing LDAP groups or Local Identities.

## 4. Migration Strategy
- A database migration script will insert the existing `RoleToken` values (`VIEWER`, `OPERATOR`, etc.) into the `rbac_roles` table with `is_system = true`.
- Existing `RoleBindingRecord` entries will continue to function since the `token_string` will match the newly migrated rows.

## 5. Resolved Questions
- **Permission Granularity:** Custom roles will be composed of granular permissions matching the ActionRegistry vocabulary.
- **Hierarchy:** Custom roles will NOT support hierarchy to keep evaluation flat.

## 6. Alignment with AGENTS.md
- **Safe Evaluation:** Unresolvable states or evaluation failures must safely fail-closed (DENIED / AUTHZ_NOT_EVALUATED), maintaining the existing `RbacEvaluator` guarantees.
