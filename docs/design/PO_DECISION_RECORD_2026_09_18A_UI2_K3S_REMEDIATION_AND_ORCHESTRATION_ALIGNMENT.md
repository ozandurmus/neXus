# PO Decision Record 2026-09-18A — UI2 K3s Remediation and Orchestrator Governance Alignment

- **Status:** FROZEN
- **Authority:** Level 2 (Scope-specific design law, ratified by Product Owner)
- **Date:** 2026-09-18
- **Build Tag:** `NXS-LOCAL-0328`
- **Subject:** Remediation of Live UI2 on K3s Cluster HOST_A (`ui2.nexus.local`), AD Group Role Mappings, 5 Product Planes RBAC Model, and Strict Orchestrator/Relay Operating Protocol

---

## 1. Context and Motivation

During live operator testing on the development K3s cluster HOST_A (`ui2.nexus.local`), several functional blockers and UX regressions were identified across discovery, gateway inventory, authentication, and role authorization. Furthermore, the Product Owner (PO) observed that emergency live fixes were implemented monolithically within the assistant session rather than through the repository's prescribed Orchestrator / Relay dispatch workflow (`scripts/orchestrator.py`, `relay/NXS-LOCAL-*.json`).

This decision record serves two purposes:
1. **Durable Architecture Record:** Formally documents the 5 technical remediation items delivered and validated on the live cluster.
2. **Vendor-Neutral Governance Law:** Establishes the exact prompt protocol and operational contract to ensure all future build and test requests strictly execute through the Orchestrator/Relay workflow, preserving complete continuity whether the PO assistant role is operated by Antigravity/Gemini, Claude, or ChatGPT.

---

## 2. Technical Remediation Specifications

### 2.1 Item 1: Static SVG Asset Bundling (Action Mapping Bypassed)
- **Problem:** Dynamic Figma mock-up SVG paths requested by `NexusWordmark.tsx` and `NexusMark.tsx` (e.g. `/docs/design/ui2_mockups/.../wordmark.svg`) were intercepted by Spring Security API routing and rejected with `{"error":"ACTION_MAPPING_REQUIRED"}`.
- **Solution:** SVG assets were copied directly into `ui2/frontend/src/assets/` and referenced via static module imports in Vite. They are packaged into the application static bundle and served under `dist/assets/` directly by the Spring Boot static handler.
- **Invariant:** Shell brand marks must never depend on unmapped dynamic API routes or external file URLs.

### 2.2 Item 2a: Check Point Management Discovery Environment Sourcing
- **Problem:** Check Point MDS/SMS discovery runs returned 0 candidate gateways despite valid credentials because the SSH remote command execution did not inherit interactive Gaia shell environment variables (`$CPDIR`, `$MDSDIR`), causing `mgmt_cli` commands to fail silently.
- **Solution:** In `ManagementShellCommands.java`, the command strings for `domainList()` and `contextSwitchAndObjectQuery()` were updated to prepend `source /etc/profile.d/CP.sh;`. The command prefix validator was updated accordingly.
- **Result:** Check Point management discovery successfully parses all domains and returns all 157 candidate gateways.

### 2.3 Item 2b: SSH Trust-On-First-Use (TOFU) & Auto-Enrollment for Discovered Gateways
- **Problem:** Gateways discovered from Check Point management servers failed during `cp_inventory_collect` with `connect_failed: host_key_rejected: TRUST_ENTRY_MISSING` because discovered endpoints did not yet have an approved entry in `management_endpoint_ssh_trust`.
- **Solution:**
  - Extended `TrustRuleResolver.java` and `PersistedManagementEndpointTrustResolver.java` with TOFU capability controlled by `UI2_SSH_ALLOW_TOFU` (default `true` in worker).
  - In `HostKeyVerifier.java`, if an active host key is missing in DB and `allowTrustOnFirstUse` is enabled, the observed host key is recorded via `recordTrustOnFirstUse()` into `management_endpoint_ssh_trust` as `ACTIVE` with `authorized_by: "system_auto_enroll"`, returning `Decision.MATCH`.
- **Invariant:** Known keys with mismatching fingerprints are **refused** (`Decision.MISMATCH`). Only absent keys are eligible for TOFU auto-enrollment.

### 2.4 Item 3: Active Directory Group to Role Mapping (API & UI)
- **Problem:** Operators had no mechanism in UI2 to map LDAP / Active Directory security groups (e.g. `Domain Admins`) to product roles without manually constructing database records.
- **Solution:**
  - **Backend:** `RoleBindingAdminService.java` and `RoleBindingAdminController.java` expose `GET /role-bindings` to list active bindings and support `POST /role-bindings` with `binding_kind: "DIRECTORY_GROUP"`, storing encrypted group references via `GroupReferenceCipher`. Revocation is supported via `POST /role-bindings/revoke`.
  - **Frontend:** In `CustomRolesPanel.tsx`, added the "Active Directory Group Mappings" table and "Map Directory Group" dialog, allowing operators to bind any AD group name or DN directly to a role.

