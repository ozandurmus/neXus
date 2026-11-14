# UI2 local password policy, configurable and off by default: minimum/maximum length, character classes, not equal to the username, optional expiry and history -- a production posture switch, never a development obligation (PO 2026-09-14)

status: planned · target: PasswordChangeService and LocalIdentityAdministration set-password paths; application.yml ui2.local-auth.*; frontend shows the active rules on the change screen

## 2026-10-17 -- project JSON compacted (size budget)

Title (full): UI2 local password policy, configurable and off by default: minimum/maximum length, character classes, not equal to the username, optional expiry and history -- a production posture switch, never a development obligation (PO 2026-09-14)

Target (full): PasswordChangeService and LocalIdentityAdministration set-password paths; application.yml ui2.local-auth.*; frontend shows the active rules on the change screen

2026-11-14 backlog truth: still open (P3, size S). Today: Local accounts are break-glass now (LDAP primary); stronger policy is optional hardening. Evidence: LocalPasswordPolicy.java enforces only min length 12 and not-equal-name; ui2.local-auth has one flag, no classes/expiry/history
