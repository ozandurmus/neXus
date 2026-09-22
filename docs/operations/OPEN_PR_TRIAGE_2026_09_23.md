# Open GitHub pull requests — triage (2026-09-23)

**Status: PROPOSAL — Product Owner decides.** `main` on GitHub already carries every change of 2026-09-22/23 (last commit pushed with this document). These 21 pull requests date from 2026-09-15..17, before the UI2 work of this week; none has been merged or closed by the engineering session.

Measured against `main` with `git merge-tree` (no working tree touched).

| PR | Updated | Merge into main | Title | Recommendation |
|---|---|---|---|---|
| #425 | 2026-09-16 | clean | Add LDAP server configuration UI | Close — superseded: Administration › LDAP Settings exists on main (DirectorySettingsPanel, LDAPS/StartTLS fixed 2026-09-22). |
| #424 | 2026-09-16 | clean | Add discovery candidate select all | Review and port if still wanted — 'select all' for discovery candidates; small, applies cleanly. |
| #423 | 2026-09-16 | 1 conflict(s) | Clean up connected user role display | Close — superseded by the role display on main. |
| #422 | 2026-09-17 | 70 conflict(s) | feat: SSH Trust TOFU & LDAP configuration screen | Close — 48 commits, 70 conflicts; TOFU host keys and LDAP screen both exist on main in later form. |
| #419 | 2026-09-16 | clean | docs: draft selected Workbench queue admission contract | Close — Workbench (agent tooling) draft, not product. |
| #416 | 2026-09-16 | 1 conflict(s) | Fix Companion current coverage tracking | Close — Workbench Companion tooling, not product. |
| #415 | 2026-09-16 | 2 conflict(s) | Enforce UI2 database role ownership boundaries | Re-implement on main (new backlog item) — database role ownership boundaries are production hardening the product still lacks. |
| #409 | 2026-09-15 | 2 conflict(s) | Generate Workbench Companion C5 LaunchAgent lifecycle artifacts | Close — Workbench Companion tooling, not product. |
| #404 | 2026-09-15 | 1 conflict(s) | Add bounded Jobs read panel | Close — superseded: Operations › Jobs on main (filters, pages, export). |
| #400 | 2026-09-15 | 2 conflict(s) | Improve Workbench board reliability | Close — Workbench tooling, not product. |
| #396 | 2026-09-15 | clean | feat(ui2): isolate PostgreSQL network access | Re-implement on main (new backlog item) — PostgreSQL NetworkPolicy isolation; applies cleanly but manifests are applied by hand and must be tested on HOST-A. |
| #393 | 2026-09-15 | 5 conflict(s) | Implement C9 exclusive replay sessions and anonymized device reads | Close — replay sessions / aiview persona exist on main in later form. |
| #391 | 2026-09-15 | 1 conflict(s) | Design the Java UI2 mockup-palace synthetic test environment (DRAFT) | Close — design draft superseded by the aiview persona. |
| #390 | 2026-09-15 | 1 conflict(s) | Draft: production replay-role anonymized view boundary design | Close — design draft superseded (aiview masking; masking leak audit in backlog). |
| #389 | 2026-09-15 | 6 conflict(s) | Thread server-only recovery_volume_path into backup artefact retrieval | Close — superseded by the artefact store v2 on main. |
| #388 | 2026-09-15 | 3 conflict(s) | Persist and re-check backup claim authorization (BK-12/BW-4) | Re-check on main — backup claim authorization (BK-12/BW-4); verify main enforces it, else re-implement. |
| #386 | 2026-09-15 | 2 conflict(s) | Make backup_artefact.artefact_id opaque, decoupled from storage path ( | Close — artefact ids on main are opaque (artefact store v2). |
| #385 | 2026-09-15 | 1 conflict(s) | Draft: discovery-integrated SSH trust enrollment contract (NXS-LOCAL-0 | Close — SSH trust enrollment exists on main (TOFU with warn-not-refuse PO decision). |
| #384 | 2026-09-15 | 1 conflict(s) | Show the safe discovery failure class in the Add device dialog | Review and port if still wanted — safe discovery failure class in Add device; small. |
| #358 | 2026-09-15 | clean | NXS-LOCAL-0198: analyze authentication placement options | Close — analysis document for an authentication decision already taken. |
| #357 | 2026-09-15 | 2 conflict(s) | Add session conflict takeover UI | Review — session conflict takeover UI; check against Sessions on main. |

Summary: close 15 as superseded or non-product tooling; review 3 small ones (#424, #384, #357); re-implement or re-check 3 on current main (#415 database role ownership, #396 PostgreSQL network isolation, #388 backup claim authorization) as new backlog items rather than merging week-old branches over this week's code.