### 2.5 Item 4: Five Product Planes Permissions Model & Menu Filtering
- **Model:** Defined the 5 canonical neXus product planes:
  1. `Devices` (Inventory, Discovery, Workspace)
  2. `Config` (Configuration Collection, Override, Deviation)
  3. `Compliance` (Compliance Frameworks, Rules, Checks)
  4. `Operations` (Readiness, HA, Jobs, Backups)
  5. `Admin` (Local Identities, Roles, LDAP, Credential Store)
- **Frontend Architecture Guard (`NoRoleConditionalRenderingInFrontendTest`):**
  - The frontend contains **zero literal role tokens** (e.g. `role:security_admin` is forbidden in `frontend/src`).
  - `/session/status` returns the resolved `permissions` array (the planes the user holds).
  - `NavigationRail.tsx` filters destinations and drawer groups purely based on `permissions.includes(requiredPlane)`.
  - `CustomRolesPanel.tsx` displays plane chips and allows assigning any of the 5 planes to custom roles.

### 2.6 Item 5: TopAppBar Active Build Badge
- **Solution:** `TopAppBar.tsx` queries `getProjectPlan()` asynchronously on mount and displays the active build identifier (`current_build` or `current_product_build`) as an M3 badge adjacent to the brand logo.

---

## 3. Live Cluster Verification Evidence (HOST_A — ui2.nexus.local)

- **Pod State:**
  ```text
  ui2-service-5576d5b745-ssxph   1/1   Running   0   (healthy)
  ui2-worker-6d97cf5ff5-998m5    1/1   Running   0   (healthy)
  ui2-db-0                       1/1   Running   0   (healthy)
  ```
- **Static Assets:** `curl -I https://127.0.0.1/assets/wordmark-tagline-B0vgzewu.svg` returns `HTTP/2 200 OK` (`content-type: image/svg+xml`).
- **Discovery Validation:** Historic runs confirmed 157 candidates; Gaia shell sourcing active.
- **Session & Permissions:** `GET /session/status` for `nexusadmin`:
  ```json
  {
    "authenticated": true,
    "display_name": "nexusadmin",
    "role_tokens": ["role:viewer", "role:operator", "role:onboarding_admin", "role:backup_admin", "role:compliance_admin", "role:security_admin"],
    "permissions": ["Devices", "Config", "Compliance", "Operations", "Admin"]
  }
  ```
- **Directory Role Binding:**
  - `POST /role-bindings` mapped `CN=Domain Admins,CN=Users,DC=nexus,DC=local` to `role:security_admin` (ID: `f2b62064-fba5-4bf3-95c7-111ca785623e`).
  - `GET /role-bindings` verified active mapping.
  - `POST /role-bindings/revoke` revoked the binding; database verified `revoked_at: 2026-09-17 21:04:21.528663+00`.

---

## 4. Orchestration & Relay Governance Protocol (Vendor-Neutral Law)

To eliminate monolithic in-session coding and ensure complete continuity across models (Antigravity/Gemini, Claude, ChatGPT), the following operational protocol is **MANDATORY**:

### 4.1 Separation of Roles
1. **The PO Assistant Agent (Lead Orchestrator):**
   - Must NEVER write product implementation code directly into the repository unless explicitly authorized for a 1-line hotfix.
   - Responsible for: Scope clarification, contract drafting/freezing, architectural review, running `scripts/project_queue.py` and `scripts/orchestrator.py`, test verification, and deployment.
2. **The Worker Subagents (Codex / Flash / Astra):**
   - Execute the code implementation within bounded contracts and targeted test files.
   - Report results through relay packets (`relay/NXS-LOCAL-*.json`) or orchestrator dispatch ledger (`scripts/dispatch_ledger.py`).

### 4.2 Standard PO Prompt Template for Build / Test Requests
When the Product Owner submits a feature or test request, the following prompt structure ensures no agent skips the orchestration workflow:

```markdown
[PO DIRECTIVE: ORCHESTRATION & RELAY DISPATCH REQUIRED]
Scope: <Short description of the feature or bugfix>
Target Build: <e.g. NXS-LOCAL-0329>
Worker Model Preferred: <Codex / Gemini Flash / Astra>

Instructions:
1. Act strictly as the Lead Orchestrator (PO Assistant). Do not write implementation code directly in this session.
2. Audit the relevant FROZEN contract and create/update the task contract if needed.
3. Queue and dispatch the work to the designated worker model using `scripts/orchestrator.py` or relay dispatch.
4. Verify the worker's relay output and ensure local tests pass (Gradle, ArchUnit, repository privacy check).
5. Only after verification, summarize the outcome and request deployment approval.
```

When an agent receives this prompt or any instruction to implement a build, the presence of `[PO DIRECTIVE: ORCHESTRATION & RELAY DISPATCH REQUIRED]` or references to `orchestrator.py` prohibits the agent from writing code directly.
