# PO Decision Record — 2026-11-11 — LDAP sign-in moves to the Active Directory service-bind model

## Status

**RATIFIED — PRODUCT OWNER DECISION, 2026-11-11.** Supersedes the bind-DN-template
(`%s`) directory profile model of V26 (`directory_profiles`). Design and contract:
`docs/design/LDAP_SERVICE_BIND_DESIGN_2026_11_11.md`.

## Decision

The Product Owner, verbatim (2026-11-11):

> "tamamiyle tufinin yaptığı gibi bir yapıya geçelim. Herkes samaccountname
> kullanıyorsa biz niye saçma sapan yüzdelerle uğraşıyoruz ?"

In English: move entirely to a design like Tufin's; everyone signs in with the
`sAMAccountName`, so there is no reason to deal with `%s` templates.

Context: the PO configured LDAP on 2026-11-11 and could not sign in. The saved profile
had no `%s` in its bind-DN template, an empty access group, no CA certificate, and an IP
address as host under a strict hostname check; the loader logged nothing. The PO compared
the screen with Tufin's Active Directory integration, which works in the same environment
with a service bind account, a `sAMAccountName` search, administrators and users group
DNs and a "trust any certificate" option.

## What this decides

1. Sign-in uses a service account: bind as the service account, search the domain DN for
   exactly one `user` whose `sAMAccountName` equals the typed name, bind as that entry
   with the typed password, then require membership of the administrators group or the
   users group.
2. Settings follow the Tufin form: enable switch, server type (Active Directory), one or
   more servers tried in order, domain DN, administrators group DN, users group DN, port
   (default 636), bind DN and bind password, connection timeout, LDAPS on/off, and two
   trust modes.
3. **"Trust any certificate" is a PO-owned risk.** In that mode neXus accepts any
   certificate the directory presents, with no chain and no hostname check, so an IP
   address works and a machine impersonating the directory would also be accepted and
   would receive the service account password and every signing-in user's password. The
   PO owns this risk on the same basis as the Palo Alto decision
   (`PO_DECISION_RECORD_2026_09_21_PAN_TLS_VERIFICATION_DISABLED.md`): an internal-only
   environment. The other mode, "trust only the certificate below", verifies the chain
   against the pasted certificate and the host name (or an IP SAN).
4. LDAPS can be switched off, as in the Tufin form. With it off, passwords cross the
   network in clear text; the screen says so.
5. The old `directory_profiles` table is retired. Its one live row was invalid on every
   axis and is not migrated; the PO re-enters the settings in the new form.
6. Role mapping (below), written as system-managed `DIRECTORY_GROUP` role bindings for
   directory profile `default`, created and revoked only by the settings save.

## Role mapping (frozen)

RATIFIED 2026-11-11 in the PO session (answer: "Öneri gibi", i.e. as proposed):

- Administrators group DN -> `role:security_admin`, `role:operation_admin`, `role:operator`,
  `role:onboarding_admin`, `role:backup_admin`, `role:compliance_admin`, `role:viewer`.
  Never `role:replay_viewer` (the masked projection).
- Users group DN -> `role:viewer` only.
- A user in both groups gets the administrators set.

## What does not change

Local sign-in and the root (break-glass) identity work exactly as before and do not
depend on the directory. The generic sign-in failure body, the directory login throttle,
the single-session conflict flow, and the at-rest encryption of directory principal and
group references are unchanged.
