# aiview as a masked read-only administrator (PO 2026-09-23): first extend server-side masking to principals (usernames, audit actors, LDAP bind DN and group names, e-mail addresses, credential usernames) with per-endpoint aiview tests; then grant role:replay_viewer the READ actions of Credentials, Local identities, Sessions, Roles & Permissions, LDAP, Notifications and Audit Logs (every write action stays denied); record the RBAC change as a PO decision record

status: planned · target:

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): aiview as a masked read-only administrator (PO 2026-09-23): first extend server-side masking to principals (usernames, audit actors, LDAP bind DN and group names, e-mail addresses, credential usernames) with per-endpoint aiview tests; then grant role:replay_viewer the READ actions of Credentials, Local identities, Sessions, Roles & Permissions, LDAP, Notifications and Audit Logs (every write action stays denied); record the RBAC change as a PO decision record

2026-11-14 backlog truth: still open (P2, size M). Today: aiview cannot review admin screens; masked read-only admin view not built. Evidence: ActionRegistry: LOCAL_IDENTITY_LIST etc. security_admin only; aiview not granted admin reads; principal masking absent

Slice 1 shipped 26330958: UI2_AIVIEW_READONLY_ADMIN switch, default off, three masked settings reads. Draft: AIVIEW_MASKED_READONLY_ADMIN_DRAFT_2026_11_20.
