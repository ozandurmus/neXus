# DEPLOY.1A — Authenticated Read-Only Viewer Boundary

status: deferred · target: DEPLOY.1A

Corporate OIDC login gate required before server is opened internally. IP allowlist is defense-in-depth only. No credential or raw operational artifact in browser/export. Login and view/export audit events required.

2026-09-13 realignment: closed as superseded. UI 2.0's authenticated surface is the service under C3/C3A (login required, RBAC in service). What remains open is where authentication sits once a second deployable service exists -- 13D AUTH-PLACEMENT -- re-cut as ui2_auth_placement_second_authenticated_surface. PO_DECISION_RECORD_2026_09_12 section 2 (2026-09-12): all UI 2.0 feature work is Java written from scratch; the existing Python is know-how only. Backlog realignment 2026-09-13, approved by the Product Owner in session.
