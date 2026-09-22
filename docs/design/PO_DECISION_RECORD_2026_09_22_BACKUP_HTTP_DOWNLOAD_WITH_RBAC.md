# PO Decision Record — 2026-09-22 — Backup artefact download over HTTP, gated by RBAC

## Status

**RATIFIED — PRODUCT OWNER DECISION, 2026-09-22.** Supersedes 14I OR-1 / OR-2 /
AC-4 ("no HTTP route of any kind returns bytes, a path or a decrypt affordance;
the operator's only retrieval path is `ui2/cli`") for backup artefacts. The
audit-first (OR-3), reason (BK-12) and `role:backup_admin` requirements are kept
and now also govern the HTTP path.

## Decision

The Product Owner directed, told plainly that the alternative was a CLI-only
retrieval path, that a backup artefact may be downloaded from the browser. Session
transcript, 2026-09-22 (paraphrased from Turkish): as the super-administrator the
Product Owner already authorises these devices' backups being taken and downloaded;
the Product Owner also holds direct firewall access and can download the same
archive from the device itself, so a product-side prohibition adds no protection --
"a correctly applied RBAC is sufficient here". The product target is "at minimum
the Backbox standard", where operators download and compare backups from the UI.

## What changes

- One new route, `POST /backups/{artefactId}/download`, mapped to the action
  `device_backup_retrieve`, which requires `role:backup_admin`, a CSRF token and
  an authenticated actor fingerprint (the same E4 gate every other role-bound
  route passes). The body carries a reason of at least eight characters.
- The service records the retrieval audit row **before** any byte is read
  (`backup_artefact_retrieval`, action `backup_artefact_downloaded`, destination
  `browser`). An audit write failure refuses the download with no bytes sent
  (OR-3 fail-closed, unchanged).
- The service pod mounts the artefact-store key and the artefact-store volume
  (read-only) so it can decrypt. The worker's `open()` path is unchanged; the
  service only ever calls `retrieve()`.
- The `Export` dialog on the Backup screen performs the download instead of
  printing a CLI command it never ran.
- `BackupNoHttpRetrievalRouteTest` (the grep for download routes) is replaced by
  a test that asserts the download route is mapped to a `role:backup_admin`
  action -- the invariant is now "never an ungated download", not "never a
  download".
- The response file name carries the vendor, the opaque artefact id prefix and
  the collection time; never a hostname or an address.

## What does not change

- The listing routes still return no path, no bytes and no decrypt affordance
  (BK-14 / OR-1's identifier rule stands: `artefact_id` is an opaque UUID).
- `ui2/cli backup-retrieve` stays as the CLI counterpart (the Product Owner
  separately asked, P1, that every UI action keep a CLI equivalent).
- The plaintext archive may carry secret-bearing files (a Gaia backup contains
  the system's own account database). The download dialog says so; the product
  does not retain the downloaded copy and does not open it.

## Consequence the Product Owner accepted

A `role:backup_admin` session that is hijacked (cookie theft, an unattended
browser) can download every backup the fleet has taken, from anywhere the UI is
reachable. Under the CLI-only model the same attacker also needed a shell on the
worker node. The Product Owner assessed this as acceptable for an environment
where the same person already holds direct device access.
